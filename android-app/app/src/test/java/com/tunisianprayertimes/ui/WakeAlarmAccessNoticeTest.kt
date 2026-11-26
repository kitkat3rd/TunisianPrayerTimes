package com.tunisianprayertimes.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tunisianprayertimes.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WakeAlarmAccessNoticeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun settingsRequireAnExplicitTapAndNotificationDenialKeepsTheAlarmEditorOpen() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        val activity = controller.setup().get()
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        shadowOf(activity.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        activity.setContent { WakeAlarmAccessNotice(activity, saved = false) }
        compose.waitForIdle()
        val android = shadowOf(activity)
        assertNull(android.lastRequestedPermission)
        assertNull(android.nextStartedActivity)

        compose.onNodeWithText(activity.getString(R.string.wake_access_exact_action)).performClick()
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.nextStartedActivity.action)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        controller.pause().resume()
        compose.onNodeWithText(activity.getString(R.string.wake_access_exact_action)).assertDoesNotExist()
        compose.onNodeWithText(activity.getString(R.string.wake_access_notifications_action)).performClick()
        val request = android.lastRequestedPermission
        assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions)
        // The user's tap opens Android's permission controller. Consume that launch before delivering its result.
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", android.nextStartedActivity.action)
        assertNull(android.nextStartedActivity)
        compose.runOnIdle {
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(PackageManager.PERMISSION_DENIED))
        }
        compose.onNodeWithText(activity.getString(R.string.wake_access_notifications_action)).assertExists()
        assertNull(android.nextStartedActivity)
    }
}
