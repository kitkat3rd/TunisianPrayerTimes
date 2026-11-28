package com.tunisianprayertimes.access

import android.app.Application
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.tunisianprayertimes.R
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeatureAccessNoticeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun sharedAccessHasOneRepairActionAndOnlyOpensSettingsOnExplicitTap() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        val activity = controller.setup().get()
        val state = mutableStateOf<List<FeatureAccessIssue>>(emptyList())
        activity.setContent { FeatureAccessNotice(state.value) }

        val banner = activity.getString(R.string.feature_access_review)
        val sheet = activity.getString(R.string.feature_access_title)
        val action = activity.getString(R.string.wake_access_exact_action)
        compose.onNodeWithText(banner).assertDoesNotExist()
        compose.runOnIdle {
            state.value = listOf(
                FeatureAccessIssue(R.string.mosque_rule_combined, R.string.mosque_needs_exact, AccessAction.EXACT_ALARMS),
                FeatureAccessIssue(R.string.wake_alarm_channel_name, R.string.wake_access_health_exact, AccessAction.EXACT_ALARMS),
            )
        }
        compose.onNodeWithText(sheet).assertDoesNotExist()
        compose.onNodeWithText(banner).performClick()
        compose.onNodeWithText(activity.getString(R.string.mosque_rule_combined)).assertExists()
        compose.onNodeWithText(activity.getString(R.string.wake_alarm_channel_name)).assertExists()
        compose.onAllNodesWithText(action).assertCountEquals(1)
        assertNull(shadowOf(activity).nextStartedActivity)
        compose.onNodeWithText(action).performClick()
        val settings = shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, settings.action)
        assertEquals("package:${activity.packageName}", settings.dataString)
        compose.runOnIdle { state.value = emptyList() }
        compose.onNodeWithText(sheet).assertDoesNotExist()
        compose.onNodeWithText(banner).assertDoesNotExist()
    }
}
