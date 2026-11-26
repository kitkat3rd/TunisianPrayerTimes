package com.tunisianprayertimes.adhan

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.tunisianprayertimes.MainActivityPendingIntents
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.PrayerTimesRepository.PrayerOccurrence
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.silence.SilenceWindow
import com.tunisianprayertimes.silence.SilenceWindowPlanner
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Arms the adhans of the enabled prayers: every occurrence after now over prayer days D − 1..D + 1 in the place's zone
 * (6.6), at the time the prayer tab shows, Jumu'a at its resolved time on a Friday (A18). Each occurrence has its own
 * alarm-clock alarm under its prayer and day slot, as exact as the silence's, and, with [AdhanSettings.warnsBefore],
 * a second one [WARNING_LEAD_MILLIS] earlier for its warning vibration. When the app's own time silence will already
 * be on at that warning, which then cannot vibrate, a third one buzzes [BEFORE_SILENCE_LEAD_MILLIS] before that silence
 * begins. Each adhan re-arms the next ones, and the screens, boot and time changes refresh them through
 * [com.tunisianprayertimes.ScheduleRefreshCoordinator].
 */
object AdhanScheduler {
    private const val TAG = "AdhanScheduler"
    internal const val ACTION_ADHAN = "com.tunisianprayertimes.adhan.ACTION_ADHAN"

    /** Shortly before an adhan: the warning vibration ([AdhanSettings.warnsBefore]). */
    internal const val ACTION_ADHAN_SOON = "com.tunisianprayertimes.adhan.ACTION_ADHAN_SOON"

    /**
     * How long before the adhan the warning comes: time to feel it and press a volume key, even with the phone in a
     * pocket, and short enough to wait for. The owner found one minute too long (2026-11-15).
     */
    internal const val WARNING_LEAD_MILLIS = 15_000L

    /**
     * Just before the app's time silence begins, when that silence would keep the warning still: Android's total
     * silence stops every app's vibration, the app's own too. The owner: "vibrate before the app silences" (2026-11-16).
     */
    internal const val ACTION_BEFORE_SILENCE = "com.tunisianprayertimes.adhan.ACTION_BEFORE_SILENCE"

    /** How long before such a silence the buzz comes: the silence's alarm is exact, so a few seconds are enough. */
    internal const val BEFORE_SILENCE_LEAD_MILLIS = 10_000L
    private const val LEGACY_ACTION_RESUME_SILENCE = "com.tunisianprayertimes.adhan.ACTION_RESUME_SILENCE"
    private const val EXTRA_PRAYER = "com.tunisianprayertimes.adhan.extra.PRAYER"
    private const val EXTRA_PRAYER_DAY = "com.tunisianprayertimes.adhan.extra.PRAYER_DAY"
    private const val EXTRA_AT = "com.tunisianprayertimes.adhan.extra.AT"

    /** Request codes 760_000 + prayer × 3 + day slot; retain the former resume code only for upgrade cleanup. */
    private const val CODE_BASE = 760_000
    private const val RESUME_CODE = 760_099

    /** The warnings' request codes: the adhans' codes moved by this much. */
    private const val WARNING_SHIFT = 1_000

    /** The buzzes before a silence: the adhans' codes moved by this much. */
    private const val BEFORE_SILENCE_SHIFT = 2_000
    private val DAY_OFFSETS = -1L..1L
    private val SLOTS = DAY_OFFSETS.count()

    /** One adhan: a prayer of a prayer day, at its instant. */
    data class Occurrence(val prayer: Prayer, val prayerDay: LocalDate, val atMillis: Long)

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    /** Refreshes off the caller's thread: it reads the place's prayer days. */
    fun refreshLater(context: Context) {
        val app = context.applicationContext
        background.launch {
            runCatching { refresh(app) }.onFailure { Log.w(TAG, "Could not arm the adhans", it) }
        }
    }

    fun refresh(context: Context, now: Instant = Instant.now()) {
        synchronized(lock) { refreshLocked(context.applicationContext, now) }
    }

