package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.mosque.presence.AdaptiveObservation
import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.PresenceFix
import com.tunisianprayertimes.mosque.presence.SilenceArea
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisionalArrivalTest {
    private fun point(x: Int, y: Int) = GeoPoint(y / 110_574.0, x / 111_319.5)

    private val hall = SilenceArea("hall", 1, listOf(SilencePolygon(listOf(
        point(0, -75), point(80, -75), point(80, 75), point(0, 75),
    ))))

    private data class Sample(val seconds: Int, val x: Int, val y: Int, val accuracy: Double)

    // The x=0 wall faces the pavement. Both approach readings are well outside,
    // while the second has a slightly larger than 10 m uncertainty radius.
    private val approach = listOf(Sample(0, -30, -40, 8.0), Sample(27, -26, -22, 11.0))

    // Walk 13's accuracy and wall clearances on an invented straight doorway.
    private val outdoor = Sample(0, -30, -30, 8.7)
    private val doorway = listOf(Sample(27, 3, -3, 10.94), Sample(28, 3, -2, 10.86), Sample(30, 4, 0, 10.16))
    private val inside = Sample(32, 5, 2, 8.7)

    private fun observing(strict: Boolean = false, inWindow: Boolean = true,
        areas: List<SilenceArea> = listOf(hall)) =
        AdaptiveObservation(areas.map { it.copy(requireStrongerEvidence = strict) }, 0).also {
            it.start(0, forceBurst = true)
            it.period(inWindow, 0)
        }

    private fun AdaptiveObservation.feed(sample: Sample) {
        val at = sample.seconds * 1_000L
        assertTrue(observe(PresenceFix(point(sample.x, sample.y), sample.accuracy, at), at))
    }

    private fun AdaptiveObservation.expectUnconfirmed(samples: List<Sample>, silent: Boolean) {
        for (sample in samples) {
            feed(sample)
            assertNull("unexpected confirmed arrival at ${sample.seconds} s", detector.arrival)
            assertEquals("silence at ${sample.seconds} s", silent, desired)
        }
    }

    @Test fun borderlineDoorwayReadingsPreserveTheApproachUntilAnAccurateCrossing() {
        val observation = observing()
        observation.expectUnconfirmed(listOf(outdoor) + doorway, silent = false)
        observation.expectUnconfirmed(listOf(inside), silent = true)
        assertEquals(77_000L, observation.detector.tentativeUntil)

        // A passerby who brushes the doorway and turns back is released on the first clear exterior fix.
        observation.expectUnconfirmed(listOf(Sample(39, -12, 6, 6.0)), silent = false)
    }

    @Test fun uncertainDoorwayReadingsCannotCreateOrRefreshAnExteriorAnchor() {
        observing().expectUnconfirmed(doorway + inside, silent = false)
        val lateDoorway = doorway.map { it.copy(seconds = it.seconds + 29) }
        observing().expectUnconfirmed(listOf(outdoor) + lateDoorway + inside.copy(seconds = 61), silent = false)
    }

    @Test fun anImplausibleJumpBetweenUncertainDoorwayFixesInvalidatesTheApproach() {
        // Each indoor point is reachable from the original fix, but a 63 m side-step in one second is not.
        val jump = doorway[1].copy(y = 60)
        val readings = listOf(outdoor, doorway.first(), jump, inside.copy(y = 60))
        observing().expectUnconfirmed(readings, silent = false)
    }

    @Test fun aCoarseFixOrAnUncertaintyCircleFullyInsideCannotBridgeTheCrossing() {
        for (interruption in listOf(doorway[1].copy(accuracy = 20.0), doorway[1].copy(x = 16))) {
            observing().expectUnconfirmed(listOf(outdoor, doorway.first(), interruption, inside), silent = false)
        }
    }

    @Test fun crossingACornerSurvivesOneAmbiguousOutsideFixAndReleasesImmediatelyWhenClearlyOutside() {
        val observation = observing()
        observation.expectUnconfirmed(approach, silent = false)
        observation.expectUnconfirmed(listOf(
            Sample(50, 1, 54, 9.0), Sample(53, 4, 62, 8.0), Sample(56, 7, 69, 7.0),
            Sample(58, 8, 73, 7.5),
        ), silent = true)
        // Two metres outside at y=77, then one metre back inside. Neither is a fresh exterior anchor.
        observation.expectUnconfirmed(listOf(Sample(61, 10, 77, 7.0), Sample(64, 8, 74, 7.0)), silent = true)
        observation.expectUnconfirmed(listOf(Sample(75, -26, 74, 6.0)), silent = false)
        observation.expectUnconfirmed(listOf(Sample(88, 1, 70, 7.0)), silent = true)
    }

    @Test fun improvingAccuracyOnAParallelTrackDoesNotConfirmTheTemporaryVisit() {
        val observation = observing()
        observation.expectUnconfirmed(approach, silent = false)
        observation.expectUnconfirmed(listOf(
            Sample(50, 8, 0, 9.5), Sample(52, 8, 4, 8.5), Sample(54, 8, 8, 7.0),
            Sample(56, 8, 12, 6.5), Sample(58, 8, 16, 6.0), Sample(60, 8, 20, 5.0),
            Sample(62, 8, 24, 4.5), Sample(64, 8, 28, 4.0),
        ), silent = true)
        observation.expectUnconfirmed(listOf(Sample(66, -2, 31, 6.0)), silent = true)
        observation.expectUnconfirmed(listOf(Sample(70, -3, 38, 6.0)), silent = false)
    }

    private val narrow = SilenceArea("narrow", 1, listOf(SilencePolygon(listOf(
        point(0, -120), point(10, -120), point(10, 120), point(0, 120),
    ))))

    @Test fun aSparseApproachAndOneAmbiguousReversalStillConfirmASeatedVisitor() {
        val observation = observing(areas = listOf(narrow))
        observation.expectUnconfirmed(approach, silent = false)
        observation.expectUnconfirmed(listOf(Sample(50, 2, 0, 7.0), Sample(52, -1, 0, 9.0)), silent = true)
        for (at in 54..82 step 2) {
            observation.feed(Sample(at, 5, 0, 8.0))
            assertTrue("silence at ${at}s", observation.desired)
        }
        assertEquals(narrow.id, observation.detector.confirmedAreaId)
        for (at in 112..412 step 30) {
            observation.feed(Sample(at, 5, 0, 8.0))
            assertTrue(observation.desired)
        }
    }

    @Test fun slowIndoorUpdatesCanConfirmAStopButCannotExtendTemporarySilence() {
        val observation = observing(areas = listOf(narrow))
        observation.expectUnconfirmed(approach, silent = false)
        observation.expectUnconfirmed(listOf(Sample(50, 5, 0, 8.0), Sample(80, 5, 0, 8.0)), silent = true)
        observation.tick(95_000)
        assertFalse(observation.desired)
        observation.feed(Sample(110, 5, 0, 8.0))
        assertEquals(narrow.id, observation.detector.confirmedAreaId)
        assertTrue(observation.desired)
    }

    @Test fun aNewShallowStopAfterTemporarySilenceExpiresCannotBorrowAnOlderCrossing() {
        val observation = observing(areas = listOf(narrow))
        observation.expectUnconfirmed(approach, silent = false)
        observation.expectUnconfirmed(listOf(Sample(50, 5, 0, 8.0), Sample(80, 5, 1, 8.0)), true)
        observation.expectUnconfirmed(listOf(Sample(102, 5, 30, 8.0), Sample(117, 5, 30, 8.0),
            Sample(132, 5, 30, 8.0)), false)
    }

    @Test fun aCrossingCannotConfirmAStopAfterAnImpossibleJumpOrProviderOutage() {
        val crossing = listOf(Sample(50, 5, 0, 8.0))
        for (later in listOf(
            (52..100 step 2).map { Sample(it, 5, 98, 8.0) },
            (130..170 step 2).map { Sample(it, 5, 0, 8.0) },
        )) {
            val observation = observing(areas = listOf(narrow))
            observation.expectUnconfirmed(approach, silent = false)
            observation.expectUnconfirmed(crossing, silent = true)
            for (sample in later) {
                observation.feed(sample)
                assertNull("unsupported stop at ${sample.seconds}s", observation.detector.arrival)
            }
            assertFalse(observation.desired)
        }
    }

    @Test fun aSamplingBurstEndingBeforeTheDoorPreservesAFreshExteriorAnchor() {
        val observation = observing()
        observation.expectUnconfirmed(listOf(Sample(95, -32, 0, 8.0), Sample(115, -19, 0, 7.0)), false)
        observation.tick(120_000)
        observation.expectUnconfirmed(listOf(Sample(126, 2, 0, 7.0)), true)
    }

    @Test fun overlappingCrossingsCannotRenewTemporarySilence() {
        val other = SilenceArea("other", 1, listOf(SilencePolygon(listOf(
            point(-20, 0), point(20, 0), point(20, 120), point(-20, 120),
        ))))
        val observation = observing(areas = listOf(narrow, other))
        observation.expectUnconfirmed(listOf(Sample(0, -30, -50, 7.0)), false)
        observation.expectUnconfirmed(listOf(Sample(25, 1, -30, 7.0), Sample(43, 1, 2, 7.0),
            Sample(48, -8, 2, 6.0)), true)
        assertEquals(70_000L, observation.detector.tentativeUntil)
        observation.tick(70_000)
        assertFalse(observation.desired)
    }

    @Test fun anOutageOrUnusableFixBetweenAmbiguousOutsideReadingsCannotCorroborateAReversal() {
        val observation = observing()
        observation.expectUnconfirmed(approach, silent = false)
        observation.expectUnconfirmed(listOf(Sample(50, 2, 0, 7.0), Sample(52, -1, 0, 9.0),
            Sample(68, -1, 0, 9.0), Sample(70, -1, 0, 60.0), Sample(73, -2, 0, 9.0)), true)
        val missingAccuracy = PresenceFix(point(0, 0), null, 75_000L)
        assertFalse(observation.observe(missingAccuracy, 75_000L))
        observation.expectUnconfirmed(listOf(Sample(78, -2, 0, 9.0)), true)
        observation.expectUnconfirmed(listOf(Sample(82, -2, 0, 9.0)), false)
    }

    @Test fun noFurtherFixesExpireTemporarySilenceAndWeakInteriorReadingsCannotRearmIt() {
        val observation = observing()
        observation.expectUnconfirmed(approach, silent = false)
        observation.expectUnconfirmed(listOf(Sample(50, 1, 0, 9.0), Sample(55, 2, 7, 8.0)), silent = true)
        observation.tick(94_999)
        assertTrue(observation.desired)
        observation.tick(95_000)
        assertFalse(observation.desired)
        observation.expectUnconfirmed(listOf(Sample(99, 2, 13, 8.0), Sample(105, 4, 20, 7.0)), silent = false)
    }

    @Test fun anUncertainFixOnTheDoorstepCannotReplaceAnExpiredExteriorAnchor() {
        val ambiguousApproach = listOf(approach.first(), Sample(27, -3, -22, 11.0))
        observing().expectUnconfirmed(ambiguousApproach + listOf(
            Sample(62, 1, 0, 7.0), Sample(65, 7, 4, 6.0),
            Sample(68, 12, 8, 5.0), Sample(71, 16, 12, 5.0),
        ), silent = false)
    }

    @Test fun strictMosquesAndThePrePrayerObservationPeriodCannotStartTemporarySilence() {
        val track = approach + listOf(
            Sample(50, 1, 0, 9.0), Sample(52, 5, 4, 8.0), Sample(54, 9, 8, 8.0),
            Sample(56, 9, 12, 8.0), Sample(58, 8, 16, 7.5), Sample(60, 9, 20, 7.5),
            Sample(62, 9, 24, 7.5),
        )
        observing(strict = true).expectUnconfirmed(track, silent = false)
        observing(inWindow = false).expectUnconfirmed(track, silent = false)
    }

    @Test fun theWindowEndingCutsTemporarySilenceShort() {
        val observation = observing()
        observation.expectUnconfirmed(approach, silent = false)
        observation.expectUnconfirmed(listOf(Sample(50, 1, 0, 9.0)), silent = true)
        observation.period(false, 51_000)
        assertFalse(observation.desired)
    }
}
