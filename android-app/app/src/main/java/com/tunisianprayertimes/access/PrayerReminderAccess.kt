package com.tunisianprayertimes.access

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.tunisianprayertimes.NotificationAccess
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhan.AdhanPlaybackService
import com.tunisianprayertimes.adhan.AdhanSettings
import com.tunisianprayertimes.adhkar.DhikrReminderScheduler
import com.tunisianprayertimes.adhkar.DhikrRepository
import com.tunisianprayertimes.adhkar.enabledReminderVibrations
import com.tunisianprayertimes.fasting.FastingReminder
import com.tunisianprayertimes.quran.hifz.HifzReminder
import com.tunisianprayertimes.remembrance.DeathReminder
import com.tunisianprayertimes.worship.WorshipReminder
import org.json.JSONException

internal object PrayerReminderAccess {
    fun issues(context: Context): List<FeatureAccessIssue> = buildList {
        val appNotificationsOff = NotificationAccess.issue(context, null) != null
        fun notifications(feature: Int, channels: List<Pair<String, Int>>) {
            if (appNotificationsOff) {
                add(FeatureAccessIssue(feature, R.string.access_reminder_app_notifications, AccessAction.NOTIFICATIONS))
                return
            }
            for ((channel, label) in channels) {
                val issue = NotificationAccess.issue(context, channel) ?: continue
                add(FeatureAccessIssue(label, R.string.access_reminder_channel_notifications,
                    AccessAction.NOTIFICATIONS, NotificationAccess.repairChannel(channel, issue)))
            }
        }

        if (AdhanSettings.enabledPrayers(context).isNotEmpty() && PrefsManager.canUsePlaceForAutomation(context)) {
            val warnsBefore = AdhanSettings.warnsBefore(context)
            val channels = buildList {
                add(AdhanPlaybackService.CHANNEL_PLAYING to R.string.adhan_channel_playing)
                add(AdhanPlaybackService.CHANNEL_QUIET to R.string.adhan_channel_quiet)
                if (warnsBefore) add(AdhanPlaybackService.CHANNEL_SOON to R.string.adhan_channel_soon)
            }
            notifications(R.string.adhan_settings_title, channels)
            // Without exact alarms playback has an inexact fallback, but the advance warning is never scheduled.
            if (!DhikrReminderScheduler.exactAlarmsEnabled(context)) {
                add(FeatureAccessIssue(R.string.adhan_settings_title, R.string.adhan_exact_alarms_off, AccessAction.EXACT_ALARMS))
                if (warnsBefore) {
                    add(FeatureAccessIssue(R.string.adhan_channel_soon, R.string.access_adhan_warning_exact, AccessAction.EXACT_ALARMS))
                }
            }
        }

        if (appNotificationsOff || NotificationAccess.issue(context, DhikrReminderScheduler.CHANNEL_ID) != null ||
            NotificationAccess.issue(context, DhikrReminderScheduler.QUIET_CHANNEL_ID) != null) {
            val channels = configuredDhikrChannels(context)
            if (channels.isNotEmpty()) notifications(R.string.adhkar_notification_permission_title, channels)
        }
        if (FastingReminder.notificationsRequested(context)) {
            notifications(R.string.access_fasting_reminder, listOf(FastingReminder.CHANNEL_ID to R.string.access_fasting_reminder))
        }
        if (PrefsManager.canUsePlaceForAutomation(context)) {
            WorshipReminder.entries.filter { it.timing(context) != null }.forEach { reminder ->
                notifications(reminder.title, listOf(reminder.channelId to reminder.title))
            }
        }
        if (DeathReminder.notificationsRequested(context)) {
            notifications(R.string.access_death_reminder, listOf(DeathReminder.CHANNEL_ID to R.string.access_death_reminder))
        }
        if (HifzReminder.hasConfiguredNotification(context)) {
            notifications(R.string.access_hifz_reminder, listOf(HifzReminder.CHANNEL_ID to R.string.access_hifz_reminder))
        }
        val usesCallFeedback = PrefsManager.isEnabled(context) || PrefsManager.isManualSilenceActive(context) ||
            PrefsManager.hasChosenCallEndVibration(context)
        if (usesCallFeedback &&
            PrefsManager.isCallEndVibrationEnabled(context) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            add(FeatureAccessIssue(R.string.call_end_vibration_title, R.string.call_feedback_uses_phone_state, AccessAction.APP_DETAILS))
        }
    }

    private fun configuredDhikrChannels(context: Context): List<Pair<String, Int>> {
        val state = try {
            DhikrRepository(context).state.value
        } catch (_: JSONException) {
            return emptyList()
        } catch (_: ClassCastException) {
            return emptyList()
        }
        return state.enabledReminderVibrations().map { vibrate ->
            DhikrReminderScheduler.channelId(vibrate) to
                if (vibrate) R.string.access_adhkar_vibrating else R.string.access_adhkar_quiet
        }
    }
}
