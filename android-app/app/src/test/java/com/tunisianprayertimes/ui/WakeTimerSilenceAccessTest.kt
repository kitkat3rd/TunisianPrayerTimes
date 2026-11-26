package com.tunisianprayertimes.ui

import android.app.Application
import android.app.NotificationManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceAccessSettings
import com.tunisianprayertimes.mosque.MosqueSettingsActivity
import com.tunisianprayertimes.mosque.MosqueSilenceSettings
import com.tunisianprayertimes.wake.WakeAlarmScheduler
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WakeTimerSilenceAccessTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun silenceAccessIsRequestedOnTapAndReturningDoesNotStartIt() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        val activity = controller.setup().get()
        val notifications = shadowOf(activity.getSystemService(NotificationManager::class.java))
        notifications.setNotificationPolicyAccessGranted(false)
        activity.setContent { WakeTimerSilenceNotice() }
        compose.onNodeWithText(activity.getString(R.string.wake_timer_silence_needs_dnd)).assertExists()
        val android = shadowOf(activity)
        assertNull(android.nextStartedActivity)
        compose.onNodeWithText(activity.getString(R.string.mosque_allow_dnd)).performClick()
        val grantPage = android.nextStartedActivity
        assertEquals(SilenceAccessSettings.DND_ACCESS_DETAIL, grantPage.action)
        assertEquals("package:${activity.packageName}", grantPage.dataString)
        notifications.setNotificationPolicyAccessGranted(true)
        controller.pause().resume()
        compose.onNodeWithText(activity.getString(R.string.mosque_allow_dnd)).assertDoesNotExist()
        assertFalse(WakeAlarmScheduler.isSilenceUntilAlarmActive(activity))

        MosqueSilenceSettings.enroll(activity)
        controller.pause().resume()
        compose.onNodeWithText(activity.getString(R.string.mosque_prepare_rules)).performClick()
        assertEquals(MosqueSettingsActivity::class.java.name, android.nextStartedActivity.component?.className)
        assertFalse(WakeAlarmScheduler.isSilenceUntilAlarmActive(activity))
    }
}
