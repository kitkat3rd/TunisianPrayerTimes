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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveDepartureSamplingTest {
    private fun point(x: Int, y: Int = 0) = GeoPoint(y / 110_574.0, x / 111_319.5)

    private fun area(id: String, west: Int, east: Int) = SilenceArea(id, 1, listOf(SilencePolygon(listOf(
        point(west, -40), point(east, -40), point(east, 40), point(west, 40),
    ))))

    private val hall = area("hall", -60, 0)

    private fun AdaptiveObservation.fix(at: Long, x: Int, accuracy: Double = 6.0) {
        assertTrue(observe(PresenceFix(point(x), accuracy, at), at))
    }

    private fun seated(vararg otherAreas: SilenceArea): AdaptiveObservation {
        val observation = AdaptiveObservation(listOf(hall) + otherAreas, 0)
        observation.period(true, 0)
        observation.start(0)
        for (at in listOf(1_000L, 9_000L, 17_000L)) observation.fix(at, -20)
        assertEquals(hall.id, observation.detector.confirmedAreaId)
        assertTrue(observation.desired)
        assertEquals(Sampling.BALANCED, observation.sampling)
        return observation
    }

    @Test fun persistentlyCoarseExteriorFixesGetThreeShortChecksAcrossFourteenMinutes() {
        val observation = seated()
        for (at in 45_000L..885_000L step 5_000L) {
            observation.fix(at, 60, accuracy = 45.0)
            val shouldProbe = (at - 45_000L) % 300_000L < 30_000L
            assertEquals("sampling at $at", if (shouldProbe) Sampling.PRECISE else Sampling.BALANCED, observation.sampling)
            assertNull(observation.detector.outsideSince)
            assertTrue(observation.desired)
        }
    }

    @Test fun scheduleFallbackNeedsFreshExteriorReadingsWhenLocationReturnsNearTheHall() {
        val observation = AdaptiveObservation(listOf(hall), 0)
        observation.locationDisabled(true, 0)
        observation.period(true, 0)
        observation.locationDisabled(false, 1_000)
        for (at in 5_000L..25_000L step 5_000) {
            observation.fix(at, 60, accuracy = 45.0)
            assertTrue(observation.fallback)
        }
        observation.fix(30_000, 60)
        assertFalse(observation.observe(PresenceFix(point(60), null, 36_000), 36_000))
        for (at in 40_000L..55_000L step 5_000) {
            observation.fix(at, 60)
            assertTrue(observation.desired)
        }
        observation.fix(60_000, 60)
        assertFalse(observation.desired)
    }

    @Test fun usableExteriorEvidenceBypassesTheCoarseRetryDelayAndRestoresSound() {
        val observation = seated()
        observation.fix(45_000, 60, accuracy = 45.0)
        observation.tick(75_000)
        assertEquals(Sampling.BALANCED, observation.sampling)
        observation.fix(105_000, 60)
        assertEquals(Sampling.PRECISE, observation.sampling)
        for (at in 110_000L..125_000L step 5_000L) observation.fix(at, 60)
        assertEquals(Departure.CLEAR_OUTSIDE, observation.detector.departureOnLastFix)
        assertFalse(observation.desired)
    }

    @Test fun intermittentIndoorFixesAndFenceTransitionsCannotRefillTheCoarseProbeBudget() {
        val observation = seated()
        observation.fix(45_000, 60, accuracy = 45.0)
        observation.tick(75_000)
        observation.fix(105_000, -20)
        for ((index, event) in listOf(1, 2, 4, 1, 2).withIndex()) {
            observation.geofence(110_000L + index * 5_000, event)
            assertEquals(Sampling.BALANCED, observation.sampling)
        }
        observation.fix(165_000, 60, accuracy = 45.0)
        assertEquals(Sampling.BALANCED, observation.sampling)
        assertTrue(observation.desired)
    }

    @Test fun staleSupportCannotRepeatedlyRunLongChecksOnCoarseFixesOverlappingTheBoundary() {
        val observation = seated()
        for (at in 45_000L..885_000L step 5_000L) {
            observation.fix(at, 40, accuracy = 45.0)
            val shouldProbe = at in 200_000L until 320_000L ||
                at in 500_000L until 530_000L || at in 800_000L until 830_000L
            assertEquals("sampling at $at", if (shouldProbe) Sampling.PRECISE else Sampling.BALANCED, observation.sampling)
            assertTrue(observation.desired)
        }
    }

    @Test fun shallowIndoorReturnCancelsTheCheckAndDoorwayJitterKeepsSilence() {
        val observation = seated()
        observation.fix(45_000, 2)
        assertEquals(Sampling.PRECISE, observation.sampling)
        assertNull(observation.detector.outsideSince)
        assertTrue(observation.desired)

        observation.fix(48_000, -1, accuracy = 31.0)
        assertEquals(Sampling.PRECISE, observation.sampling)
        observation.fix(50_000, -1)
        assertEquals(Sampling.BALANCED, observation.sampling)
        for ((i, x) in listOf(3, -2, 4, -3, 2).withIndex()) {
            observation.fix(55_000L + 5_000L * i, x)
            assertEquals(Sampling.BALANCED, observation.sampling)
            assertTrue(observation.desired)
        }
    }

    @Test fun shallowExteriorFixesKeepTheCheckRunningEvenWhileIndoorEvidenceStillSupportsSilence() {
        val observation = seated()
        for (at in 25_000L..40_000L step 5_000L) observation.fix(at, -7)
        observation.fix(42_000, 1)
        observation.fix(44_000, 1)
        assertEquals(44_000L, observation.detector.lastSupport)
        assertEquals(Sampling.PRECISE, observation.sampling)
        assertTrue(observation.desired)
        observation.fix(46_000, -1)
        assertEquals(Sampling.BALANCED, observation.sampling)
    }

    @Test fun lingeringJustOutsideCannotRenewTheCheckOrReleaseSilence() {
        val observation = seated()
        observation.fix(45_000, 2)
        assertEquals(Sampling.PRECISE, observation.sampling)
        val end = requireNotNull(observation.requestEnd)
        assertEquals(75_000L, end)
        for (at in 50_000L until end step 5_000L) {
            observation.fix(at, 3)
            assertEquals(end, observation.requestEnd)
        }
        observation.tick(end)
        observation.fix(end + 1_000L, 4)
        assertEquals(Sampling.BALANCED, observation.sampling)
        assertNull(observation.detector.outsideSince)
        assertTrue(observation.desired)
        observation.fix(160_000L, 2)
        assertEquals(Sampling.BALANCED, observation.sampling)
        observation.fix(165_000L, 2)
        assertEquals(Sampling.PRECISE, observation.sampling)
        assertTrue(observation.desired)
    }

    @Test fun clearDepartureGetsTheFullExitCheckEvenLateInTheShortBurst() {
        val observation = seated()
        observation.fix(45_000, 2)
        assertEquals(Sampling.PRECISE, observation.sampling)
        val end = requireNotNull(observation.requestEnd)
        val firstClear = end - 1_000L
        observation.fix(firstClear, 22)
        observation.tick(end)
        assertEquals(Sampling.PRECISE, observation.sampling)
        observation.fix(firstClear + 15_000L, 28)
        assertTrue(observation.desired)
        observation.fix(firstClear + 20_000L, 29)
        assertEquals(Departure.CLEAR_OUTSIDE, observation.detector.departureOnLastFix)
        assertFalse(observation.desired)
    }

    @Test fun clearDepartureBypassesTheDoorwayJitterCooldown() {
        val observation = seated()
        observation.fix(45_000, 2)
        observation.fix(50_000, -1)
        observation.fix(55_000, 3)
        assertEquals(Sampling.BALANCED, observation.sampling)
        observation.fix(60_000, 22)
        assertEquals(Sampling.PRECISE, observation.sampling)
        assertTrue(observation.desired)
        observation.fix(70_000, 25)
        observation.fix(80_000, 27)
        assertEquals(Departure.CLEAR_OUTSIDE, observation.detector.departureOnLastFix)
        assertFalse(observation.desired)
    }

    @Test fun aNewConfirmedVisitGetsItsOwnDepartureCheck() {
        val observation = seated()
        observation.fix(45_000, 2)
        observation.fix(50_000, 22)
        observation.fix(60_000, 24)
        observation.fix(70_000, 25)
        assertFalse(observation.desired)
        for (at in listOf(85_000L, 93_000L, 101_000L)) observation.fix(at, -20)
        assertTrue(observation.desired)
        assertEquals(Sampling.BALANCED, observation.sampling)
        observation.fix(115_000, 2)
        assertEquals(Sampling.PRECISE, observation.sampling)
        assertTrue(observation.desired)
    }

    @Test fun theFixMustBeUsableAndItsCentreBeyondEverySavedPolygon() {
        val observation = seated(area("annex", 5, 45))
        observation.fix(45_000, 0)
        assertEquals(Sampling.BALANCED, observation.sampling)
        observation.fix(50_000, 3, accuracy = 31.0)
        assertEquals(Sampling.BALANCED, observation.sampling)
        observation.fix(55_000, 7)
        assertEquals(Sampling.BALANCED, observation.sampling)
        observation.fix(60_000, 3, accuracy = 30.0)
        assertEquals(Sampling.PRECISE, observation.sampling)
        assertTrue(observation.desired)
    }

    @Test fun twoClearOutsideFixesSeparatedByAnOutageStartANewSequence() {
        val observation = seated()
        observation.fix(45_000, 27)
        observation.fix(88_000, 28)
        assertEquals(88_000L, observation.detector.outsideSince)
        observation.fix(98_000, 28)
        assertTrue(observation.desired)
        observation.fix(108_000, 28)
        assertEquals(Departure.CLEAR_OUTSIDE, observation.detector.departureOnLastFix)
        assertFalse(observation.desired)
    }

    @Test fun unusableIndoorFixesBreakAnOtherwiseContinuousExteriorSequence() {
        val observation = seated()
        observation.fix(45_000, 27)
        observation.fix(55_000, 27)
        for (at in 56_000L..62_000L step 2_000L) observation.fix(at, -10, accuracy = 120.0)
        observation.fix(65_000, 28)
        assertFalse(observation.observe(PresenceFix(point(-10), null, 67_000L), now = 67_000L))
        observation.fix(75_000, 28)
        observation.fix(85_000, 28)
        assertTrue(observation.desired)
        observation.fix(95_000, 28)
        assertEquals(Departure.CLEAR_OUTSIDE, observation.detector.departureOnLastFix)
    }

    @Test fun anUnfamiliarExitCanBeConfirmedByLingeringJustBeyondTheAccuracyCircle() {
        val observation = seated()
        for (at in listOf(45_000L, 60_000L, 75_000L, 89_999L)) {
            observation.fix(at, 9, accuracy = 5.0)
            assertTrue(observation.desired)
            assertEquals(Sampling.PRECISE, observation.sampling)
        }
        observation.fix(90_000, 9, accuracy = 5.0)
        assertEquals(Departure.SUSTAINED_OUTSIDE, observation.detector.departureOnLastFix)
        assertFalse(observation.desired)
    }

    @Test fun exteriorPointsOnOppositeSidesOfTheBuildingDoNotProveAContinuousExit() {
        val observation = seated()
        for ((i, x) in listOf(9, -69, 9, -69, 9, -69, 9).withIndex()) {
            observation.fix(45_000L + i * 10_000L, x, accuracy = 5.0)
            assertTrue(observation.desired)
        }
    }

    @Test fun implausibleJumpsAlongTheOutsideOfALongWallDoNotProveAMarginalExit() {
        val observation = seated(area("long hall", -300, 0))
        for (i in 0..10) {
            val x = if (i % 2 == 0) -20 else -220
            val at = 45_000L + i * 5_000L
            assertTrue(observation.observe(PresenceFix(point(x, 49), 5.0, at), at))
            assertTrue(observation.desired)
        }
    }

    @Test fun aDelayedBatchNeedsAFreshExteriorFixBeforeReleasing() {
        val observation = seated()
        for (at in listOf(45_000L, 55_000L, 65_000L)) {
            assertTrue(observation.observe(PresenceFix(point(27), 6.0, at), now = at + 20_000L))
            assertTrue(observation.desired)
        }
        assertTrue(observation.observe(PresenceFix(point(28), 6.0, 75_000L), now = 85_000L))
        assertEquals(Departure.CLEAR_OUTSIDE, observation.detector.departureOnLastFix)
        assertFalse(observation.desired)
    }
}
