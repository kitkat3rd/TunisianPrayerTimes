package com.tunisianprayertimes.access

import android.content.Context
import com.tunisianprayertimes.NotificationAccess
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.R
import com.tunisianprayertimes.WakeMainAlarmMode
import com.tunisianprayertimes.wake.AwakeCheckService
import com.tunisianprayertimes.wake.PrayerWakeRepository
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import com.tunisianprayertimes.wake.WakePlaybackService
import com.tunisianprayertimes.wake.hasFutureWakeTriggers
import com.tunisianprayertimes.wake.wakeAlarmAccess

internal object WakeFeatureAccess {
    suspend fun issues(context: Context, storedWakeAlarms: List<PrayerWakeConfig>? = null): List<FeatureAccessIssue> {
        val alarms = storedWakeAlarms ?: PrayerWakeRepository(context).getCurrentStore().alarms
        if (alarms.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val placeReady = PrefsManager.canUsePlaceForAutomation(context)
        val eligible = alarms.filter {
            it.hasFutureWakeTriggers(now) && (placeReady || it.mainAlarm.mode != WakeMainAlarmMode.PRAYER_RELATIVE)
        }
        if (eligible.isEmpty()) return emptyList()
        val access = wakeAlarmAccess(context, awakeCheckEnabled = eligible.any { it.playback.awakeCheckEnabled })
        return buildList {
            fun ringingIssue(message: Int, action: AccessAction, channel: String? = null) {
                add(FeatureAccessIssue(R.string.wake_alarm_channel_name, message, action, channel))
            }
            if (!access.exact) ringingIssue(R.string.wake_access_health_exact, AccessAction.EXACT_ALARMS)
            if (access.ringingIssue != null) {
                val message = if (access.ringingIssue == NotificationAccess.Issue.LOW_IMPORTANCE)
                    R.string.wake_access_health_channel_quiet else R.string.wake_access_health_notifications
                ringingIssue(message, AccessAction.NOTIFICATIONS,
                    NotificationAccess.repairChannel(WakePlaybackService.CHANNEL_ID, access.ringingIssue))
            }
            if (access.awakeCheckIssue != null && access.ringingIssue != NotificationAccess.Issue.APP_BLOCKED) {
                add(FeatureAccessIssue(R.string.awake_check_channel_name, R.string.wake_access_health_awake_check,
                    AccessAction.NOTIFICATIONS, NotificationAccess.repairChannel(AwakeCheckService.CHANNEL_ID, access.awakeCheckIssue)))
            }
            if (!access.fullScreen) ringingIssue(R.string.wake_access_health_lock_screen, AccessAction.FULL_SCREEN)
            val needsTimerSilence = eligible.any {
                it.silenceUntilAlarm && it.mainAlarm.mode == WakeMainAlarmMode.FROM_NOW && it.mainAlarm.oneOffTriggerAtMillis > now
            }
            if (needsTimerSilence) {
                when (WakeAlarmScheduler.timerSilenceIssue(context)) {
                    WakeAlarmScheduler.TimerSilenceIssue.DND -> add(FeatureAccessIssue(
                        R.string.mosque_rule_wake, R.string.wake_timer_silence_needs_dnd, AccessAction.DND))
                    WakeAlarmScheduler.TimerSilenceIssue.RULE -> add(FeatureAccessIssue(
                        R.string.mosque_rule_wake, R.string.wake_timer_silence_needs_rule, AccessAction.WAKE_RULE))
                    null -> Unit
                }
            }
        }
    }
}
