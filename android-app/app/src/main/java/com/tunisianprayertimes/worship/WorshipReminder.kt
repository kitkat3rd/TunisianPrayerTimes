package com.tunisianprayertimes.worship

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tunisianprayertimes.MainActivity
import com.tunisianprayertimes.MainTabNavigation
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceStatus
import com.tunisianprayertimes.access.ReminderAccessChanges
import com.tunisianprayertimes.adhkar.DhikrReminderScheduler
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.postReminderIfAllowed
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal enum class WorshipTiming(val label: Int) {
    START(R.string.duha_reminder_start), MIDDLE(R.string.duha_reminder_middle),
    AFTER_ISHA(R.string.qiyam_reminder_after_isha), LAST_THIRD(R.string.qiyam_reminder_last_third);

    fun at(window: VoluntaryPrayerWindow, lastThird: Instant? = null): Instant? = when (this) {
        START -> window.start
        MIDDLE -> window.start.plus(Duration.between(window.start, window.end).dividedBy(2))
        AFTER_ISHA -> window.start.plus(Duration.ofMinutes(30))
        LAST_THIRD -> lastThird
    }?.takeIf { it >= window.start && it < window.end }
}

internal enum class WorshipReminder(
    val channelId: String, private val id: Int, val title: Int, val editorTitle: Int,
    private val icon: Int, private val message: Int, val options: List<WorshipTiming>,
) {
    DUHA("duha_reminder", 740601, R.string.duha_reminder_title, R.string.duha_reminder_editor,
        R.drawable.ic_adhkar_sun, R.string.duha_reminder_message, listOf(WorshipTiming.START, WorshipTiming.MIDDLE)),
    QIYAM("qiyam_reminder", 740602, R.string.qiyam_reminder_title, R.string.qiyam_reminder_editor,
        R.drawable.ic_prayer_isha_night, R.string.qiyam_reminder_message, listOf(WorshipTiming.AFTER_ISHA, WorshipTiming.LAST_THIRD));

    private fun prefs(context: Context) = context.getSharedPreferences(channelId, Context.MODE_PRIVATE)

    fun timing(context: Context): WorshipTiming? {
        val name = prefs(context).getString("timing", null)
        return options.find { it.name == name }
    }

    @Synchronized
    fun setTiming(context: Context, choice: WorshipTiming?, now: Instant = Instant.now()): Boolean {
        require(choice == null || choice in options)
        if (choice != null && !PrefsManager.hasConfirmedPlace(context)) return false
        if (timing(context) != choice) {
            prefs(context).edit().putString("timing", choice?.name).putLong("chosen_at", now.toEpochMilli()).apply()
            ReminderAccessChanges.changed()
        }
        if (choice == null) cancel(context) else refresh(context, now)
        return true
    }

    private data class Moment(val at: Instant, val end: Instant)

    private fun moment(context: Context, place: PrayerPlace, date: LocalDate, choice: WorshipTiming): Moment? {
        val day = PrayerTimesRepository.prayerDay(context, date, place)
        return when (this) {
            DUHA -> {
                val window = duhaWindow(date, place.zone, day, PrayerTimesRepository.solarNoon(context, date, place)) ?: return null
                choice.at(window)?.let { Moment(it, window.end) }
            }
            QIYAM -> {
                val night = nightWindow(date, day, PrayerTimesRepository.prayerDay(context, date.plusDays(1), place)) ?: return null
                choice.at(night.prayerWindow, night.lastThirdPrayerStart)?.let { Moment(it, night.fajr) }
            }
        }
    }

    @Synchronized
    fun refresh(context: Context, now: Instant = Instant.now()) {
        val choice = timing(context) ?: return
        if (!PrefsManager.canUsePlaceForAutomation(context)) {
            cancel(context)
            return
        }
        val place = PrayerTimesRepository.place(context)
        val today = now.atZone(place.zone).toLocalDate()
        val settings = prefs(context)
        val chosenAt = Instant.ofEpochMilli(settings.getLong("chosen_at", 0))
        // After midnight the current night still belongs to yesterday's sunset, even if Isha also crossed midnight.
        val firstOffset = if (this == QIYAM) -1L else 0L
        for (offset in firstOffset..1L) {
            val date = today.plusDays(offset)
            if (settings.getLong("posted_day", Long.MIN_VALUE) == date.toEpochDay()) continue
            val moment = try {
                moment(context, place, date, choice)
            } catch (error: Exception) {
                Log.w("WorshipReminder", "Could not calculate $name for $date", error)
                null
            } ?: continue
            // A newly chosen reminder starts at its next occurrence, including when chosen after midnight.
            if (now >= moment.end || (chosenAt <= now && moment.at < chosenAt)) continue
            if (now < moment.at) {
                arm(context, moment.at)
                return
            }
            val held = SilenceStatus.isAppControlledSilenceActive(context)
            if (!held && post(context, moment.end, now)) {
                settings.edit().putLong("posted_day", date.toEpochDay()).apply()
            } else {
                val retry = now.plus(Duration.ofMinutes(15))
                val release = if (held) SilenceStatus.appSilenceEndsAt(context, now.toEpochMilli()) else null
                val next = release?.let(Instant::ofEpochMilli)?.takeIf { it > now }?.let { minOf(it, retry) } ?: retry
                if (next < moment.end) {
                    arm(context, next)
                    return
                }
            }
        }
        // Check again even when the upcoming local days have no usable prayer window.
        arm(context, today.plusDays(1).atStartOfDay(place.zone).toInstant())
    }

    private fun post(context: Context, windowEnd: Instant, now: Instant): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelName = context.getString(title)
        manager.createNotificationChannel(NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_WORSHIP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val end = clock.format(windowEnd.atZone(ZoneId.systemDefault()))
        val reminder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(icon)
            .setContentTitle(channelName)
            .setContentText(context.getString(message, end))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(Duration.between(now, windowEnd).toMillis().coerceAtLeast(1))
            .build()
        return manager.postReminderIfAllowed(context, id, reminder)
    }

    private fun alarm(context: Context, code: Int) = PendingIntent.getBroadcast(context, code,
        Intent(context, WorshipReminderReceiver::class.java).setAction(channelId), PendingIntent.FLAG_IMMUTABLE)

    private fun arm(context: Context, at: Instant) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val millis = at.toEpochMilli() + if (at.nano % 1_000_000 == 0) 0 else 1
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, alarm(context, 0))
        if (DhikrReminderScheduler.exactAlarmsEnabled(context)) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, alarm(context, 1))
            } catch (_: SecurityException) {
                // Android can revoke exact access between the check and registration. The backup is already armed.
            }
        }
    }

    private fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(alarm(context, 0))
        manager.cancel(alarm(context, 1))
        context.getSystemService(NotificationManager::class.java).cancel(id)
    }

    companion object {
        private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

        fun refreshAll(context: Context) {
            entries.forEach { reminder ->
                runCatching { reminder.refresh(context) }
                    .onFailure { Log.w("WorshipReminder", "Could not refresh ${reminder.name}", it) }
            }
        }
    }
}

class WorshipReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminder = WorshipReminder.entries.find { it.channelId == intent.action } ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                reminder.refresh(context.applicationContext)
            } catch (error: Exception) {
                Log.w("WorshipReminder", "Could not deliver ${reminder.name}", error)
            } finally {
                pending.finish()
            }
        }
    }
}