    private fun refreshLocked(app: Context, now: Instant) {
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        cancelLegacySilenceResume(app, alarms)
        val enabled = AdhanSettings.enabledPrayers(app).toSet()
        // A warning that came late would be worse than none: only with exact alarms.
        val warns = AdhanSettings.warnsBefore(app) && canScheduleExact(alarms)
        val armed = HashSet<Int>()
        // A restored switch is retained while the user chooses a city. Fall through to cancel stale slots.
        if (enabled.isNotEmpty() && PrefsManager.canUsePlaceForAutomation(app)) {
            val place = PrayerTimesRepository.place(app)
            val today = now.atZone(place.zone).toLocalDate()
            // Only the time windows are known ahead; a mosque silence begins when the user arrives.
            val silences = if (warns && AdhanPlaybackService.timeWindowsSilence(app)) {
                runCatching { SilenceScheduler.planner(app, place) }
                    .onFailure { Log.w(TAG, "Could not read the silence windows", it) }
                    .getOrNull()
            } else {
                null
            }
            val buzzTimes = HashSet<Long>()
            val occurrences = DAY_OFFSETS.flatMap { PrayerTimesRepository.prayerOccurrences(app, today.plusDays(it), place) }
            for (occurrence in upcoming(occurrences, enabled, now)) {
                val code = requestCode(occurrence.prayer, occurrence.prayerDay.toEpochDay() - today.toEpochDay()) ?: continue
                val adhan = Occurrence(occurrence.prayer, occurrence.prayerDay, occurrence.at.toEpochMilli())
                runCatching { arm(app, alarms, adhanIntent(app, code), code, adhan.atMillis, adhan) }
                    .onSuccess { armed += code }
                    .onFailure { Log.w(TAG, "Could not arm ${adhan.prayer} of ${adhan.prayerDay}", it) }
                val warningAt = if (warns) warningAt(adhan.atMillis, now.toEpochMilli()) else null
                if (warningAt != null) {
                    val warningCode = code + WARNING_SHIFT
                    runCatching { arm(app, alarms, warningIntent(app, warningCode), warningCode, warningAt, adhan) }
                        .onSuccess { armed += warningCode }
                        .onFailure { Log.w(TAG, "Could not arm the warning of ${adhan.prayer} of ${adhan.prayerDay}", it) }
                    val silenceStart = silences?.let { planner -> silenceStartBeforeWarning(app, planner, warningAt) }
                    val buzzAt = beforeSilenceAt(silenceStart, now.toEpochMilli())
                    // One buzz per silence, even when two adhans' warnings fall inside it.
                    if (buzzAt != null && buzzTimes.add(buzzAt)) {
                        val buzzCode = code + BEFORE_SILENCE_SHIFT
                        runCatching { arm(app, alarms, beforeSilenceIntent(app, buzzCode), buzzCode, buzzAt, adhan) }
                            .onSuccess { armed += buzzCode }
                            .onFailure { Log.w(TAG, "Could not arm the buzz before the silence of ${adhan.prayer}", it) }
                    }
                }
            }
        }
        for (code in allCodes()) {
            if (code !in armed) cancel(app, alarms, code, adhanIntent(app, code))
            val warningCode = code + WARNING_SHIFT
            if (warningCode !in armed) cancel(app, alarms, warningCode, warningIntent(app, warningCode))
            val buzzCode = code + BEFORE_SILENCE_SHIFT
            if (buzzCode !in armed) cancel(app, alarms, buzzCode, beforeSilenceIntent(app, buzzCode))
        }
        Log.d(TAG, "Armed ${armed.size} adhans, warnings and buzzes before a silence for $enabled")
    }

    /** When the warning of an adhan at [atMillis] comes; null when that moment has already passed. */
    internal fun warningAt(atMillis: Long, nowMillis: Long): Long? =
        (atMillis - WARNING_LEAD_MILLIS).takeIf { it > nowMillis }

    /** The start of the app's time silence that will be on at this warning: the earliest, if several. */
    internal fun silenceStartBeforeWarning(app: Context, planner: SilenceWindowPlanner, warningAtMillis: Long): Long? =
        silenceStartBeforeWarning(planner.containing(Instant.ofEpochMilli(warningAtMillis)), warningAtMillis) { window ->
            SilenceScheduler.isDismissed(app, window.prayer, window.prayerDay)
        }

    internal fun silenceStartBeforeWarning(
        windows: List<SilenceWindow>,
        warningAtMillis: Long,
        dismissed: (SilenceWindow) -> Boolean,
    ): Long? {
        val warning = Instant.ofEpochMilli(warningAtMillis)
        return windows.filter { warning in it && !dismissed(it) }.minOfOrNull { it.start.toEpochMilli() }
    }

