package com.tunisianprayertimes.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrayerHeroTimeTest {
    @Test fun sameWinterOffsetShowsOneClockAcrossDifferentZoneIds() {
        val at = Instant.parse("2026-11-25T12:15:00Z")
        val time = prayerHeroTime(at, at.minusSeconds(900), ZoneId.of("Europe/Paris"), ZoneId.of("Africa/Tunis"))
        assertEquals("13:15", time.phoneClock)
        assertNull(time.placeClock)
        assertNull(time.phoneDate)
        assertNull(time.placeDate)
    }

    @Test fun springTransitionUsesTheOffsetAtTheFuturePrayer() {
        val time = prayerHeroTime(
            Instant.parse("2026-03-29T01:30:00Z"), Instant.parse("2026-03-29T00:45:00Z"),
            ZoneId.of("Europe/Paris"), ZoneId.of("Africa/Tunis"),
        )
        assertEquals("03:30", time.phoneClock)
        assertEquals("02:30", time.placeClock)
        assertNull(time.phoneDate)
        assertNull(time.placeDate)
    }

    @Test fun sameClockReadingAcrossTheDateLineNeedsBothDates() {
        val at = Instant.parse("2026-11-25T10:30:00Z")
        val time = prayerHeroTime(at, at.minusSeconds(3600), ZoneId.of("Pacific/Honolulu"), ZoneId.of("Pacific/Kiritimati"))
        assertEquals("00:30", time.phoneClock)
        assertEquals("00:30", time.placeClock)
        assertEquals(LocalDate.parse("2026-11-25"), time.phoneDate)
        assertEquals(LocalDate.parse("2026-11-26"), time.placeDate)
        assertFalse(time.tomorrow)
    }

    @Test fun anAfterMidnightAdhanUsesItsActualCalendarDayForTomorrow() {
        val at = Instant.parse("2026-11-25T23:30:00Z")
        val zone = ZoneId.of("Africa/Tunis")
        val time = prayerHeroTime(at, at.minusSeconds(3600), zone, zone)
        assertEquals("00:30", time.phoneClock)
        assertTrue(time.tomorrow)
        assertNull(time.placeClock)
    }
}
