package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
    private val silenceLabel get() = context.getString(R.string.manual_silence_action_duration, formatCountdownRemaining(30 * 60_000L, 0L))
    private val unsilenceLabel get() = context.getString(R.string.manual_silence_action_stop)

    @Before
    fun setup() {
        PrefsManager.setEnabled(context, false)
    }

    @Test
    fun visibleActionNamesTheSelectedDurationAndUpdatesWhenSilenced() {
        var silenced by mutableStateOf(false)
        val changes = mutableListOf<Boolean>()
        compose.setContent {
            PrayerHeroSilenceControl(
                silenced, if (silenced) unsilenceLabel else silenceLabel,
                onClick = { silenced = !silenced; changes += silenced }, onChooseDuration = {},
            )
        }
        val control = compose.onNodeWithTag(TestTags.HERO_PHONE_SILENCE_BUTTON)
        control.assert(hasText(silenceLabel)).performClick()
        control.assert(hasText(unsilenceLabel)).performClick()
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun choosingADurationDoesNotStartSilencing() {
        var starts = 0
        var choices = 0
        compose.setContent { PrayerHeroSilenceControl(false, silenceLabel, { starts++ }, { choices++ }) }
        compose.onNodeWithTag(TestTags.HERO_SILENCE_DURATION)
            .assert(hasText(context.getString(R.string.manual_silence_change_duration)))
            .assertContentDescriptionEquals(context.getString(R.string.manual_silence_choose_end)).performClick()
        assertEquals(1, choices)
        assertEquals(0, starts)
        compose.onNodeWithTag(TestTags.HERO_PHONE_SILENCE_BUTTON)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            .assert(hasText(silenceLabel))
    }

    @Test
    fun activeSessionHasOneEndActionAndNoDurationEditor() {
        compose.setContent { PrayerHeroSilenceControl(true, unsilenceLabel, {}, {}) }
        compose.onNodeWithText(unsilenceLabel).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.HERO_SILENCE_DURATION).assertDoesNotExist()
    }

    @Test
    fun defaultWidthActionAndVisibleDurationOptionFitOnOneRowInReadingOrder() {
        var silenced by mutableStateOf(false)
        compose.setContent { HeroPreview(silenced = silenced) }
        val action = compose.onNodeWithTag(TestTags.HERO_PHONE_SILENCE_BUTTON)
        val actionBounds = action.assertIsDisplayed().assert(hasText(silenceLabel)).fetchSemanticsNode().boundsInRoot
        val timeBounds = compose.onNodeWithText("12:30", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val optionBounds = compose.onNodeWithTag(TestTags.HERO_SILENCE_DURATION).fetchSemanticsNode().boundsInRoot
        assertSkylineFillsCard()
        assertTrue("Time is above the manual action", timeBounds.bottom <= actionBounds.top)
        assertTrue("Primary action is first in RTL", actionBounds.left >= optionBounds.right)
        assertTrue("Actions share a row", actionBounds.top < optionBounds.bottom && optionBounds.top < actionBounds.bottom)
        compose.savePrayerHeroPreview("normal")

        compose.runOnIdle { silenced = true }
        action.assert(hasText(unsilenceLabel))
        compose.savePrayerHeroPreview("silenced")
    }

    @Test
    fun remoteHeroGroupsThePrayerAndBothClocksWithTheirActualDatesAndKeepsTheControlSeparate() {
        val placeZone = ZoneId.of("Pacific/Kiritimati")
        val phoneZone = ZoneId.of("America/New_York")
        val at = Instant.parse("2026-11-25T10:30:00Z")
        val prayerDay = LocalDate.parse("2026-11-26")
        compose.setContent {
            HeroPreview(
                silenced = false,
                countdown = NextPrayerCountdownInfo(Prayer.DHUHR, at, prayerDay, placeZone),
                now = at.minusSeconds(3600).toEpochMilli(),
                phoneZone = phoneZone,
            )
        }
        val times = compose.onNodeWithTag("next_prayer_summary")
        assertSkylineFillsCard()
        times.assert(hasText(context.getString(R.string.prayer_dhuhr)) and hasText("05:30"))
            .assert(hasText("00:30", substring = true))
            .assert(hasText(gregorianDateLabel(prayerDay.minusDays(1)), substring = true))
            .assert(hasText(gregorianDateLabel(prayerDay), substring = true))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithTag(TestTags.HERO_PHONE_SILENCE_BUTTON).assert(hasText(silenceLabel))
    }

    @Test
    fun pendingActionDisablesStartingAndChangingTheDuration() {
        var calls = 0
        compose.setContent { PrayerHeroSilenceControl(false, silenceLabel, { calls++ }, { calls++ }, enabled = false) }
        compose.onNodeWithTag(TestTags.HERO_PHONE_SILENCE_BUTTON).assertIsNotEnabled().performClick()
        compose.onNodeWithTag(TestTags.HERO_SILENCE_DURATION).assertIsNotEnabled().performClick()
        assertEquals(0, calls)
    }

    @Test
    fun narrowRtlHeroWithLargeTextKeepsBothActionsReadableAndTappable() {
        compose.setContent { HeroPreview(width = 254.dp, fontScale = 2f, silenced = false) }
        assertSkylineFillsCard()
        val control = compose.onNodeWithTag(TestTags.HERO_PHONE_SILENCE_BUTTON).assertIsDisplayed()
        val options = compose.onNodeWithTag(TestTags.HERO_SILENCE_DURATION).assertIsDisplayed()
        control.assert(hasText(silenceLabel))
        options.assert(hasText(context.getString(R.string.manual_silence_change_duration)))
        assertTrue("The duration editor flows below the primary action at narrow widths",
            control.fetchSemanticsNode().boundsInRoot.bottom <= options.fetchSemanticsNode().boundsInRoot.top)
        val card = compose.onNodeWithTag(TestTags.STATUS_CARD).fetchSemanticsNode().boundsInRoot
        val density = context.resources.displayMetrics.density
        for (node in listOf(control, options)) {
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(card.contains(bounds.topLeft) && card.contains(bounds.bottomRight))
            assertTrue(bounds.height >= 48 * density - 1 && bounds.width >= 48 * density - 1)
        }
        compose.savePrayerHeroPreview("large-text")
    }

    private fun assertSkylineFillsCard() {
        val card = compose.onNodeWithTag(TestTags.STATUS_CARD).fetchSemanticsNode().boundsInRoot
        val artwork = compose.onNodeWithTag("hero_skyline").fetchSemanticsNode().boundsInRoot
        assertEquals(card, artwork)
    }

    @Test
    fun browsingAnotherDateIdentifiesTheCountdownAsLive() {
        compose.setContent { HeroPreview(silenced = false, browsingAnotherDate = true) }
        compose.onNodeWithText(context.getString(R.string.next_prayer_countdown_live_title)).assertIsDisplayed()
        compose.onNodeWithText("12:30", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun countdownKeepsEachArabicUnitWithItsQuantityIncludingTheFinalSeconds() {
        assertEquals("1\u00a0س و\u00a030\u00a0د", formatCountdownRemaining(90 * 60_000L, 0L))
        assertEquals("1\u00a0س", formatCountdownRemaining(60 * 60_000L, 0L))
        assertEquals("30\u00a0د", formatCountdownRemaining(30 * 60_000L, 0L))
        assertEquals("5\u00a0ث", formatCountdownRemaining(5_000L, 0L))
    }

    @Composable
    private fun HeroPreview(
        width: Dp = 360.dp,
        fontScale: Float = 1f,
        silenced: Boolean,
        browsingAnotherDate: Boolean = false,
        phoneZone: ZoneId = ZoneId.of("Africa/Tunis"),
        now: Long = LocalDate.of(2026, 11, 26).atTime(11, 0).atZone(phoneZone).toInstant().toEpochMilli(),
        countdown: NextPrayerCountdownInfo = NextPrayerCountdownInfo(
            Prayer.DHUHR, Instant.ofEpochMilli(now).plusSeconds(90 * 60L), Instant.ofEpochMilli(now).atZone(phoneZone).toLocalDate(), phoneZone,
        ),
    ) {
        PrayerHeroPreviewFrame(width, fontScale) {
            NextPrayerHeroCard(
                countdown = countdown,
                browsingAnotherDate = browsingAnotherDate,
                prayerName = context.getString(R.string.prayer_dhuhr),
                currentTimeMillis = now,
                isPhoneSilenced = silenced,
                hasDnd = true,
                silenceReason = if (silenced) PhoneSilenceReason(PhoneSilenceReasonSource.MANUAL_UNTIL_STOPPED) else null,
                autoSilenceEnabled = false,
                silenceActionPending = false,
                silenceActive = silenced,
                phoneZone = phoneZone,
                configuredMode = AutomaticSilenceMode.TIME_ONLY,
                setupIssues = emptyList(),
                onRepairSilence = {},
                manualActionLabel = if (silenced) unsilenceLabel else silenceLabel,
                onChooseManualDuration = {},
                onManualSilenceClick = {},
            )
        }
    }
}
