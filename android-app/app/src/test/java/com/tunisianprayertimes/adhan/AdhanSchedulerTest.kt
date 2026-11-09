package com.tunisianprayertimes.adhan

import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTimesRepository.PrayerOccurrence
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
}
