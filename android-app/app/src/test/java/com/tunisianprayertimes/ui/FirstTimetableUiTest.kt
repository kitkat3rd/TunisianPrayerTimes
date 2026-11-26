package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.FirstRunPlaceChoice
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import com.tunisianprayertimes.engine.EventStatus
import com.tunisianprayertimes.engine.EventTime
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w320dp-h700dp-mdpi", application = Application::class)
class FirstTimetableUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun compactFridayClockOpensItsEditor() {
        val anchor = PrayerTimelineAnchor.demo(Prayer.JOMOAA, 12, 13)
        var edits = 0
        compose.setContent {
            TunisianPrayerTimesTheme {
                CompactPrayerTimeRow(Prayer.JOMOAA, "الجمعة", anchor, true, { edits++ })
            }
        }
        compose.onNodeWithText("12:13", substring = true).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.prayer_timeline_up_next)).assertIsDisplayed()
        assertEquals(1, edits)
    }

    @Test fun sunriseShowsPhoneClockAndExplainsItsDayOffset() {
        val sunrise = EventTime(TimeEvent.SUNRISE, Instant.parse("2026-02-11T00:10:00Z"), EventStatus.COMPUTED)
        compose.setContent {
            TunisianPrayerTimesTheme {
                CompactSunriseRow(sunrise, LocalDate.parse("2026-02-11"), ZoneId.of("America/New_York"))
            }
        }
        compose.onNodeWithTag("today_time_SUNRISE")
            .assert(hasText(context.getString(R.string.adhkar_shuruk)) and hasText("19:10", substring = true))
        compose.onNodeWithTag("marker_day_SUNRISE", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun dismissingSetupKeepsTheCompactTimetableCohort() {
        PrefsManager.initializeFirstRun(context)
        PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Browse(PrayerPlace.Inm(615), null))
        PrefsManager.setPlaceProvisional(context, false)
        compose.setContent { TunisianPrayerTimesTheme { FirstRunFeatureSuggestion() } }
        compose.onNodeWithText(context.getString(R.string.today_dismiss_setup)).performClick()
        compose.onNodeWithTag("first_timetable_setup").assertDoesNotExist()
        assertFalse(PrefsManager.isFirstRunSetupSuggested(context))
        assertTrue(PrefsManager.usesPlaceOnboarding(context))
    }
}
