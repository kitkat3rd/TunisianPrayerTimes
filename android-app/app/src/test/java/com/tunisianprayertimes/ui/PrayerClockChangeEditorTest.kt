package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w412dp-h900dp-mdpi", application = Application::class)
class PrayerClockChangeEditorTest {
    @get:Rule val compose = createComposeRule()
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("America/New_York")
    // 01:50 EDT plus twenty elapsed minutes is 01:10 EST on the same calendar date.
    private val anchor = PrayerTimelineAnchor(
        Prayer.FAJR, LocalDate.of(2026, 11, 1), Instant.parse("2026-11-01T05:50:00Z"), zone,
        inm = false, displayZone = zone,
    )
    private val config = PrayerSilenceConfig(delayMinutes = 0, endOffsetMinutes = 20)

    @Test fun repeatedHourKeepsBothOffsetLabelledEndpointsEditable() {
        val opened = mutableListOf<SilenceEndpoint>()
        compose.setContent {
            TunisianPrayerTimesTheme {
                PrayerSilenceSection(
                    prayer = anchor.prayer,
                    prayerName = context.getString(R.string.prayer_fajr),
                    anchor = anchor,
                    config = config,
                    window = resolvePrayerTimelineWindow(anchor, config),
                    scale = PrayerTimelineScale(-15, 60),
                    enabled = true,
                    showSwitch = true,
                    isNextPrayer = false,
                    onEnabledChange = {},
                    onPreviewWindow = {},
                    onCommitWindow = {},
                    onEditEndpoint = { opened.add(it) },
                    onPrayerTimeClick = null,
                )
            }
        }
        compose.onNodeWithTag("prayer_silence_slider_FAJR").assertDoesNotExist()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.prayer_silence_duration_minutes, 20, 20))
            .assertIsDisplayed()
        compose.onNode(hasClickAction() and hasText("01:50", substring = true) and hasText("UTC-04:00", substring = true))
            .assertIsDisplayed().performClick()
        compose.onNode(hasClickAction() and hasText("01:10", substring = true) and hasText("UTC-05:00", substring = true))
            .assertIsDisplayed().performClick()
        assertEquals(listOf(SilenceEndpoint.START, SilenceEndpoint.END), opened)
    }

    @Test fun endEditorRetainsTwentyMinutesAfterAdhanDuringTheRepeatedHour() {
        var saved: PrayerSilenceConfig? = null
        compose.setContent {
            TunisianPrayerTimesTheme {
                PrayerSilenceEditorSheet(
                    prayer = anchor.prayer,
                    prayerName = context.getString(R.string.prayer_fajr),
                    anchor = anchor,
                    config = config,
                    endpoint = SilenceEndpoint.END,
                    onDismiss = {},
                    onSave = { saved = it; true },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.prayer_silence_direction_after)).assertIsSelected()
        compose.onNodeWithContentDescription(context.getString(R.string.prayer_editor_minutes_unit)).assertTextContains("20")
        compose.onNodeWithText(context.getString(R.string.prayer_editor_apply)).assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            assertEquals(20, saved?.endOffsetMinutes)
            assertEquals(20, saved?.afterMinutes)
        }
    }
}
