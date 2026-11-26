package com.tunisianprayertimes.ui

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.DhikrReminderScheduler
import com.tunisianprayertimes.quran.hifz.HifzReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReminderNotificationRecoveryTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun aFastDenialLeavesTheReaderInPlaceUntilTheyChooseSettings() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        val activity = controller.setup().get()
        val app = shadowOf(activity.application)
        val android = shadowOf(activity)
        app.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        activity.setContent { HifzNotificationsWarning(listOf(HifzReminder.CHANNEL_ID)) }
        compose.onNodeWithText("السماح بالإشعارات").performClick()
        val request = android.lastRequestedPermission
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", android.nextStartedActivity.action)
        compose.runOnIdle {
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
        }
        val retry = activity.getString(R.string.notification_access_retry)
        val openSettings = activity.getString(R.string.notification_access_open_settings)
        compose.onNodeWithText(retry).assertDoesNotExist()
        compose.onNodeWithText("السماح بالإشعارات").assertExists()
        assertNull(android.nextStartedActivity)
        compose.onNodeWithText("السماح بالإشعارات").performClick()
        compose.onNodeWithText(retry).assertExists()
        compose.onNodeWithText(openSettings).assertExists()
        assertNull(android.nextStartedActivity)
        compose.onNodeWithText(openSettings).performClick()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, android.nextStartedActivity.action)
        app.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        controller.pause().resume()
        compose.onNodeWithText("السماح بالإشعارات").assertDoesNotExist()
    }

    @Test fun aBlockedReviewChannelOpensItsOwnSettingsWithoutARuntimePrompt() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        val activity = controller.setup().get()
        shadowOf(activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = activity.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        manager.createNotificationChannel(NotificationChannel(HifzReminder.CHANNEL_ID, "المراجعة", NotificationManager.IMPORTANCE_NONE))
        activity.setContent { HifzNotificationsWarning(listOf(HifzReminder.CHANNEL_ID)) }
        compose.onNodeWithText("إعدادات الإشعارات").performClick()
        val android = shadowOf(activity)
        assertNull(android.lastRequestedPermission)
        val intent = android.nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(HifzReminder.CHANNEL_ID, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test fun schedulersHonorTheAndroidGroupEvenIfBothReminderChannelsAreEnabled() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        val group = NotificationChannelGroup("reminder_group", "Reminders")
        ReflectionHelpers.callInstanceMethod<Unit>(group, "setBlocked", ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        manager.createNotificationChannelGroup(group)
        listOf(HifzReminder.CHANNEL_ID, DhikrReminderScheduler.CHANNEL_ID).forEach { id ->
            manager.createNotificationChannel(NotificationChannel(id, id, NotificationManager.IMPORTANCE_HIGH).also { it.group = group.id })
        }
        assertFalse(DhikrReminderScheduler.notificationsEnabled(app))
        assertEquals(HifzReminder.CHANNEL_ID, HifzReminder.blockedChannel(app, listOf(HifzReminder.CHANNEL_ID)))
    }
}
