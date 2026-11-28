package com.tunisianprayertimes.access

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.R
import com.tunisianprayertimes.WakeMainAlarmConfig
import com.tunisianprayertimes.WakeMainAlarmMode
import com.tunisianprayertimes.WakePlaybackOptions
import com.tunisianprayertimes.wake.AwakeCheckService
import com.tunisianprayertimes.wake.PrayerWakeRepository
import com.tunisianprayertimes.wake.WakePlaybackService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WakeFeatureAccessTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = PrayerWakeRepository(app)

    private fun timer(at: Long, enabled: Boolean = true) = PrayerWakeConfig(
        id = "timer", prayer = Prayer.FAJR, enabled = enabled,
        mainAlarm = WakeMainAlarmConfig(mode = WakeMainAlarmMode.FROM_NOW, oneOffTriggerAtMillis = at),
        playback = WakePlaybackOptions(awakeCheckEnabled = false),
    )

    @Test fun disabledAndExpiredAlarmsDoNotRequestAccess() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        repo.replaceWakeConfigs(listOf(timer(1), timer(Long.MAX_VALUE, enabled = false).copy(id = "disabled")))
        assertTrue(WakeFeatureAccess.issues(app).isEmpty())
    }

    @Test fun timerSilenceRequiresDndOnlyWhenChosenAndTheMainRingIsStillAhead() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationPolicyAccessGranted(false)
        val saved = timer(System.currentTimeMillis() + 600_000)
        repo.replaceWakeConfigs(listOf(saved))
        val ordinary = WakeFeatureAccess.issues(app)
        assertTrue(ordinary.any { it.action == AccessAction.EXACT_ALARMS })
        assertTrue(ordinary.none { it.action == AccessAction.DND })
        repo.replaceWakeConfigs(listOf(saved.copy(silenceUntilAlarm = true)))
        assertTrue(WakeFeatureAccess.issues(app).any {
            it.feature == R.string.mosque_rule_wake && it.action == AccessAction.DND
        })
    }

    @Test fun blockedAwakeCheckChannelOnlyAppliesToAlarmsWithFollowUpEnabled() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = app.getSystemService(NotificationManager::class.java)
        shadowOf(notifications).setNotificationsEnabled(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        notifications.createNotificationChannel(NotificationChannel(WakePlaybackService.CHANNEL_ID, "Wake", NotificationManager.IMPORTANCE_HIGH))
        notifications.createNotificationChannel(NotificationChannel(AwakeCheckService.CHANNEL_ID, "Confirm", NotificationManager.IMPORTANCE_NONE))
        val saved = timer(System.currentTimeMillis() + 600_000)
        repo.replaceWakeConfigs(listOf(saved))
        assertTrue(WakeFeatureAccess.issues(app).isEmpty())
        repo.replaceWakeConfigs(listOf(saved.copy(playback = saved.playback.copy(awakeCheckEnabled = true))))
        val issue = WakeFeatureAccess.issues(app).single()
        assertEquals(AccessAction.NOTIFICATIONS, issue.action)
        assertEquals(AwakeCheckService.CHANNEL_ID, issue.channelId)
    }

    @Test fun aRevokedNotificationGrantOffersOneAppWideRepairForBothAlarmChannels() = runBlocking {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val saved = timer(System.currentTimeMillis() + 600_000)
        repo.replaceWakeConfigs(listOf(saved.copy(playback = saved.playback.copy(awakeCheckEnabled = true))))
        val issue = WakeFeatureAccess.issues(app).single()
        assertEquals(AccessAction.NOTIFICATIONS, issue.action)
        assertEquals(null, issue.channelId)
    }
}
