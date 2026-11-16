package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.MathDifficulty
import com.tunisianprayertimes.WakePlaybackOptions
import com.tunisianprayertimes.WakeUpCheckStep
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WakeChallengeChoicesLayoutTest {
    @get:Rule val compose = createComposeRule()
    private var playback by mutableStateOf(WakePlaybackOptions(wakeUpCheckSteps = listOf(WakeUpCheckStep())))

    private fun show(fontScale: Float, width: Int) {
        compose.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                TunisianPrayerTimesTheme(fontFamily = AppFontFamily) {
                    // The challenge card is inset inside the alarm's timeline on a 360dp phone.
                    Column(Modifier.width(width.dp).verticalScroll(rememberScrollState())) {
                        WakeUpCheckStepsEditor(playback) { playback = it }
                    }
                }
            }
        }
    }

    private fun assertDifficultyFits(label: String) {
        val node = compose.onNodeWithText(label, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertFalse("$label must fit vertically", layout.didOverflowHeight)
            assertFalse("$label must stay in one piece", layout.splitsWord(label))
            assertEquals("Every letter of $label must be laid out", label.length, layout.getLineEnd(layout.lineCount - 1))
            // Center-aligned Text semantics can remeasure the paragraph at a larger width than the drawn Text.
            val offset = (layout.multiParagraph.width - layout.size.width).coerceAtLeast(0f) / 2f
            for (line in 0 until layout.lineCount) {
                assertFalse(layout.isLineEllipsized(line))
                assertTrue("$label must fit at the left edge", layout.getLineLeft(line) - offset >= -1f)
                assertTrue("$label must fit at the right edge", layout.getLineRight(line) - offset <= layout.size.width + 1f)
            }
        }
    }

    private fun checkDifficulties(fontScale: Float, width: Int = 260) {
        show(fontScale, width)
        listOf("سهل", "متوسط", "صعب").forEach(::assertDifficultyFits)
        listOf("سهل" to MathDifficulty.EASY, "متوسط" to MathDifficulty.INTERMEDIATE, "صعب" to MathDifficulty.HARD).forEach { (label, difficulty) ->
            compose.onNodeWithText(label).performScrollTo().performClick()
            assertEquals(difficulty, playback.effectiveWakeUpCheckSteps.single().difficulty)
            assertDifficultyFits(label)
        }
    }

    @Test fun difficultyLabelsFitInsideTheTimelineCard() = checkDifficulties(1f)
    @Test fun difficultyLabelsGrowToFitLargeText() = checkDifficulties(2f)
    @Test fun selectedDifficultyLabelsFitTheNarrowAfterRingCard() = checkDifficulties(2f, 222)
}
