package com.tunisianprayertimes.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-xxhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WakePrayerOffsetPickerTest {
    @get:Rule val compose = createComposeRule()

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val fajr get() = context.getString(R.string.prayer_fajr)

    @Test
    fun side_followsTheSignOfTheOffset_andGivesItBack() {
        assertEquals(WakePrayerOffsetSide.BEFORE, wakePrayerOffsetSide(-90))
        assertEquals(WakePrayerOffsetSide.AT, wakePrayerOffsetSide(0))
        assertEquals(WakePrayerOffsetSide.AFTER, wakePrayerOffsetSide(15))
        assertEquals(-90, WakePrayerOffsetSide.BEFORE.signedMinutes(90))
        assertEquals(0, WakePrayerOffsetSide.AT.signedMinutes(90))
        assertEquals(90, WakePrayerOffsetSide.AFTER.signedMinutes(90))
    }

    @Test
    fun hoursWheel_reachesTwelve_andStretchesForAnOlderLongerOffset() {
        assertEquals(12, wakePrayerOffsetMaxHours(20))
        assertEquals(12, wakePrayerOffsetMaxHours(12 * 60 + 59))
        // The minutes field it replaces took up to 999 minutes: 16 h 39.
        assertEquals(16, wakePrayerOffsetMaxHours(999))
    }

    @Test
    fun wheels_showTheOffset_inHoursAndMinutes() {
        compose.setContent { Picker(minutes = 95) }

        compose.onNodeWithTag("wake_offset_hours").assert(state("ساعة واحدة"))
        compose.onNodeWithTag("wake_offset_minutes").assert(state("35 دقيقة"))
        compose.onNodeWithTag("wake_offset_result")
            .assertTextEquals("يرن الساعة \u206603:55\u2069 • $fajr \u206605:30\u2069")
        savePreview("before-1h35")
    }

    @Test
    fun preset_setsTheOffset_andTurnsTheWheelsToIt() {
        var minutes by mutableIntStateOf(20)
        compose.setContent { Picker(minutes = minutes, onMinutesChange = { minutes = it }) }
        compose.onNodeWithTag("wake_offset_preset_20").assertIsSelected()
        savePreview("before-20")

        compose.onNodeWithTag("wake_offset_preset_60").performClick()
        compose.waitForIdle()

        assertEquals(60, minutes)
        compose.onNodeWithTag("wake_offset_preset_60").assertIsSelected()
        compose.onNodeWithTag("wake_offset_preset_20").assertIsNotSelected()
        compose.onNodeWithTag("wake_offset_hours").assert(state("ساعة واحدة"))
        compose.onNodeWithTag("wake_offset_minutes").assert(state("0 دقيقة"))
        compose.onNodeWithTag("wake_offset_result")
            .assertTextEquals("يرن الساعة \u206604:30\u2069 • $fajr \u206605:30\u2069")
    }

    @Test
    fun turningTheHoursWheel_handsOverOneValue_onceItRests() {
        var minutes by mutableIntStateOf(20)
        val changes = mutableListOf<Int>()
        compose.setContent {
            Picker(minutes = minutes, onMinutesChange = { value -> minutes = value; changes += value })
        }

        compose.onNodeWithTag("wake_offset_hours").performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.waitForIdle()

        assertEquals(listOf(140), changes)
        compose.onNodeWithTag("wake_offset_hours").assert(state("ساعتان"))
        compose.onNodeWithTag("wake_offset_minutes").assert(state("20 دقيقة"))
    }

    @Test
    fun swipingTheMinutesWheel_snapsToAValue_andHandsItOver() {
        var minutes by mutableIntStateOf(20)
        compose.setContent { Picker(minutes = minutes, onMinutesChange = { minutes = it }) }

        compose.onNodeWithTag("wake_offset_minutes").performTouchInput {
            swipeUp(startY = bottom - 4f, endY = top + 4f, durationMillis = 400)
        }
        compose.waitForIdle()

        assertTrue("A swipe up shows later minutes: $minutes", minutes > 20)
        compose.onNodeWithTag("wake_offset_minutes")
            .assert(state(context.resources.getQuantityString(R.plurals.prayer_silence_duration_minutes, minutes, minutes)))
    }

    @Test
    fun zeroBeforeThePrayer_becomesOneMinute() {
        var minutes by mutableIntStateOf(5)
        compose.setContent { Picker(minutes = minutes, onMinutesChange = { minutes = it }) }

        compose.onNodeWithTag("wake_offset_minutes").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.waitForIdle()

        assertEquals(1, minutes)
        compose.onNodeWithTag("wake_offset_minutes").assert(state("دقيقة واحدة"))
    }

    @Test
    fun atThePrayer_hidesTheWheels_andTheDurationComesBackAfter() {
        var side by mutableStateOf(WakePrayerOffsetSide.BEFORE)
        compose.setContent { Picker(side = side, minutes = 75, onSideChange = { side = it }) }

        compose.onNodeWithText(context.getString(R.string.wake_editor_offset_side_at, fajr)).performClick()
        compose.waitForIdle()
        assertEquals(WakePrayerOffsetSide.AT, side)
        compose.onNodeWithTag("wake_offset_hours").assertDoesNotExist()
        compose.onNodeWithTag("wake_offset_preset_60").assertDoesNotExist()
        savePreview("at")

        compose.onNodeWithText(context.getString(R.string.wake_editor_offset_side_after, fajr)).performClick()
        compose.waitForIdle()
        assertEquals(WakePrayerOffsetSide.AFTER, side)
        compose.onNodeWithTag("wake_offset_hours").assert(state("ساعة واحدة"))
        compose.onNodeWithTag("wake_offset_minutes").assert(state("15 دقيقة"))
        compose.onNodeWithTag("wake_offset_result")
            .assertTextEquals("يرن الساعة \u206606:45\u2069 • $fajr \u206605:30\u2069")
        savePreview("after-1h15")
    }

    @Test
    fun adjusting_coversTheTurn_andAShortPauseAfterIt() {
        var adjusting = false
        compose.setContent { Picker(minutes = 20, onAdjustingChange = { adjusting = it }) }
        compose.waitForIdle()
        assertFalse(adjusting)

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("wake_offset_minutes").performSemanticsAction(SemanticsActions.SetProgress) { it(40f) }
        compose.mainClock.advanceTimeBy(100)
        assertTrue("Turning", adjusting)

        compose.mainClock.advanceTimeBy(1_000)
        assertTrue("Just stopped: the hours may come next", adjusting)

        compose.mainClock.advanceTimeBy(3_000)
        assertFalse("Done", adjusting)
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun choosingAtThePrayerMidTurn_endsTheAdjustment() {
        var adjusting = false
        var side by mutableStateOf(WakePrayerOffsetSide.BEFORE)
        compose.setContent { Picker(side = side, onSideChange = { side = it }, onAdjustingChange = { adjusting = it }) }
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("wake_offset_minutes").performSemanticsAction(SemanticsActions.SetProgress) { it(40f) }
        compose.mainClock.advanceTimeBy(100)
        assertTrue("Turning", adjusting)

        side = WakePrayerOffsetSide.AT
        // The wheels slide away well before their pause after the turn would have ended.
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("wake_offset_minutes").assertDoesNotExist()
        assertFalse("The wheels are gone, so nothing holds a dialog back", adjusting)
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun largeTextOnANarrowScreen_keepsEveryPresetInOneRow() {
        compose.setContent { Picker(minutes = 45, fontScale = 1.3f) }

        val first = compose.onNodeWithTag("wake_offset_preset_10").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("wake_offset_preset_60").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("picker_root").fetchSemanticsNode().boundsInRoot
        assertEquals("One row", first.top, last.top, 0.5f)
        assertTrue(root.contains(first.topLeft) && root.contains(last.bottomRight))
        assertNotEquals(first.left, last.left)
        savePreview("large-text")
    }

    @Composable
    private fun Picker(
        side: WakePrayerOffsetSide = WakePrayerOffsetSide.BEFORE,
        minutes: Int = 20,
        onSideChange: (WakePrayerOffsetSide) -> Unit = {},
        onMinutesChange: (Int) -> Unit = {},
        onAdjustingChange: (Boolean) -> Unit = {},
        // The section card's inner width on a 360dp phone.
        width: Dp = 300.dp,
        fontScale: Float = 1f,
    ) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            TunisianPrayerTimesTheme(fontFamily = AppFontFamily) {
                Box(
                    Modifier
                        .testTag("picker_root")
                        .background(BgCream)
                        .padding(16.dp)
                        .width(width),
                ) {
                    WakePrayerOffsetControls(
                        prayerName = fajr,
                        side = side,
                        onSideChange = onSideChange,
                        minutes = minutes,
                        maxHours = WAKE_PRAYER_OFFSET_MAX_HOURS,
                        onMinutesChange = onMinutesChange,
                        resultPrayerName = fajr,
                        prayerTimeText = clock(FAJR_MINUTE_OF_DAY),
                        ringTimeTextFor = { signedOffsetMinutes -> clock(FAJR_MINUTE_OF_DAY + signedOffsetMinutes) },
                        onAdjustingChange = onAdjustingChange,
                    )
                }
            }
        }
    }

    private fun state(description: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)

    private fun clock(minuteOfDay: Int): String =
        Math.floorMod(minuteOfDay, 24 * 60).let { String.format(Locale.ROOT, "%02d:%02d", it / 60, it % 60) }

    private fun savePreview(name: String) {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("picker_root").fetchSemanticsNode().boundsInWindow
        val bitmap = compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().last { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
            Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                canvas.translate(-bounds.left, -bounds.top)
                view.draw(canvas)
            }
        }
        val output = File("build/outputs/wake-offset-preview/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private companion object {
        const val FAJR_MINUTE_OF_DAY = 5 * 60 + 30
    }
}
