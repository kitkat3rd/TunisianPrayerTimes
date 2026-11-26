package com.tunisianprayertimes

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NotificationAccessTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    @Before fun allowNotifications() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
    }

    @Test fun highImportanceIsRequiredOnlyWhenTheFeaturePromisesAnInterruptingAlert() {
        val channel = "ringing"
        manager.createNotificationChannel(NotificationChannel(channel, "Alarm", NotificationManager.IMPORTANCE_DEFAULT))
        assertNull(NotificationAccess.issue(app, channel))
        assertEquals(NotificationAccess.Issue.LOW_IMPORTANCE, NotificationAccess.issue(app, channel, NotificationManager.IMPORTANCE_HIGH))
        val settings = NotificationAccess.settingsIntent(app, channel)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, settings.action)
        assertEquals(app.packageName, settings.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(channel, settings.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test fun aBlockedGroupUsesAppNotificationSettingsEvenWhenItsOwnChannelIsHigh() {
        val group = NotificationChannelGroup("reminders", "Reminders")
        // Only Android's notification settings can set this flag on a device.
        ReflectionHelpers.callInstanceMethod<Unit>(group, "setBlocked", ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        manager.createNotificationChannelGroup(group)
        manager.createNotificationChannel(NotificationChannel("wake", "Wake", NotificationManager.IMPORTANCE_HIGH).also { it.group = group.id })
        assertEquals(NotificationAccess.Issue.GROUP_BLOCKED, NotificationAccess.issue(app, "wake"))
        val settings = NotificationAccess.settingsIntent(app, "wake")
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settings.action)
        assertNull(settings.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test fun aMissingChannelCanBeCreatedOnFirstDeliveryAndAUserBlockedOneHasItsOwnRemedy() {
        assertNull(NotificationAccess.issue(app, "first_delivery"))
        manager.createNotificationChannel(NotificationChannel("blocked", "Blocked", NotificationManager.IMPORTANCE_NONE))
        assertEquals(NotificationAccess.Issue.CHANNEL_BLOCKED, NotificationAccess.issue(app, "blocked"))
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, NotificationAccess.settingsIntent(app, "blocked").action)
        shadowOf(manager).setNotificationsEnabled(false)
        assertEquals(NotificationAccess.Issue.APP_BLOCKED, NotificationAccess.issue(app, "blocked"))
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, NotificationAccess.settingsIntent(app, "blocked").action)
    }

    @Test fun anUnpromptableDenialAppliesAcrossFeaturesAndActivities() {
        val first = Robolectric.buildActivity(Activity::class.java).setup().get()
        val next = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(NotificationAccess.canRequest(first))
        PrefsManager.recordNotificationResponse(first, granted = false, hadRationale = false)
        assertTrue(NotificationAccess.canRequest(next))
        assertTrue(NotificationAccess.offerRequestOrSettings(next))
        PrefsManager.recordNotificationResponse(first, granted = false, hadRationale = true)
        assertFalse(NotificationAccess.canRequest(next))
        assertEquals(NotificationAccess.Issue.APP_BLOCKED, NotificationAccess.issue(app, null))
    }
}
