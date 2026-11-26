package com.tunisianprayertimes.adhan

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.ui.AppFontFamily
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import java.time.LocalDate
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
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdhanPlaybackControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theArabicScreenRaisesVolumeToTheRightAndKeepsStopBelowTheSlider() {
        compose.mainClock.autoAdvance = false
        val volume = mutableFloatStateOf(1f)
        var stopped = false
        val alert = AdhanAlert(
            AdhanScheduler.Occurrence(Prayer.FAJR, LocalDate.now(), System.currentTimeMillis()),
            sounding = true, voice = AdhanVoice.FAJR_MARWAN_QASSAS, startedAtUptimeMillis = SystemClock.uptimeMillis() - 12_000,
            onVolumeChange = { volume.floatValue = it },
        )
        compose.setContent {
            TunisianPrayerTimesTheme(fontFamily = AppFontFamily) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AdhanAlertScreen(alert.copy(volume = volume.floatValue)) { stopped = true }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        val slider = compose.onNodeWithContentDescription("مستوى صوت الأذان").assertIsDisplayed()
        val stop = compose.onNodeWithText("إيقاف").assertIsDisplayed()
        assertTrue(stop.fetchSemanticsNode().boundsInRoot.top >= slider.fetchSemanticsNode().boundsInRoot.bottom)
        slider.performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertTrue("Volume after tapping the middle: ${volume.floatValue}", volume.floatValue in 0.4f..0.6f)
        slider.performTouchInput { swipe(center, center.copy(x = width * 0.75f)) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertTrue("Volume after dragging right: ${volume.floatValue}", volume.floatValue in 0.6f..0.9f)
        slider.performTouchInput { swipe(center.copy(x = width * 0.75f), centerLeft) }
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        assertEquals(0f, volume.floatValue, 0f)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        assertEquals(1f, volume.floatValue, 0f)
        assertFalse(stopped)
        stop.performClick()
        assertTrue(stopped)
    }
}
