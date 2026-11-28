package com.tunisianprayertimes.mosque

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceAccessSettings
import com.tunisianprayertimes.ui.gregorianDateLabel
import com.tunisianprayertimes.wake.displayName
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MosqueInterruptionNoticeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun existingMosqueUsersCanDismissBatteryGuidanceAndStillReviewItInSetup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val eligible = mutableStateOf(false)
        val inSetup = mutableStateOf(false)
        val title = app.getString(R.string.mosque_battery_guidance_title)
        val info = app.getString(R.string.info_about_topic, title)
        val detail = app.getString(R.string.mosque_battery_guidance_body)
        val batterySettings = app.getString(R.string.mosque_interruption_battery_action)
        compose.setContent {
            if (inSetup.value) MosqueBackgroundBatteryLink()
            else if (rememberMosqueBatteryGuidance(eligible.value)) MosqueBatteryGuidanceNotice()
        }
        compose.onNodeWithText(batterySettings).assertDoesNotExist()
        compose.runOnIdle { eligible.value = true }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(batterySettings).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(detail).assertDoesNotExist()
        compose.onNodeWithContentDescription(info).performClick()
        compose.onNodeWithText(detail).assertExists()
        compose.onNodeWithText(app.getString(R.string.prayer_control_close)).performClick()
        compose.onNodeWithText(detail).assertDoesNotExist()
        assertNull(shadowOf(app).nextStartedActivity)
        compose.runOnIdle { eligible.value = false }
        compose.onNodeWithText(batterySettings).assertDoesNotExist()
        compose.runOnIdle { eligible.value = true }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(batterySettings).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(app.getString(R.string.mosque_interruption_dismiss)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(batterySettings).fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { inSetup.value = true }
        compose.onNodeWithText(detail).assertDoesNotExist()
        compose.onNodeWithText(batterySettings).performClick()
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(SilenceAccessSettings.APP_BATTERY_DETAIL, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
    }

    @Test fun aRecoveredVisitShowsItsPrayerAndPersistsUntilExplicitlyDismissed() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val day = LocalDate.of(2026, 11, 27)
        val notice = MosqueObservationInterruption("prayer-episode", Prayer.ASR, day, confirmedVisit = true)
        val title = app.getString(R.string.mosque_interruption_title)
        compose.setContent { rememberMosqueInterruption()?.let { MosqueInterruptionNotice(it) } }
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.runOnIdle {
            MosqueObservationInterruptions.observing(app, notice)
            MosqueObservationInterruptions.interrupted(app)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText("${Prayer.ASR.displayName(app)} · ${gregorianDateLabel(day)}").assertExists()
        compose.onNodeWithText(app.getString(R.string.mosque_interruption_during_visit)).assertExists()
        compose.onNodeWithText(app.getString(R.string.mosque_battery_guidance_body)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.mosque_interruption_battery_action)).performClick()
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(SilenceAccessSettings.APP_BATTERY_DETAIL, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
        assertEquals(notice, MosqueObservationInterruptions.pending(app))
        compose.onNodeWithContentDescription(app.getString(R.string.mosque_interruption_dismiss))
            .performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(title).assertDoesNotExist()
        assertNull(MosqueObservationInterruptions.pending(app))
    }
}
