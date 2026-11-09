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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.DisplayTexts
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrayerHeroSilenceControlTest {
    @get:Rule val compose = createComposeRule()

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val silenceLabel get() = context.getString(R.string.hero_phone_silence_title)
    private val unsilenceLabel get() = context.getString(R.string.hero_phone_unsilence_title)
    private val verseText get() = DisplayTexts.PRAYER_VERSES.first { it.id == "taha_13" }.text

    private fun labelBounds() = compose.onNodeWithText(context.getString(R.string.next_prayer_countdown_title))
        .fetchSemanticsNode().boundsInRoot

    @Before
    fun setup() {
        PrefsManager.setEnabled(context, false)
    }

    @Test
    fun tappingTheButton_updatesTheAction_oncePerTap() {
        var silenced by mutableStateOf(false)
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            PrayerHeroSilenceControl(silenced, {
                silenced = !silenced
                changes += silenced
            })
        }
        val control = compose.onNodeWithTag(TestTags.HERO_PHONE_SILENCE_BUTTON)
        control.assertContentDescriptionEquals(silenceLabel).performClick().assertContentDescriptionEquals(unsilenceLabel)
        control.performClick().assertContentDescriptionEquals(silenceLabel)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun speaker_showsTheState_whileTheLabelNamesTheTap() {
        assertEquals(R.drawable.ic_volume_on, heroSilenceIcon(silenced = false))
        assertEquals(R.drawable.ic_volume_off, heroSilenceIcon(silenced = true))
        compose.setContent { PrayerHeroSilenceControl(silenced = false, onClick = {}) }
        compose.onNodeWithTag(TestTags.HERO_PHONE_SILENCE_BUTTON).assertContentDescriptionEquals(silenceLabel)
    }

    @Test
    fun stateChangedElsewhere_updatesTheAction_withoutCallingBack() {
        var silenced by mutableStateOf(true)
        var clicks = 0
        compose.setContent { PrayerHeroSilenceControl(silenced, { clicks++ }) }

        compose.onNodeWithTag(TestTags.HERO_PHONE_SILENCE_BUTTON).assertContentDescriptionEquals(unsilenceLabel)
        compose.runOnIdle { silenced = false }
        compose.onNodeWithTag(TestTags.HERO_PHONE_SILENCE_BUTTON).assertContentDescriptionEquals(silenceLabel)
        assertEquals(0, clicks)
    }

    @Test
    fun accessibility_hasOneButton_namedForTheAction() {
        compose.setContent { PrayerHeroSilenceControl(true, {}) }

        compose.onNodeWithTag(TestTags.HERO_PHONE_SILENCE_BUTTON)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertContentDescriptionEquals(unsilenceLabel)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
        assertEquals(
            1,
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes().size,
        )
        assertTrue(
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun normalHero_endsThePrayerLineWithTheButton_besideTheArtwork() {
        var silenced by mutableStateOf(false)
        compose.setContent { HeroPreview(silenced = silenced) }
        val button = compose.onNodeWithTag(TestTags.HERO_PHONE_SILENCE_BUTTON)
        button.assertIsDisplayed().assertContentDescriptionEquals(silenceLabel)
        val buttonBounds = button.fetchSemanticsNode().boundsInRoot
        val prayerBounds = compose.onNodeWithText(context.getString(R.string.prayer_dhuhr), substring = false)
            .fetchSemanticsNode().boundsInRoot
        val timeBounds = compose.onNodeWithText("12:30").fetchSemanticsNode().boundsInRoot
        val verseBounds = compose.onNodeWithContentDescription(verseText).fetchSemanticsNode().boundsInRoot
        val cardBounds = compose.onNodeWithTag(TestTags.STATUS_CARD).fetchSemanticsNode().boundsInRoot
        assertEquals("The button is level with the prayer line", prayerBounds.center.y, buttonBounds.center.y, 1f)
        assertTrue("The button follows the prayer time, on the artwork's side", buttonBounds.right <= timeBounds.left)
        assertTrue("The button stays above the verse", buttonBounds.bottom <= verseBounds.top)
        assertTrue("The button is compact", buttonBounds.width < cardBounds.width / 2)
        assertTrue(cardBounds.contains(buttonBounds.topLeft) && cardBounds.contains(buttonBounds.bottomRight))
        savePreview("normal")

        compose.runOnIdle { silenced = true }
        button.assertContentDescriptionEquals(unsilenceLabel)
        savePreview("silenced")
    }

    @Test
    fun control_takesOnlyItsCircleOfRoom_butKeepsA48dpTouchTarget() {
        var clicks = 0
        compose.setContent { Box(Modifier.testTag("host")) { PrayerHeroSilenceControl(false, { clicks++ }) } }
        val density = context.resources.displayMetrics.density
        val circle = (34 * density).roundToInt()
        val target = (48 * density).roundToInt()
        assertEquals(IntSize(circle, circle), compose.onNodeWithTag("host").fetchSemanticsNode().size)
        val button = compose.onNodeWithTag(TestTags.HERO_PHONE_SILENCE_BUTTON)
        assertEquals(IntSize(target, target), button.fetchSemanticsNode().size)

        button.performTouchInput { click(Offset(2 * density, centerY)) }
        assertEquals("A press beside the circle, inside the touch target", 1, clicks)
    }

    @Test
    fun pendingAction_disablesTheButton() {
        var clicks = 0
        compose.setContent { PrayerHeroSilenceControl(false, { clicks++ }, enabled = false) }
        compose.onNodeWithTag(TestTags.HERO_PHONE_SILENCE_BUTTON).assertIsNotEnabled().performClick()
        assertEquals(0, clicks)
    }

    @Test
    fun narrowRtlHero_withLargeText_keepsTheIconButtonInsideTheCard() {
        compose.setContent { HeroPreview(width = 254.dp, fontScale = 2f, silenced = true) }
        val control = compose.onNodeWithTag(TestTags.HERO_PHONE_SILENCE_BUTTON)
        control.assertIsDisplayed().assertContentDescriptionEquals(unsilenceLabel)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
        val bounds = control.fetchSemanticsNode().boundsInRoot
        val cardBounds = compose.onNodeWithTag(TestTags.STATUS_CARD).fetchSemanticsNode().boundsInRoot
        assertTrue(cardBounds.contains(bounds.topLeft) && cardBounds.contains(bounds.bottomRight))
        assertEquals("The button is level with the label it shares a line with", labelBounds().center.y, bounds.center.y, 1f)
        savePreview("large-text")
    }

    @Test
    fun narrowHero_withNormalText_putsTheButtonOnTheLabelLine() {
        compose.setContent { HeroPreview(width = 300.dp, silenced = false) }
        val bounds = compose.onNodeWithTag(TestTags.HERO_PHONE_SILENCE_BUTTON).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val labelBounds = labelBounds()
        assertEquals("The button is level with the label", labelBounds.center.y, bounds.center.y, 1f)
        assertTrue("The button ends the line, away from the label", bounds.right <= labelBounds.left)
        savePreview("narrow")
    }

    @Composable
    private fun HeroPreview(width: Dp = 360.dp, fontScale: Float = 1f, silenced: Boolean) {
        val density = LocalDensity.current
        val today = LocalDate.now(ZoneId.of("Africa/Tunis"))
        val now = today.atTime(11, 0).atZone(ZoneId.of("Africa/Tunis")).toInstant().toEpochMilli()
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            TunisianPrayerTimesTheme {
                Box(Modifier.width(width).background(BgCream).padding(16.dp)) {
                    NextPrayerHeroCard(
                        countdown = NextPrayerCountdownInfo(Prayer.DHUHR, 12, 30, now + 90 * 60_000L, false, today),
                        prayerName = context.getString(R.string.prayer_dhuhr),
                        currentTimeMillis = now,
                        isPhoneSilenced = silenced,
                        hasDnd = true,
                        silenceReason = if (silenced) PhoneSilenceReason(PhoneSilenceReasonSource.MANUAL_UNTIL_STOPPED) else null,
                        autoSilenceEnabled = false,
                        silenceActionPending = false,
                        silenceActive = silenced,
                        onManualSilenceClick = {},
                    )
                }
            }
        }
    }

    private fun savePreview(name: String) {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag(TestTags.STATUS_CARD).fetchSemanticsNode().boundsInWindow
        val bitmap = compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().last { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
            Bitmap.createBitmap(bounds.width.toInt() + 16, bounds.height.toInt() + 16, Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                canvas.drawColor(android.graphics.Color.rgb(255, 248, 240))
                canvas.translate(8f - bounds.left, 8f - bounds.top)
                view.draw(canvas)
            }
        }
        val output = File("build/outputs/prayer-hero-preview/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
