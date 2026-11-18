package com.tunisianprayertimes.adhan

import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTimesRepository.PrayerOccurrence
import com.tunisianprayertimes.silence.EndResolution
import com.tunisianprayertimes.silence.SilenceWindow
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdhanSchedulerTest {
    private val today = LocalDate.of(2026, 11, 9)
    private val now = Instant.parse("2026-11-09T12:00:00Z")

    private fun at(prayer: Prayer, day: LocalDate, iso: String) = PrayerOccurrence(prayer, day, Instant.parse(iso))

    @Test
    fun onlyTheEnabledPrayersStillToComeAreArmedInTimeOrder() {
        val occurrences = listOf(
            at(Prayer.ISHA, today, "2026-11-09T17:45:00Z"),
            at(Prayer.DHUHR, today, "2026-11-09T11:20:00Z"),
            at(Prayer.ASR, today, "2026-11-09T14:00:00Z"),
            at(Prayer.MAGHRIB, today, "2026-11-09T16:30:00Z"),
            at(Prayer.FAJR, today.plusDays(1), "2026-11-10T05:10:00Z"),
            // The same occurrence read twice (overlapping prayer days): armed once.
            at(Prayer.ASR, today, "2026-11-09T14:00:00Z"),
        )
        val armed = AdhanScheduler.upcoming(occurrences, setOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.ISHA), now)
        assertEquals(
            listOf(Prayer.ASR to today, Prayer.ISHA to today, Prayer.FAJR to today.plusDays(1)),
            armed.map { it.prayer to it.prayerDay },
        )
    }

    @Test
    fun anAdhanAtNowIsNotArmedAgain() {
        val occurrences = listOf(at(Prayer.DHUHR, today, "2026-11-09T12:00:00Z"))
        assertEquals(emptyList<PrayerOccurrence>(), AdhanScheduler.upcoming(occurrences, setOf(Prayer.DHUHR), now))
    }

    @Test
    fun everyPrayerAndDaySlotHasItsOwnRequestCode() {
        val codes = AdhanSettings.prayers.flatMap { prayer -> (-1L..1L).map { AdhanScheduler.requestCode(prayer, it) } }
        assertEquals(AdhanSettings.prayers.size * 3, codes.filterNotNull().toSet().size)
        assertNull(AdhanScheduler.requestCode(Prayer.AID_FITR, 0))
        assertNull(AdhanScheduler.requestCode(Prayer.FAJR, 2))
    }

    @Test
    fun theWarningComesFifteenSecondsBeforeUnlessThatTimeHasPassed() {
        val adhan = now.toEpochMilli() + 10 * 60_000L
        assertEquals(15_000L, AdhanScheduler.WARNING_LEAD_MILLIS)
        assertEquals(adhan - 15_000L, AdhanScheduler.warningAt(adhan, now.toEpochMilli()))
        assertNull(AdhanScheduler.warningAt(adhan, adhan - 10_000L))
        assertNull(AdhanScheduler.warningAt(adhan, adhan - 15_000L))
    }

    @Test
    fun warningsHaveTheirOwnRequestCodes() {
        val slots = AdhanSettings.prayers.flatMap { prayer -> (-1L..1L).map { prayer to it } }
        val adhans = slots.mapNotNull { (prayer, day) -> AdhanScheduler.requestCode(prayer, day) }.toSet()
        val warnings = slots.mapNotNull { (prayer, day) -> AdhanScheduler.warningCode(prayer, day) }.toSet()
        assertEquals(slots.size, warnings.size)
        assertEquals(emptySet<Int>(), adhans intersect warnings)
    }

    @Test
    fun aBuzzComesBeforeASilenceThatWouldKeepTheWarningStill() {
        val adhan = Instant.parse("2026-11-09T13:45:00Z")
        val warning = adhan.toEpochMilli() - AdhanScheduler.WARNING_LEAD_MILLIS
        fun silence(start: String) = SilenceWindow(
            Prayer.ASR, today, adhan, Instant.parse(start), Instant.parse("2026-11-09T14:15:00Z"), EndResolution.AS_SET, clamped = false,
        )
        val early = silence("2026-11-09T13:40:00Z")
        val start = AdhanScheduler.silenceStartBeforeWarning(listOf(early), warning) { false }
        assertEquals(early.start.toEpochMilli(), start)
        assertEquals(early.start.toEpochMilli() - 10_000L, AdhanScheduler.beforeSilenceAt(start, now.toEpochMilli()))
        // A silence that starts with the adhan: its warning comes first and vibrates.
        assertNull(AdhanScheduler.silenceStartBeforeWarning(listOf(silence("2026-11-09T13:45:00Z")), warning) { false })
        // Dismissed: no silence will come.
        assertNull(AdhanScheduler.silenceStartBeforeWarning(listOf(early), warning) { true })
        // Begun already: the phone is already still.
        assertNull(AdhanScheduler.beforeSilenceAt(start, early.start.toEpochMilli()))
    }
}
