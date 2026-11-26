package com.tunisianprayertimes.ui

import android.Manifest
import android.app.Application
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tunisianprayertimes.NotificationAccess
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationAccessActionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun anInterruptedRequestCanBeRetriedWithoutOpeningSettings() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        val activity = controller.setup().get()
        shadowOf(activity.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        activity.setContent {
            val request = rememberNotificationAccessAction()
            TextButton(onClick = { request(null) }) { Text("Allow") }
        }
        val android = shadowOf(activity)
        compose.onNodeWithText("Allow").performClick()
        val first = android.lastRequestedPermission
        assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), first.requestedPermissions)
        android.nextStartedActivity
        compose.runOnIdle { activity.onRequestPermissionsResult(first.requestCode, emptyArray(), intArrayOf()) }
        assertTrue(NotificationAccess.canRequest(activity))
        assertNull(android.nextStartedActivity)
        compose.onNodeWithText("Allow").performClick()
        assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), android.lastRequestedPermission.requestedPermissions)
        controller.pause().stop().destroy()
    }
}
