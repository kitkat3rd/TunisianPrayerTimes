package com.tunisianprayertimes.access

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.MainActivity
import com.tunisianprayertimes.MainTabNavigation
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.RamadanOverrideChecker
import com.tunisianprayertimes.platform.SharedAndroid
import com.tunisianprayertimes.ui.TestTags
import java.time.LocalDate
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
class MainEntryAccessTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun alarmsTabOpensWithCallFeedbackWarningAndSameTabClearsItAfterSettings() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        SharedAndroid.init(app)
        PrefsManager.markFirstLaunchDone(app)
        PrefsManager.setEnabled(app, false)
        PrefsManager.setAutoLocationUpdateEnabled(app, false)
        PrefsManager.setCallEndVibrationEnabled(app, true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        val clock = RamadanOverrideChecker.todayProvider
        // A normal day without any announcement polling, independent of the host date.
        RamadanOverrideChecker.todayProvider = { LocalDate.of(2026, 11, 27) }
        val launch = Intent(app, MainActivity::class.java)
            .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_ALARMS)
        val controller = Robolectric.buildActivity(MainActivity::class.java, launch)
        try {
            val activity = controller.setup().get()
            val warning = activity.getString(R.string.feature_access_review_one, activity.getString(R.string.call_end_vibration_title))
            compose.waitUntil(15_000) { compose.onAllNodesWithText(warning).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(TestTags.MAIN_TAB_ALARMS).assertIsSelected()
            compose.runOnIdle {
                controller.pause()
                shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
                controller.resume()
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithText(warning).fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText(warning).assertDoesNotExist()
            compose.onNodeWithTag(TestTags.MAIN_TAB_ALARMS).assertIsSelected()
        } finally {
            controller.pause().stop().destroy()
            RamadanOverrideChecker.todayProvider = clock
        }
    }
}
