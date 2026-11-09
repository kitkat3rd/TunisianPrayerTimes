package com.tunisianprayertimes.adhan

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.tunisianprayertimes.MainActivityPendingIntents
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.PrayerTimesRepository.PrayerOccurrence
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Arms the adhans of the enabled prayers: every occurrence after now over prayer days D − 1..D + 1 in the place's zone
 * (6.6), at the time the prayer tab shows, Jumu'a at its resolved time on a Friday (A18). Each occurrence has its own
 * alarm-clock alarm under its prayer and day slot, as exact as the silence's. Each adhan re-arms the next ones, and
 * the screens, boot and time changes refresh them through [com.tunisianprayertimes.ScheduleRefreshCoordinator].
 */
object AdhanScheduler {
    private const val TAG = "AdhanScheduler"
    internal const val ACTION_ADHAN = "com.tunisianprayertimes.adhan.ACTION_ADHAN"
    internal const val ACTION_RESUME_SILENCE = "com.tunisianprayertimes.adhan.ACTION_RESUME_SILENCE"
    private const val EXTRA_PRAYER = "com.tunisianprayertimes.adhan.extra.PRAYER"
    private const val EXTRA_PRAYER_DAY = "com.tunisianprayertimes.adhan.extra.PRAYER_DAY"
    private const val EXTRA_AT = "com.tunisianprayertimes.adhan.extra.AT"

    /** Request codes 760_000 + prayer × 3 + day slot; the silence-resume safety alarm has its own. */
    private const val CODE_BASE = 760_000
    private const val RESUME_CODE = 760_099
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
        val enabled = AdhanSettings.enabledPrayers(app).toSet()
        val armed = HashSet<Int>()
        if (enabled.isNotEmpty()) {
            val place = PrayerTimesRepository.place(app)
            val today = now.atZone(place.zone).toLocalDate()
            val occurrences = DAY_OFFSETS.flatMap { PrayerTimesRepository.prayerOccurrences(app, today.plusDays(it), place) }
            for (occurrence in upcoming(occurrences, enabled, now)) {
                val code = requestCode(occurrence.prayer, occurrence.prayerDay.toEpochDay() - today.toEpochDay()) ?: continue
                val adhan = Occurrence(occurrence.prayer, occurrence.prayerDay, occurrence.at.toEpochMilli())
                runCatching { arm(app, alarms, code, adhan) }
                    .onSuccess { armed += code }
                    .onFailure { Log.w(TAG, "Could not arm ${adhan.prayer} of ${adhan.prayerDay}", it) }
            }
        }
        for (code in allCodes()) if (code !in armed) cancel(app, alarms, code)
        Log.d(TAG, "Armed ${armed.size} adhans for $enabled")
    }

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

    private fun arm(app: Context, alarms: AlarmManager, code: Int, occurrence: Occurrence) {
        val operation = PendingIntent.getBroadcast(
            app, code, adhanIntent(app, code).putOccurrence(occurrence),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (canScheduleExact(alarms)) {
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(occurrence.atMillis, MainActivityPendingIntents.adhan(app)), operation)
        } else {
            // Without the exact-alarm permission Android may hold the alarm back; an adhan that comes late stays quiet.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, occurrence.atMillis, operation)
        }
    }

    private fun cancel(app: Context, alarms: AlarmManager, code: Int) {
        PendingIntent.getBroadcast(app, code, adhanIntent(app, code), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            ?.let { pending ->
                alarms.cancel(pending)
                pending.cancel()
            }
    }

    private fun adhanIntent(app: Context, code: Int): Intent =
        Intent(app, AdhanReceiver::class.java).setAction(ACTION_ADHAN).setData(Uri.parse("tunisianprayertimes://adhan/$code"))

    /**
     * A safety alarm for the prayer silence an adhan holds back: should the player die before it ends, the silence
     * still comes back by [atMillis].
     */
    internal fun armSilenceResume(context: Context, atMillis: Long) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            if (canScheduleExact(alarms)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, resumeIntent(app, PendingIntent.FLAG_UPDATE_CURRENT)!!)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, resumeIntent(app, PendingIntent.FLAG_UPDATE_CURRENT)!!)
            }
        }.onFailure { Log.w(TAG, "Could not arm the silence resume", it) }
    }

    internal fun cancelSilenceResume(context: Context) {
        val app = context.applicationContext
        val pending = resumeIntent(app, PendingIntent.FLAG_NO_CREATE) ?: return
        app.getSystemService(AlarmManager::class.java)?.cancel(pending)
        pending.cancel()
    }

    private fun resumeIntent(app: Context, flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        app, RESUME_CODE,
        Intent(app, AdhanReceiver::class.java).setAction(ACTION_RESUME_SILENCE),
        flag or PendingIntent.FLAG_IMMUTABLE,
    )

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
