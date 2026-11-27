package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.mosque.presence.AdaptiveObservation
import com.tunisianprayertimes.mosque.presence.AdaptiveVisitDetector
import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.PresenceFix
import com.tunisianprayertimes.mosque.presence.SilenceArea
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PavementVisitTest {
    // The west wall is x = 0; the pavement runs north/south at x = -5.
    private fun point(x: Int, y: Int) = GeoPoint(y / 110_574.0, x / 111_319.5)

    private fun rectangle(id: String, west: Int, east: Int, south: Int, north: Int) =
        SilenceArea(id, 1, listOf(SilencePolygon(listOf(
            point(west, south), point(east, south), point(east, north), point(west, north),
        ))))

    private val hall = rectangle("hall", 0, 100, -90, 90)

    private inner class Walk(areas: List<SilenceArea> = listOf(hall)) {
        val observation = AdaptiveObservation(areas, 0).also { it.start(0); it.period(true, 0) }
        var time = 0L
            private set

        fun fix(x: Int, y: Int, accuracy: Double = 8.0, seconds: Int = 5) {
            time += seconds * 1_000L
            assertTrue("rejected ($x,$y) at $time",
                observation.observe(PresenceFix(point(x, y), accuracy, time), time))
        }

        fun approachOnPavement(startY: Int = -40) {
            fix(-36, startY - 15)
            fix(-26, startY - 12)
            fix(-18, startY - 9)
            fix(-10, startY - 6)
            for (y in startY..0 step 8) {
                fix(-5, y)
                assertFalse("approaching on the pavement at y=$y", observation.desired)
            }
        }

        fun enterAndPray() {
            fix(3, 0, 4.0)
            fix(11, 0, 4.0)
            fix(19, 0, 4.0)
            fix(25, 0, 4.0)
            repeat(4) { fix(25, 0, 4.0) }
            assertEquals(hall.id, observation.detector.confirmedAreaId)
            assertTrue(observation.desired)
            repeat(6) { fix(25, 0, 4.0, seconds = 55) }
            assertTrue("confirmed visit must survive ordinary indoor sampling", observation.desired)
        }

        fun reachDoor(y: Int = 0) {
            fix(17, y, 4.0, seconds = 30)
            fix(10, y, 4.0)
            fix(3, y, 4.0)
            assertTrue(observation.desired)
        }
    }

    @Test fun stoppingOnThePavementAfterAnApproachDoesNotSilenceThePhone() {
        // Both the outer edge dwell and the wider nearby dwell used to accept exterior-only stops.
        for (x in listOf(-5, -2)) {
            val walk = Walk()
            for (from in listOf(-50, -40, -30, -20, -10, x)) walk.fix(from, 0)
            repeat(20) {
                walk.fix(x, 0)
                assertNull("arrival $x m from the wall at ${walk.time}", walk.observation.detector.arrival)
                assertFalse(walk.observation.desired)
            }
        }
    }

    @Test fun shallowIndoorDwellCanConfirmAfterCrossingButAnIsolatedIndoorFixCannot() {
        // A 10 m-wide hall has nowhere to place an entire 7 m uncertainty radius.
        val narrow = rectangle("narrow", 0, 10, -30, 30)
        fun approach(walk: Walk) {
            for (x in listOf(-45, -35, -25, -17, -8)) walk.fix(x, 0, 7.0)
            assertFalse(walk.observation.desired)
        }
        val visit = Walk(listOf(narrow))
        approach(visit)
        repeat(6) { visit.fix(5, 0, 7.0) }
        assertEquals(narrow.id, visit.observation.detector.confirmedAreaId)
        assertTrue(visit.observation.desired)

        val passer = Walk(listOf(narrow))
        approach(passer)
        passer.fix(5, 0, 7.0)
        repeat(12) {
            passer.fix(-2, 0, 7.0)
            assertFalse("one stray point inside cannot authorize a stop outside", passer.observation.desired)
        }

        val strict = Walk(listOf(narrow.copy(requireStrongerEvidence = true)))
        approach(strict)
        repeat(8) { strict.fix(5, 0, 7.0) }
        assertFalse("strict mode still requires stronger evidence", strict.observation.desired)
    }

    @Test fun returningToTheObservedPavementReleasesAnIndoorVisitEvenWithinTheGpsRadius() {
        val walk = Walk()
        walk.approachOnPavement()
        walk.enterAndPray()
        walk.reachDoor()
        walk.fix(-5, 0)
        assertTrue("one exterior reading could be indoor drift", walk.observation.desired)
        for (y in -8 downTo -40 step 8) walk.fix(-5, y)
        assertFalse("returned along the incoming pavement at 5 m clearance with 8 m accuracy",
            walk.observation.desired)
        assertNull(walk.observation.detector.arrival)
    }

    private fun confirmedNarrowVisit(): Walk {
        val walk = Walk(listOf(rectangle("narrow", 0, 10, -90, 90)))
        walk.approachOnPavement(startY = -32)
        repeat(7) { walk.fix(5, 0, 7.0) }
        assertEquals(AdaptiveVisitDetector.Arrival.EDGE, walk.observation.detector.arrival)
        return walk
    }

    @Test fun shallowVisitInANarrowHallCanReturnToItsPavement() {
        val walk = confirmedNarrowVisit()
        repeat(6) { walk.fix(5, 0, 7.0, seconds = 55) }
        assertTrue(walk.observation.desired)

        for (y in 0 downTo -24 step 6) {
            walk.fix(5, y, 7.0)
            assertTrue("walking south inside the same narrow hall", walk.observation.desired)
        }
        walk.fix(-5, -24)
        assertTrue("an isolated exterior fix in the indoor walk", walk.observation.desired)
        for (y in -24..0 step 6) {
            walk.fix(5, y, 7.0)
            assertTrue("walking back to the door inside", walk.observation.desired)
        }
        walk.fix(-5, 0)
        assertTrue(walk.observation.desired)
        for (y in -8 downTo -40 step 8) walk.fix(-5, y)
        assertFalse("returning to the recorded pavement from a 10 m-wide hall", walk.observation.desired)
    }

    @Test fun oneShallowIndoorFixAfterLosingTheIndoorReferenceCannotAuthorizeAnExit() {
        val walk = confirmedNarrowVisit()
        repeat(3) { walk.fix(1, 0, 8.0, seconds = 45) }
        walk.fix(5, 0, 7.0)
        assertTrue(walk.observation.desired)
        for (y in 0 downTo -32 step 8) {
            walk.fix(-5, y)
            assertTrue("fresh shallow indoor support needs corroboration", walk.observation.desired)
        }
    }

    @Test fun aBalancedFixCanRediscoverThePavementAfterMissingTheDoorway() {
        val walk = Walk()
        walk.approachOnPavement(startY = -80)
        walk.enterAndPray()
        assertEquals(AdaptiveObservation.Sampling.BALANCED, walk.observation.sampling)
        // The visitor is already 24 m along the familiar path by the first exterior fix.
        walk.fix(-5, -24, seconds = 50)
        assertTrue(walk.observation.desired)
        assertEquals(AdaptiveObservation.Sampling.PRECISE, walk.observation.sampling)
        for (y in -32 downTo -56 step 8) walk.fix(-5, y)
        assertFalse("the missed doorway does not strand the phone in silent mode", walk.observation.desired)
    }

    @Test fun anImplausibleJumpFromAnotherWingOntoTheSamePavementCannotReleaseSilence() {
        val walk = Walk()
        walk.approachOnPavement(startY = -80)
        walk.enterAndPray()
        walk.fix(75, 55, 4.0, seconds = 60)
        walk.fix(-5, -24, seconds = 1)
        for (y in -32 downTo -56 step 8) {
            walk.fix(-5, y)
            assertTrue("the last reliable indoor position was in the other wing", walk.observation.desired)
        }
    }

    @Test fun indoorParallelWalkAndAlternatingWallJitterDoNotBecomeDepartures() {
        val walk = Walk()
        walk.approachOnPavement()
        walk.enterAndPray()
        walk.reachDoor()
        for (y in 0 downTo -40 step 8) {
            walk.fix(4, y)
            assertTrue("walking along the inside wall", walk.observation.desired)
        }
        for (y in -40..0 step 8) {
            walk.fix(-5, y)
            walk.fix(3, y)
            assertTrue("a return inside interrupts the exterior sequence", walk.observation.desired)
        }
    }

    @Test fun remainingNearTheDoorWithExteriorJitterDoesNotReestablishThePavementRoute() {
        val walk = Walk()
        walk.approachOnPavement()
        walk.enterAndPray()
        walk.reachDoor()
        for (y in listOf(0, -2, 0, -2, -1, -3, -1, 0, -2)) {
            walk.fix(-5, y)
            assertTrue("uncertain stationary location at the doorway", walk.observation.desired)
        }
    }

    @Test fun twoShortSightingsOfThePavementAcrossALongGpsGapDoNotProveDeparture() {
        val walk = Walk()
        walk.approachOnPavement()
        walk.enterAndPray()
        walk.reachDoor()
        walk.fix(-5, 0)
        walk.fix(-5, -8)
        walk.fix(-5, -32, seconds = 95)
        walk.fix(-5, -40)
        assertTrue("missing observations cannot count as time walking on the pavement",
            walk.observation.desired)
    }

    @Test fun anUnfamiliarNearWallRouteAndOneFarFixCannotBorrowDepartureTime() {
        val walk = Walk()
        walk.approachOnPavement()
        walk.enterAndPray()
        walk.reachDoor(y = 30)
        for (y in 30..70 step 8) {
            walk.fix(-5, y)
            assertTrue("unobserved end of the facade", walk.observation.desired)
        }
        walk.fix(-25, 70, 6.0, seconds = 15)
        assertTrue("one ordinary outside fix after an ambiguous path", walk.observation.desired)
        walk.fix(-25, 70, 6.0, seconds = 20)
        assertFalse(walk.observation.desired)
    }

    @Test fun startingInsideWithoutAnExteriorRouteStillUsesTheOrdinaryDeparture() {
        val walk = Walk()
        walk.enterAndPray()
        walk.reachDoor()
        for (y in 0 downTo -40 step 8) {
            walk.fix(-5, y)
            assertTrue("no independent exterior route to recognize", walk.observation.desired)
        }
        walk.fix(-25, -40, 6.0, seconds = 15)
        assertTrue(walk.observation.desired)
        walk.fix(-25, -40, 6.0, seconds = 20)
        assertFalse(walk.observation.desired)
    }

    @Test fun returningIntoAnotherEnabledAreaDoesNotReleaseTheVisit() {
        val adjacent = rectangle("adjacent", -40, -6, -48, 4)
        val walk = Walk(listOf(hall, adjacent))
        walk.approachOnPavement()
        walk.enterAndPray()
        walk.reachDoor()
        for (y in 0 downTo -40 step 8) {
            walk.fix(-7, y)
            assertTrue("another enabled mosque covers the returning track", walk.observation.desired)
        }
    }
}
