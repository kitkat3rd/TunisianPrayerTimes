package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.mosque.presence.AdaptiveObservation
import com.tunisianprayertimes.mosque.presence.AdaptiveObservation.Sampling
import com.tunisianprayertimes.mosque.presence.AdaptiveVisitDetector.Departure
import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.PresenceFix
import com.tunisianprayertimes.mosque.presence.SilenceArea
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntranceReturnTest {
    // Invented metre grid. The observed entrance is halfway along the x = 0 wall.
    private fun point(x: Int, y: Int) = GeoPoint(y / 110_574.0, x / 111_319.5)

    private val hall = SilenceArea("hall", 1, listOf(SilencePolygon(listOf(
        point(0, -100), point(110, -100), point(110, 100), point(0, 100),
    ))))

    private inner class Visit(areas: List<SilenceArea> = listOf(hall)) {
        val observation = AdaptiveObservation(areas, 0).apply {
            period(true, 0)
            start(0)
        }
        private var at = 0L

        fun fix(x: Int, y: Int = 0, after: Long = 5_000, accuracy: Double? = 6.0, delay: Long = 0) {
            at += after
            val accepted = observation.observe(PresenceFix(point(x, y), accuracy, at), at + delay)
            assertEquals("delivery at ${at}ms", accuracy != null, accepted)
        }

        fun enterAndStop(minutes: Int = 10, seat: Int = 65) {
            fix(-35)
            fix(-24, after = 10_000, accuracy = 11.0)
            for (x in listOf(2, 8, 13, 18)) fix(x)
            assertEquals(hall.id, observation.detector.confirmedAreaId)
            fix(seat, after = 30_000)
            repeat(2 * minutes) { fix(seat, after = 30_000) }
            assertTrue(observation.desired)
        }

        fun approachDoor(y: Int = 0) {
            fix(35, y / 2, after = 30_000)
            fix(16, y, after = 18_000)
            assertTrue(observation.desired)
        }
    }

    private fun returningVisit() = Visit().apply {
        enterAndStop()
        approachDoor()
    }

    @Test fun aSustainedStayAndReturnToTheObservedDoorReleaseAfterTwoOutwardFixes() {
        val visit = returningVisit()
        // The indoor precision probe has expired. The first circle clears the wall by only 1 m.
        visit.fix(-8, after = 18_000, accuracy = 7.0)
        assertTrue(visit.observation.desired)
        assertEquals(Sampling.PRECISE, visit.observation.sampling)
        visit.fix(-9, after = 350, accuracy = 7.0)
        assertTrue("callbacks a fraction of a second apart cannot corroborate exit", visit.observation.desired)
        visit.fix(-12, after = 2_650, accuracy = 7.0)
        assertFalse(visit.observation.desired)
        assertEquals(Departure.ENTRANCE_RETURN, visit.observation.detector.departureOnLastFix)
    }

    @Test fun walkingToTheDoorAndTurningBackKeepsSilence() {
        val visit = returningVisit()
        visit.fix(3, after = 9_000)
        visit.fix(-9)
        assertTrue(visit.observation.desired)
        visit.fix(-7, after = 3_000) // Still outside the accuracy circle but turning inward.
        assertTrue(visit.observation.desired)
        visit.fix(2)
        visit.fix(12)
        assertEquals(hall.id, visit.observation.detector.confirmedAreaId)
        assertTrue(visit.observation.desired)
    }

    @Test fun worseningAccuracyOrLateDeliveryCannotCompleteTheFastExit() {
        val uncertain = returningVisit()
        uncertain.fix(-9, accuracy = 3.0)
        uncertain.fix(-12, after = 3_000, accuracy = 9.0)
        assertTrue(uncertain.observation.desired)

        val delayed = returningVisit()
        delayed.fix(-9)
        delayed.fix(-14, after = 3_000, delay = 8_000)
        assertTrue(delayed.observation.desired)
    }

    @Test fun stoppingOnTheWayToTheDoorEndsThePrecisionCheckOnTimeAndKeepsSilence() {
        val visit = Visit()
        visit.enterAndStop()
        assertEquals(Sampling.BALANCED, visit.observation.sampling)
        visit.fix(24, after = 30_000)
        val end = requireNotNull(visit.observation.requestEnd)
        assertEquals(Sampling.PRECISE, visit.observation.sampling)
        repeat(5) {
            visit.fix(24)
            assertEquals(end, visit.observation.requestEnd)
            assertEquals(Sampling.PRECISE, visit.observation.sampling)
            assertTrue(visit.observation.desired)
        }
        // At 30 s the burst ends. Repeated positions at the same indoor spot do not renew it.
        repeat(30) {
            visit.fix(24)
            assertEquals(Sampling.BALANCED, visit.observation.sampling)
            assertTrue(visit.observation.desired)
        }
        visit.fix(-9, after = 30_000)
        visit.fix(-14, after = 3_000)
        assertTrue("the old route cannot lend its early exit to a later departure", visit.observation.desired)
    }

    @Test fun anotherDoorAlongTheSameWallKeepsTheOrdinaryDepartureCheck() {
        val visit = Visit()
        visit.enterAndStop()
        visit.approachDoor(y = 55)
        visit.fix(-9, y = 55, after = 18_000)
        visit.fix(-14, y = 55, after = 3_000)
        assertTrue(visit.observation.desired)
    }

    @Test fun aLongStayAloneCannotReplaceAnObservedReturnTowardTheEntrance() {
        val visit = Visit()
        visit.enterAndStop()
        visit.fix(-9, after = 30_000) // A balanced interval could miss the entire indoor walk.
        visit.fix(-14, after = 3_000)
        assertTrue(visit.observation.desired)
    }

    @Test fun shuttlingAcrossTheHallCannotQualifyAsAStationaryStay() {
        val visit = Visit()
        visit.enterAndStop(minutes = 0)
        // The visitor revisits the same indoor spot every minute but walks away between visits.
        repeat(6) {
            visit.fix(20, after = 30_000)
            visit.fix(65, after = 30_000)
        }
        visit.approachDoor()
        visit.fix(-9, after = 18_000)
        visit.fix(-14, after = 3_000)
        assertTrue(visit.observation.desired)
    }

    @Test fun bothExteriorReadingsMustClearOtherSavedAreasToo() {
        val annex = SilenceArea("annex", 1, listOf(SilencePolygon(listOf(
            point(-18, 4), point(-6, 4), point(-6, 15), point(-18, 15),
        ))))
        val visit = Visit(listOf(hall, annex))
        visit.enterAndStop()
        visit.approachDoor()
        visit.fix(-9, after = 18_000)
        visit.fix(-14, after = 3_000)
        assertTrue("both 6 m accuracy circles overlap the annex", visit.observation.desired)
    }

    @Test fun aShortStopOrASeatCloseToTheDoorCannotAuthorizeTheQuickExit() {
        val short = Visit()
        short.enterAndStop(minutes = 1)
        short.approachDoor()
        short.fix(-9, after = 18_000)
        short.fix(-14, after = 3_000)
        assertTrue(short.observation.desired)

        val close = Visit()
        close.enterAndStop(seat = 14)
        close.fix(10, after = 30_000)
        close.fix(-9, after = 18_000)
        close.fix(-14, after = 3_000)
        assertTrue(close.observation.desired)
    }

    @Test fun aGapCannotCorroborateTheFirstExteriorReading() {
        val visit = returningVisit()
        visit.fix(-9, after = 18_000)
        visit.fix(-14, after = 13_000)
        assertTrue(visit.observation.desired)
    }

    @Test fun anUnusableOrBoundaryStraddlingFixBreaksTheExteriorPair() {
        for ((x, uncertainty) in listOf(-11 to null, -35 to 32.0, -5 to 7.0)) {
            val visit = returningVisit()
            visit.fix(-9, after = 18_000)
            visit.fix(x, after = 2_000, accuracy = uncertainty)
            visit.fix(-14, after = 2_000)
            assertTrue("intervening x=$x with accuracy $uncertainty", visit.observation.desired)
        }
    }
}
