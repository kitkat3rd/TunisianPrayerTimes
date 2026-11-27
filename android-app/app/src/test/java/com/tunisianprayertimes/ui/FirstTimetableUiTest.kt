package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.FirstRunPlaceChoice
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.R
import com.tunisianprayertimes.RamadanOverrideChecker
import com.tunisianprayertimes.engine.EventStatus
import com.tunisianprayertimes.engine.EventTime
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.platform.SharedAndroid
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

    private fun anchors(date: LocalDate): Map<Prayer, PrayerTimelineAnchor> {
        val zone = ZoneId.of("Africa/Tunis")
        val clockHours = mapOf(
            Prayer.FAJR to 5, Prayer.DHUHR to 12, Prayer.JOMOAA to 13, Prayer.ASR to 15,
            Prayer.MAGHRIB to 17, Prayer.ISHA to 19, Prayer.AID_FITR to 7, Prayer.AID_ADHA to 9,
        )
        return clockHours.mapValues { (prayer, hour) ->
            PrayerTimelineAnchor(prayer, date, date.atTime(hour, 0).atZone(zone).toInstant(), zone, inm = true)
        }
    }

    @Test fun fridayUsesJumuaInPlaceOfDhuhrAndPlacesEidByItsActualTime() {
        val friday = LocalDate.of(2026, 11, 27)
        val timetable = anchors(friday)
        val sunrise = friday.atTime(8, 0).atZone(ZoneId.of("Africa/Tunis")).toInstant()
        assertEquals(
            listOf(Prayer.FAJR, Prayer.AID_FITR, null, Prayer.JOMOAA, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA),
            prayerTimetableOrder(friday, timetable, sunrise, showFitr = true, showAdha = false),
        )
        assertEquals(
            listOf(Prayer.FAJR, null, Prayer.AID_ADHA, Prayer.JOMOAA, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA),
            prayerTimetableOrder(friday, timetable, sunrise, showFitr = false, showAdha = true),
        )
    }

    @Test fun ordinaryDayShowsOnlyItsFivePrayersAndSunrise() {
        val thursday = LocalDate.of(2026, 11, 26)
        val sunrise = thursday.atTime(7, 30).atZone(ZoneId.of("Africa/Tunis")).toInstant()
        assertEquals(
            listOf(Prayer.FAJR, null, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA),
            prayerTimetableOrder(thursday, anchors(thursday), sunrise, showFitr = false, showAdha = false),
        )
    }

    @Test fun afterMidnightIshaHasTheNextBadgeOnlyOnItsOwnPrayerDay() {
        val zone = ZoneId.of("Europe/Paris")
        val prayerDay = LocalDate.of(2026, 6, 17)
        val civilDay = prayerDay.plusDays(1)
        val next = NextPrayerCountdownInfo(Prayer.ISHA, civilDay.atTime(0, 47).atZone(zone).toInstant(), prayerDay, zone)
        assertEquals(null, next.prayerOn(civilDay))
        assertEquals(Prayer.ISHA, next.prayerOn(prayerDay))
    }

    @Test fun heroFollowsTheTimetablesSunriseAndEditedEidTimesThenAdvances() {
        SharedAndroid.init(context)
        val tunis = PrayerPlace.Inm(615)
        val fitr = RamadanOverrideChecker.getEidFitrDate(1447)
        val adha = RamadanOverrideChecker.getEidAdhaDate(1447)
        PrefsManager.setAidAdhaTime(context, 8, 15)
        for ((prayer, date) in listOf(Prayer.AID_FITR to fitr, Prayer.AID_ADHA to adha)) {
            val eid = requireNotNull(eidTimelineAnchor(context, prayer, date, tunis))
            val before = eid.adhan.minusSeconds(1)
            val next = requireNotNull(PrayerTimesRepository.nextTimetablePrayer(context, before, tunis))
            assertEquals(prayer, next.prayer)
            assertEquals(date, next.prayerDay)
            assertEquals(eid.adhan, next.at)
            val nextDaily = PrayerTimesRepository.upcomingPrayers(context, before, tunis).first()
            assertEquals(nextDaily, PrayerTimesRepository.nextTimetablePrayer(context, eid.adhan, tunis))
        }
    }

    @Test fun hiddenEidRowAfterDhuhrIsNotPromotedToTheHeroByALateCustomTime() {
        SharedAndroid.init(context)
        val tunis = PrayerPlace.Inm(615)
        val date = RamadanOverrideChecker.getEidAdhaDate(1447)
        PrefsManager.setAidAdhaTime(context, 14, 0)
        val afterDhuhr = date.atTime(13, 30).atZone(tunis.zone).toInstant()
        val daily = PrayerTimesRepository.upcomingPrayers(context, afterDhuhr, tunis).first()
        assertEquals(daily, PrayerTimesRepository.nextTimetablePrayer(context, afterDhuhr, tunis))
    }

    @Test fun savedWindowAcrossTheRepeatedHourShowsBothOffsets() {
        val zone = ZoneId.of("America/New_York")
        val anchor = PrayerTimelineAnchor(
            Prayer.FAJR, LocalDate.of(2026, 11, 1), Instant.parse("2026-11-01T05:50:00Z"), zone,
            inm = false, displayZone = zone,
        )
        compose.setContent {
            androidx.compose.material3.Text(
                timetableSilenceSummary(anchor, PrayerSilenceConfig(delayMinutes = 0, endOffsetMinutes = 20),
                    enabled = true, ready = false, mosqueMode = true),
            )
        }
        val expectedStart = context.getString(R.string.timetable_clock_offset, prayerClockText(110), "-04:00")
        val expectedEnd = context.getString(R.string.timetable_clock_offset, prayerClockText(70), "-05:00")
        compose.onNodeWithText(expectedStart, substring = true).assertIsDisplayed()
            .assert(hasText(expectedEnd, substring = true))
    }

    @Test fun previewableEidOutsideTheSelectedDateStaysOutOfTheTimetable() {
        val date = LocalDate.of(2026, 11, 26)
        val futureEid = anchors(date.plusDays(1)).getValue(Prayer.AID_FITR)
        val timetable = anchors(date) + (Prayer.AID_FITR to futureEid)
        assertEquals(
            listOf(Prayer.FAJR, null, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA),
            prayerTimetableOrder(date, timetable, sunrise = null, showFitr = true, showAdha = false),
        )
    }

    @Test fun actionablePrayerRowAnnouncesTimeNextAndSilenceWithOneEditorAction() {
        val anchor = PrayerTimelineAnchor.demo(Prayer.JOMOAA, 12, 13)
        val summary = "إسكات من 12:15 إلى 12:30"
        var opened = 0
        compose.setContent {
            TunisianPrayerTimesTheme {
                CompactPrayerTimeRow(
                    Prayer.JOMOAA, "الجمعة", anchor, true,
                    onOpenEditor = { opened++ }, silenceSummary = summary,
                )
            }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithTag("today_time_JOMOAA").assertHasClickAction()
            .assert(hasText("الجمعة") and hasText("12:13", substring = true))
            .assert(hasText(context.getString(R.string.prayer_timeline_up_next)) and hasText(summary))
            .performClick()
        assertEquals(1, opened)
    }

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
