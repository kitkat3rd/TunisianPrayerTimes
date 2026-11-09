package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import com.tunisianprayertimes.quran.hifz.HifzSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h480dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HifzSettingsScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun scrollingBackFromBottomDoesNotDismissTheSettingsSheet() {
        var dismissed = false
        compose.setContent {
            AdhkarTheme {
                HifzSettingsSheet(
                    settings = HifzSettings(quietHours = true), onChange = {},
                    onDismiss = { dismissed = true }, today = 100L,
                )
            }
        }
        val sheet = compose.onNodeWithTag("hifz_settings_sheet")
        val originalTop = sheet.fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("hifz_daily_goal_more").performScrollTo()
        assertTrue("The settings must first scroll down", scrollPosition() > 0f)

        // Return to the top with momentum, then keep pulling as in one continuous scroll.
        repeat(6) {
            sheet.performTouchInput { swipeDown(durationMillis = 120) }
            compose.waitForIdle()
            assertFalse("Scrolling the settings must not dismiss the sheet", dismissed)
            assertEquals(originalTop, sheet.fetchSemanticsNode().boundsInRoot.top, 1f)
        }
        assertEquals("The gestures must actually return the content to the top", 0f, scrollPosition(), 1f)
        sheet.assertIsDisplayed()
    }

    @Test
    fun draggingTheHandleStillDismissesTheSettingsSheet() {
        var dismissed = false
        compose.setContent {
            AdhkarTheme {
                HifzSettingsSheet(
                    settings = HifzSettings(), onChange = {},
                    onDismiss = { dismissed = true }, today = 100L,
                )
            }
        }
        val contentTop = compose.onNodeWithTag("hifz_settings_sheet").fetchSemanticsNode().boundsInRoot.top
        val dialog = compose.onNode(isDialog())
        val dialogTop = dialog.fetchSemanticsNode().boundsInRoot.top
        dialog.performTouchInput {
            swipe(Offset(centerX, contentTop - dialogTop - 24f), Offset(centerX, bottom - 1f), 120)
        }
        compose.waitForIdle()
        assertTrue("A deliberate drag from the handle must still dismiss the sheet", dismissed)
    }

    private fun scrollPosition(): Float = compose.onNodeWithTag("hifz_settings_sheet")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
}
