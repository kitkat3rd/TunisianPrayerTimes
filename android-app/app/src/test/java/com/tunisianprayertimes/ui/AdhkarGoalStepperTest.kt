package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
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
class AdhkarGoalStepperTest {
    @get:Rule val compose = createComposeRule()
    private var value by mutableStateOf("20")
    private val plus get() = compose.onNodeWithContentDescription("زيادة العدد")
    private val minus get() = compose.onNodeWithContentDescription("تقليص العدد")

    private fun show(initial: String = "20", minimum: Int = 11) {
        value = initial
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AdhkarTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        GoalStepper(value, { value = it }, minimum, "زيادة العدد", "تقليص العدد",
                            "goal", OutlinedTextFieldDefaults.colors())
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun hold(button: SemanticsNodeInteraction) {
        var timeout = 0L
        button.performTouchInput {
            timeout = viewConfiguration.longPressTimeoutMillis
            down(center)
        }
        compose.mainClock.advanceTimeBy(timeout + 550)
    }

    @Test fun tappingOrUsingAccessibilityChangesTheGoalOnce() {
        show()
        plus.performTouchInput { click() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("21", value)
        minus.performClick()
        assertEquals("20", value)
    }

    @Test fun holdingPlusKeepsIncreasingAndReleaseStopsWithoutAnExtraStep() {
        show()
        hold(plus)
        val held = value.toInt()
        assertTrue("A hold must step repeatedly using the latest value", held >= 24)
        plus.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(held.toString(), value)
        plus.performClick()
        assertEquals((held + 1).toString(), value)
    }

    @Test fun holdingMinusStopsAtTheMinimum() {
        show(initial = "13")
        hold(minus)
        assertEquals("11", value)
        minus.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals("11", value)
    }

    @Test fun holdingPlusStopsAtTheMaximum() {
        show(initial = "99998")
        hold(plus)
        assertEquals("100000", value)
        plus.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        assertEquals("100000", value)
    }

    @Test fun cancellingAHoldStopsRepeatingAndTheNextTapStillWorks() {
        show()
        hold(plus)
        plus.performTouchInput { cancel() }
        compose.mainClock.advanceTimeByFrame()
        val cancelled = value.toInt()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(cancelled.toString(), value)
        plus.performTouchInput { click() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals((cancelled + 1).toString(), value)
    }
}
