package com.tunisianprayertimes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.tunisianprayertimes.wake.PrayerWakeRepository
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import com.tunisianprayertimes.wake.displayName
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class WakeShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.action
        if (savedInstanceState != null || (action != WakeShortcutActions.BEFORE_ADHAN && action != WakeShortcutActions.NAP)) {
            finish()
            return
        }
        if (action == WakeShortcutActions.BEFORE_ADHAN && !PrefsManager.hasConfirmedPlace(this)) {
            Toast.makeText(this, R.string.prayer_feature_place_required, Toast.LENGTH_LONG).show()
            MainActivityPendingIntents.choosePlace(this).send()
            finish()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !WakeAlarmScheduler.canScheduleExactAlarms(this)) {
            Toast.makeText(this, R.string.shortcut_wake_permission, Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
            finish()
            return
        }
        val tappedAt = Instant.now()
        lifecycleScope.launch {
            try {
                val message = withContext(NonCancellable + ScheduleRefreshCoordinator.scheduling) {
                    WakeShortcutActions.perform(applicationContext, action, tappedAt)
                }
                Toast.makeText(this@WakeShortcutActivity, message, Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Log.w("WakeShortcut", "Could not set the wake alarm", error)
                Toast.makeText(this@WakeShortcutActivity, R.string.shortcut_wake_failed, Toast.LENGTH_LONG).show()
            } finally {
                finish()
            }
        }
    }
}

internal object WakeShortcutActions {
    const val BEFORE_ADHAN = "com.tunisianprayertimes.shortcut.WAKE_BEFORE_ADHAN"
    const val NAP = "com.tunisianprayertimes.shortcut.WAKE_NAP"
    private const val ADHAN_LEAD_MINUTES = 10
    private const val NAP_MINUTES = 20
    private val saveLock = Mutex()

    suspend fun perform(context: Context, action: String, tappedAt: Instant): String {
        val config = when (action) {
            BEFORE_ADHAN -> {
                if (!PrefsManager.hasConfirmedPlace(context)) return context.getString(R.string.prayer_feature_place_required)
                val next = PrayerTimesRepository.upcomingPrayers(context, tappedAt).firstOrNull()
                    ?: return context.getString(R.string.shortcut_wake_missing_prayer)
                val prayerName = next.prayer.displayName(context)
                val trigger = next.at.minusSeconds(ADHAN_LEAD_MINUTES * 60L)
                if (trigger <= tappedAt) return context.getString(R.string.shortcut_wake_too_late, prayerName)
                oneOff(
                    id = "shortcut-adhan-${next.prayer.name}-${next.prayerDay}",
                    title = context.getString(R.string.shortcut_before_prayer_title, prayerName),
                    // Wake alarms use the Dhuhr slot for the resolved Friday prayer.
                    prayer = if (next.prayer == Prayer.JOMOAA) Prayer.DHUHR else next.prayer,
                    main = WakeMainAlarmConfig(
                        mode = WakeMainAlarmMode.PRAYER_RELATIVE,
                        prayerOffset = PrayerRelativeOffset(-ADHAN_LEAD_MINUTES),
                        oneOffTriggerAtMillis = trigger.toEpochMilli(),
                    ),
                )
            }
            NAP -> oneOff(
                id = "shortcut-nap",
                title = context.getString(R.string.shortcut_nap),
                prayer = Prayer.FAJR, // From-now alarms use the same default slot as the in-app timer.
                main = WakeMainAlarmConfig(
                    mode = WakeMainAlarmMode.FROM_NOW,
                    oneOffOffsetMinutes = NAP_MINUTES,
                    oneOffTriggerAtMillis = tappedAt.plusSeconds(NAP_MINUTES * 60L).toEpochMilli(),
                ),
            )
            else -> error("Unknown wake shortcut")
        }
        val triggerAt = config.mainAlarm.oneOffTriggerAtMillis
        val alarm = saveLock.withLock {
            val repository = PrayerWakeRepository(context)
            val prefix = "${config.id}_"
            // Reuse a pending shortcut alarm on repeat taps. An alarm edited into a different schedule has its own life.
            val pending = repository.getCurrentStore().alarms.firstOrNull { alarm ->
                alarm.id.startsWith(prefix) && alarm.enabled && alarm.repeatMode == WakeRepeatMode.ONCE &&
                    alarm.prayer == config.prayer && alarm.mainAlarm.mode == config.mainAlarm.mode &&
                    alarm.mainAlarm.oneOffTriggerAtMillis > tappedAt.toEpochMilli() &&
                    when (action) {
                        NAP -> alarm.mainAlarm.oneOffOffsetMinutes == NAP_MINUTES
                        else -> alarm.mainAlarm.prayerOffset == config.mainAlarm.prayerOffset &&
                            alarm.mainAlarm.oneOffTriggerAtMillis == triggerAt
                    }
            }
            val alarm = pending?.copy(mainAlarm = pending.mainAlarm.copy(oneOffTriggerAtMillis = triggerAt))
                ?: config.copy(id = prefix + UUID.randomUUID())
            repository.saveWakeConfig(alarm)
            ScheduleRefreshCoordinator.syncWake(context)
            if (WakeAlarmScheduler.scheduledMainTriggerAt(context, alarm.id) != triggerAt) {
                return context.getString(R.string.shortcut_wake_failed)
            }
            alarm
        }
        val time = DateUtils.formatDateTime(context, triggerAt, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY)
        return context.getString(R.string.shortcut_wake_confirmed, alarm.title, time)
    }

    private fun oneOff(id: String, title: String, prayer: Prayer, main: WakeMainAlarmConfig) = PrayerWakeConfig(
        id = id,
        title = title,
        prayer = prayer,
        enabled = true,
        mainAlarm = main,
        repeatMode = WakeRepeatMode.ONCE,
        playback = WakePlaybackOptions(awakeCheckEnabled = false, initialVibrationSeconds = 0),
    )
}