    /**
     * When the buzz before [silenceStart] comes; null without such a silence, or when it is too late: a silence that
     * has begun already keeps the phone still. A silence that starts with the adhan is not one: its warning comes first.
     */
    internal fun beforeSilenceAt(silenceStart: Long?, nowMillis: Long): Long? =
        silenceStart?.let { it - BEFORE_SILENCE_LEAD_MILLIS }?.takeIf { it > nowMillis }

    internal fun warningCode(prayer: Prayer, dayOffset: Long): Int? = requestCode(prayer, dayOffset)?.plus(WARNING_SHIFT)

    /** The adhans to arm: the enabled prayers' occurrences after [now], one per prayer and prayer day, by time. */
    internal fun upcoming(occurrences: List<PrayerOccurrence>, enabled: Set<Prayer>, now: Instant): List<PrayerOccurrence> =
        occurrences.filter { it.prayer in enabled && it.at > now }
            .distinctBy { it.prayer to it.prayerDay }
            .sortedBy { it.at }

    internal fun requestCode(prayer: Prayer, dayOffset: Long): Int? {
        val index = AdhanSettings.prayers.indexOf(prayer)
        if (index < 0 || dayOffset !in DAY_OFFSETS) return null
        return CODE_BASE + index * SLOTS + (dayOffset - DAY_OFFSETS.first).toInt()
    }

    private fun allCodes(): List<Int> = AdhanSettings.prayers.indices.flatMap { index ->
        (0 until SLOTS).map { slot -> CODE_BASE + index * SLOTS + slot }
    }

    private fun arm(app: Context, alarms: AlarmManager, intent: Intent, code: Int, triggerAtMillis: Long, occurrence: Occurrence) {
        val operation = PendingIntent.getBroadcast(
            app, code, intent.putOccurrence(occurrence),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (canScheduleExact(alarms)) {
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, MainActivityPendingIntents.adhan(app)), operation)
        } else {
            // Without the exact-alarm permission Android may hold the alarm back; an adhan that comes late stays quiet.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        }
    }

    private fun cancel(app: Context, alarms: AlarmManager, code: Int, intent: Intent) {
        PendingIntent.getBroadcast(app, code, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            ?.let { pending ->
                alarms.cancel(pending)
                pending.cancel()
            }
    }

    private fun adhanIntent(app: Context, code: Int): Intent =
        Intent(app, AdhanReceiver::class.java).setAction(ACTION_ADHAN).setData(Uri.parse("tunisianprayertimes://adhan/$code"))

    private fun warningIntent(app: Context, code: Int): Intent =
        Intent(app, AdhanReceiver::class.java).setAction(ACTION_ADHAN_SOON).setData(Uri.parse("tunisianprayertimes://adhan-soon/$code"))

    private fun beforeSilenceIntent(app: Context, code: Int): Intent = Intent(app, AdhanReceiver::class.java)
        .setAction(ACTION_BEFORE_SILENCE)
        .setData(Uri.parse("tunisianprayertimes://adhan-before-silence/$code"))

    /** Cancel the old adhan hold's safety alarm when upgrading; adhan no longer owns any silence changes. */
    private fun cancelLegacySilenceResume(app: Context, alarms: AlarmManager) {
        val pending = PendingIntent.getBroadcast(
            app, RESUME_CODE,
            Intent(app, AdhanReceiver::class.java).setAction(LEGACY_ACTION_RESUME_SILENCE),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarms.cancel(pending)
        pending.cancel()
    }

    private fun canScheduleExact(alarms: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    internal fun Intent.putOccurrence(occurrence: Occurrence): Intent = this
        .putExtra(EXTRA_PRAYER, occurrence.prayer.name)
        .putExtra(EXTRA_PRAYER_DAY, occurrence.prayerDay.toEpochDay())
        .putExtra(EXTRA_AT, occurrence.atMillis)

    internal fun Intent.occurrence(): Occurrence? {
        val prayer = getStringExtra(EXTRA_PRAYER)?.let { name -> runCatching { Prayer.valueOf(name) }.getOrNull() } ?: return null
        if (!hasExtra(EXTRA_PRAYER_DAY) || !hasExtra(EXTRA_AT)) return null
        return Occurrence(prayer, LocalDate.ofEpochDay(getLongExtra(EXTRA_PRAYER_DAY, 0L)), getLongExtra(EXTRA_AT, 0L))
    }
}
