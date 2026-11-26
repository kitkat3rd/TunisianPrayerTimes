package com.tunisianprayertimes.remembrance

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.MainTabNavigation
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.fasting.FastingReminder
import com.tunisianprayertimes.ui.FajrAlarmPersistence
import com.tunisianprayertimes.wake.AwakeCheckService
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReminderNotificationDeliveryTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    private val fastingPrefs get() = app.getSharedPreferences("fasting_reminder", Context.MODE_PRIVATE)
    private val lifePrefs get() = app.getSharedPreferences("death_remembrance", Context.MODE_PRIVATE)
    private val fastDay = LocalDate.of(2026, 12, 3)

    @Before fun setup() {
        PrefsManager.markFirstLaunchDone(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(true)
        fastingPrefs.edit().putBoolean("enabled", true).commit()
        lifePrefs.edit().putBoolean("enabled", true).putString("frequency", "daily").commit()
    }

    @Test fun fastingPermissionGrantedDuringTheEveDeliversOnce() {
        val due = requireNotNull(FastingReminder.eve(app, fastDay).remindAt)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        FastingReminder.refresh(app, due)
        assertTrue(notifications.activeNotifications.isEmpty())
        assertFalse(fastingPrefs.contains("posted_fast_day"))

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        FastingReminder.refresh(app, due.plusSeconds(60), fromApp = true)
        val posted = notifications.activeNotifications.single()
        assertEquals(FastingReminder.CHANNEL_ID, posted.notification.channelId)
        assertEquals(fastDay.toEpochDay(), fastingPrefs.getLong("posted_fast_day", -1))
        notifications.cancel(posted.id)
        FastingReminder.refresh(app, due.plusSeconds(120))
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test fun restoringAccessAfterFajrDoesNotReplayFastingReminder() {
        val eve = FastingReminder.eve(app, fastDay)
        shadowOf(notifications).setNotificationsEnabled(false)
        FastingReminder.refresh(app, requireNotNull(eve.remindAt))
        assertFalse(fastingPrefs.contains("posted_fast_day"))
        shadowOf(notifications).setNotificationsEnabled(true)
        FastingReminder.refresh(app, requireNotNull(eve.fajr).plusSeconds(1))
        assertTrue(notifications.activeNotifications.isEmpty())
        assertFalse(fastingPrefs.contains("posted_fast_day"))
    }

    @Test fun lifeReminderChannelRestoredDuringDayDeliversOnceWithoutAnOverdueAlarm() {
        val moment = requireNotNull(DeathReminder.next(app, Instant.parse("2026-12-01T05:00:00Z")))
        notifications.createNotificationChannel(NotificationChannel(
            DeathReminder.CHANNEL_ID, "Blocked", NotificationManager.IMPORTANCE_NONE,
        ))
        DeathReminder.refresh(app, moment.at)
        assertFalse(lifePrefs.contains("posted_day"))
        assertTrue(notifications.activeNotifications.isEmpty())
        val planned = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .filter { shadowOf(it.operation).savedIntent.data?.scheme == "remembrance" }
        assertTrue(planned.all { it.triggerAtTime > moment.at.toEpochMilli() })

        // Robolectric exposes the channel stored by the OS, so this models the user's change in Settings.
        notifications.getNotificationChannel(DeathReminder.CHANNEL_ID).importance = NotificationManager.IMPORTANCE_LOW
        DeathReminder.refresh(app, moment.at.plusSeconds(1))
        val posted = notifications.activeNotifications.single()
        assertEquals(DeathReminder.CHANNEL_ID, posted.notification.channelId)
        assertEquals(moment.day.toEpochDay(), lifePrefs.getLong("posted_day", -1))
        notifications.cancel(posted.id)
        DeathReminder.refresh(app, moment.at.plusSeconds(2))
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test fun restoringAppNotificationsAfterDaylightDoesNotReplayLifeReminder() {
        val moment = requireNotNull(DeathReminder.next(app, Instant.parse("2026-12-01T05:00:00Z")))
        shadowOf(notifications).setNotificationsEnabled(false)
        DeathReminder.refresh(app, moment.at)
        assertFalse(lifePrefs.contains("posted_day"))
        shadowOf(notifications).setNotificationsEnabled(true)
        DeathReminder.refresh(app, moment.windowEnd)
        assertTrue(notifications.activeNotifications.isEmpty())
        assertFalse(lifePrefs.contains("posted_day"))
    }

    @Test fun suhoorQuickActionChecksTheChannelItsSelectedPersistenceUses() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        notifications.createNotificationChannel(NotificationChannel(
            AwakeCheckService.CHANNEL_ID, "Blocked check", NotificationManager.IMPORTANCE_NONE,
        ))
        val eve = FastingReminder.eve(app, fastDay)
        val due = requireNotNull(eve.remindAt)
        fun suhoorTap(persistence: FajrAlarmPersistence) = run {
            FastingReminder.saveSuhoorChoice(app, 30, persistence)
            FastingReminder.post(app, eve, due)
            val actions = notifications.activeNotifications.single().notification.actions
            shadowOf(actions.single { it.title == "أيقظني للسحور" }.actionIntent)
        }
        val needsSetup = suhoorTap(FajrAlarmPersistence.BALANCED)
        assertTrue(needsSetup.isActivity)
        assertEquals(MainTabNavigation.WORSHIP_ACTION_SUHOOR,
            needsSetup.savedIntent.getStringExtra(MainTabNavigation.EXTRA_WORSHIP_ACTION))
        assertTrue(suhoorTap(FajrAlarmPersistence.GENTLE).isBroadcast)
    }
}
