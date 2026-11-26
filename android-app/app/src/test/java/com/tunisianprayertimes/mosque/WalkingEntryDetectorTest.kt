package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.mosque.presence.AdaptiveObservation
import com.tunisianprayertimes.mosque.presence.AdaptiveVisitDetector
import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.PresenceFix
import com.tunisianprayertimes.mosque.presence.SilenceArea
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import org.junit.Assert.*
import org.junit.Test

class WalkingEntryDetectorTest {
    // An invented metre grid. The entrance of the 100 m by 80 m rectangle faces west at x = 0.
    private fun point(x: Int, y: Int) = GeoPoint(y / 110_574.0, x / 111_319.5)

    private fun rectangle(west: Int, east: Int, south: Int, north: Int) =
        listOf(point(west, south), point(east, south), point(east, north), point(west, north))

    private val boundary = SilencePolygon(rectangle(0, 100, -40, 40))
    private val hall = SilenceArea("hall", 1, listOf(boundary))

    private data class Sample(val sec: Int, val x: Int, val y: Int = 0, val accuracy: Double = 5.0) {
        val at get() = sec * 1_000L
    }

    private fun fix(s: Sample) = PresenceFix(point(s.x, s.y), s.accuracy, s.at)

    private fun observing(areas: List<SilenceArea> = listOf(hall), inWindow: Boolean = true) =
        AdaptiveObservation(areas, 0).also { it.start(0, forceBurst = true); it.period(inWindow, 0) }

    private fun feed(o: AdaptiveObservation, s: Sample) {
        assertTrue("expected a valid provider sample at ${s.sec}s", o.observe(fix(s), s.at))
    }

    private fun stillUnconfirmed(o: AdaptiveObservation, samples: List<Sample>) {
        for (s in samples) {
            feed(o, s)
            assertNull("confirmed at ${s.sec}s", o.detector.arrival)
            assertFalse("silencing at ${s.sec}s", o.desired)
        }
    }

    private val approach = listOf(Sample(0, -42), Sample(4, -31), Sample(8, -20))
    private val crossing = listOf(Sample(20, 2), Sample(22, 5), Sample(24, 9), Sample(26, 13), Sample(28, 17))

    @Test fun walkingAcrossTheEntranceConfirmsInsideTheWindowBeforeAnyStationaryDwell() {
        val o = observing()
        stillUnconfirmed(o, approach + crossing.dropLast(1))
        feed(o, crossing.last())
        assertEquals(AdaptiveVisitDetector.Arrival.WALKED_IN, o.detector.arrival)
        assertEquals(hall.id, o.detector.confirmedAreaId)
        assertTrue(o.desired)
    }

    @Test fun crossingBetweenSamplesCanConfirmWithoutAReadingOnTheDoorstep() {
        val o = observing()
        stillUnconfirmed(o, approach + Sample(20, 11) + Sample(23, 15) + Sample(26, 20))
        feed(o, Sample(29, 24))
        assertEquals(AdaptiveVisitDetector.Arrival.WALKED_IN, o.detector.arrival)
        assertTrue(o.desired)
    }

    @Test fun sixSecondFixesCanSettleAtTheSamePositionAfterMeasuredAdvanceThroughTheDoor() {
        val o = observing()
        val outside = listOf(Sample(0, -42), Sample(6, -32), Sample(12, -20))
        stillUnconfirmed(o, outside + Sample(18, 2) + Sample(24, 11))
        feed(o, Sample(30, 11))
        assertEquals(AdaptiveVisitDetector.Arrival.WALKED_IN, o.detector.arrival)
        assertTrue(o.desired)
    }

    @Test fun sparseBalancedOutsideFixesCanSeedWalkingEntryUntilTheApproachExpires() {
        val sparse = observing()
        val spacedApproach = listOf(Sample(0, -60), Sample(30, -35), Sample(58, -18))
        val entry = crossing.map { it.copy(sec = it.sec + 54) }
        stillUnconfirmed(sparse, spacedApproach + entry.dropLast(1))
        feed(sparse, entry.last())
        assertEquals(AdaptiveVisitDetector.Arrival.WALKED_IN, sparse.detector.arrival)

        val expired = observing()
        stillUnconfirmed(expired, approach + entry)
    }

    @Test fun anUnsampledTurnFromAParallelPathCanEstablishEntryThroughDifferentWalls() {
        val irregular = SilenceArea("south-door", 1, listOf(SilencePolygon(listOf(
            point(-40, 0), point(40, 0), point(36, 28), point(18, 65), point(-27, 49),
        ))))
        // More than half a minute of sampled travel along a wall shows no inward progress.
        // The turn and crossing occur between two successive location updates.
        val outside = listOf(Sample(0, -30, -28, 7.0), Sample(22, -31, -12, 7.0), Sample(43, -31, 0, 7.0))
        val inside = listOf(Sample(68, 2, 0, 6.0), Sample(70, 5, 2, 6.0), Sample(72, 9, 4, 6.0),
            Sample(74, 10, 7, 6.0), Sample(76, 10, 10, 6.0))
        for (area in listOf(hall, irregular)) {
            val samples = (outside + inside).map { s -> if (area == hall) s else s.copy(x = -s.y, y = s.x) }
            val o = observing(listOf(area))
            stillUnconfirmed(o, samples.dropLast(1))
            feed(o, samples.last())
            assertEquals(AdaptiveVisitDetector.Arrival.WALKED_IN, o.detector.arrival)
            assertEquals(area.id, o.detector.confirmedAreaId)
            assertTrue(o.desired)
        }
    }

    @Test fun aSingleReliableOutsideFixAndADirectIndoorSequenceRespectBothStrictModeAndPrayerTime() {
        // The first indoor sample is already well inside. The subsequent path runs parallel to the wall.
        val walk = listOf(Sample(0, -29), Sample(28, 16), Sample(34, 16, 6), Sample(41, 16, 12))
        val beforeWindow = observing(inWindow = false)
        stillUnconfirmed(beforeWindow, walk.dropLast(1))
        feed(beforeWindow, walk.last())
        assertEquals(AdaptiveVisitDetector.Arrival.WALKED_IN, beforeWindow.detector.arrival)
        assertFalse(beforeWindow.desired)
        beforeWindow.period(true, 43_000)
        assertTrue(beforeWindow.desired)
        beforeWindow.period(false, 44_000)
        assertFalse(beforeWindow.desired)
        stillUnconfirmed(observing(listOf(hall.copy(requireStrongerEvidence = true))), walk)
    }

    @Test fun sparseOutdoorEvidenceStillRequiresAnUnambiguousReachableAndRecentStartingPoint() {
        val inside = listOf(Sample(66, 2), Sample(71, 10), Sample(77, 14))
        val weakOrBroken = listOf(
            Sample(39, -14, accuracy = 7.0),  // The exterior fix must clear uncertainty plus 8 m.
            Sample(39, -32, accuracy = 12.0), // Too uncertain even though the centre is outside.
            Sample(3, -32),                   // No recent measurement before crossing.
            Sample(61, -76),                  // Impossible displacement within five seconds.
        )
        for (anchor in weakOrBroken) stillUnconfirmed(observing(), listOf(anchor) + inside)
        stillUnconfirmed(observing(), inside)
    }

    @Test fun anOutsideSnapshotFollowedByEdgeSkimmingOrImprovingPrecisionDoesNotSilence() {
        val outside = listOf(Sample(0, -29, -20), Sample(20, -30, -6))
        val skimming = listOf(Sample(40, 2, 0, 6.0), Sample(44, 5, 7, 6.0),
            Sample(49, 5, 15, 6.0), Sample(53, -3, 21, 6.0))
        // Better precision makes these last two centres look deep while the route stays parallel to the wall.
        val precisionChange = listOf(Sample(40, 6, 0, 7.0), Sample(44, 7, 6, 3.0), Sample(49, 7, 13, 3.0))
        for (passing in listOf(skimming, precisionChange)) stillUnconfirmed(observing(), outside + passing)
    }

    @Test fun recentInteriorEvidenceRequiresACorroboratedOutsideReturnBeforeAnotherWalkIn() {
        val recentlyInside = Sample(0, 18)
        val outside = Sample(12, -30)
        val entering = listOf(Sample(28, 2), Sample(32, 10), Sample(38, 16))
        stillUnconfirmed(observing(), listOf(recentlyInside, outside) + entering)

        val corroborated = observing()
        stillUnconfirmed(corroborated, listOf(recentlyInside, outside, Sample(18, -30)) + entering.dropLast(1))
        feed(corroborated, entering.last())
        assertEquals(AdaptiveVisitDetector.Arrival.WALKED_IN, corroborated.detector.arrival)
        assertTrue(corroborated.desired)
    }

    @Test fun sustainedAccurateInteriorWalkingConfirmsPresenceWhenObservationStartsIndoors() {
        val moving = listOf(Sample(0, 19, -15), Sample(5, 19, -7), Sample(10, 19, 1), Sample(15, 19, 9))
        val o = observing()
        stillUnconfirmed(o, moving.dropLast(1))
        feed(o, moving.last())
        assertEquals(AdaptiveVisitDetector.Arrival.INSIDE, o.detector.arrival)
        assertEquals(hall.id, o.detector.confirmedAreaId)
        assertTrue(o.desired)

        val strict = observing(listOf(hall.copy(requireStrongerEvidence = true)))
        stillUnconfirmed(strict, moving)
        for (sec in listOf(20, 25, 30, 35)) feed(strict, Sample(sec, 19, 9))
        assertEquals(AdaptiveVisitDetector.Arrival.INSIDE, strict.detector.arrival)
        assertTrue(strict.desired)
    }

    @Test fun repeatedSparseInteriorFixesCanConfirmPresenceWithoutARecordedEntrance() {
        // The third provider reading arrives as the initial precise request returns to balanced sampling.
        val inside = listOf(Sample(0, 24, -8), Sample(60, 31, 4), Sample(120, 42, 12),
            Sample(155, 53, 0), Sample(215, 47, -6), Sample(260, 35, 5))
        val o = observing()
        stillUnconfirmed(o, inside.take(2))
        for (s in inside.drop(2)) {
            feed(o, s)
            assertEquals("indoor evidence at ${s.sec}s", AdaptiveVisitDetector.Arrival.INSIDE, o.detector.arrival)
            assertEquals(hall.id, o.detector.confirmedAreaId)
            assertTrue(o.desired)
            assertFalse(o.fallback)
        }
    }

    @Test fun identicalSparseFixesWithEighteenMetreAccuracyNeedAmpleBoundaryClearance() {
        val fixes = listOf(Sample(0, 0, -12, 18.0), Sample(35, 1, 0, 18.0), Sample(88, 0, 13, 18.0))
        val spacious = hall.copy(id = "spacious", polygons = listOf(SilencePolygon(rectangle(-100, 100, -90, 90))))
        val narrow = hall.copy(id = "narrow", polygons = listOf(SilencePolygon(rectangle(-25, 25, -90, 90))))

        val o = observing(listOf(spacious))
        stillUnconfirmed(o, fixes.take(2))
        feed(o, fixes.last())
        assertEquals(AdaptiveVisitDetector.Arrival.INSIDE, o.detector.arrival)
        assertEquals(spacious.id, o.detector.confirmedAreaId)
        assertTrue(o.desired)
        assertFalse(o.fallback)

        // Centres are 24–25 m inside: one reported 18 m radius fits, but the sparse-evidence margin does not.
        stillUnconfirmed(observing(listOf(narrow)), fixes)
    }

    @Test fun sparseFixesSixteenMetresInsideQualifyOnBothSidesOfTenMetreAccuracy() {
        for (accuracy in listOf(9.9, 10.1)) {
            val o = observing()
            val fixes = listOf(Sample(0, 16, -10, accuracy), Sample(35, 16, 0, accuracy), Sample(85, 16, 12, accuracy))
            stillUnconfirmed(o, fixes.take(2))
            feed(o, fixes.last())
            assertEquals("accuracy $accuracy", AdaptiveVisitDetector.Arrival.INSIDE, o.detector.arrival)
            assertTrue(o.desired)
            assertFalse(o.fallback)
        }
    }

    @Test fun sparseEvidenceCannotBridgeAGapCourtyardOrOutsideFix() {
        val withCourtyard = hall.copy(polygons = listOf(SilencePolygon(boundary.exterior, listOf(rectangle(44, 56, -8, 8)))))
        val firstPair = listOf(Sample(0, 20), Sample(35, 28))
        val broken = listOf(
            listOf(Sample(175, 23)),                                  // First reading after a long gap.
            listOf(Sample(70, 50), Sample(105, 23), Sample(145, 26)), // Inside the excluded courtyard.
            listOf(Sample(70, -24), Sample(105, 23), Sample(145, 26)), // Clearly outside the outer wall.
        )
        for (interruption in broken) {
            stillUnconfirmed(observing(listOf(withCourtyard)), firstPair + interruption)
        }
    }

    @Test fun interiorWalkingEvidenceBreaksAtAnImplausibleStepUncertainFixOrLongGap() {
        val begin = listOf(Sample(0, 12, -15), Sample(5, 18, -7))
        val jump = listOf(Sample(10, 86, 25), Sample(15, 80, 19), Sample(20, 74, 13))
        val uncertain = listOf(Sample(10, 26, 0, 22.0), Sample(15, 35, 7), Sample(20, 43, 13))
        val gap = listOf(Sample(23, 28, 5), Sample(28, 36, 12), Sample(33, 44, 18))
        for (interruption in listOf(jump, uncertain, gap)) stillUnconfirmed(observing(), begin + interruption)
    }

    @Test fun confirmedWalkBeforePrayerOnlySilencesWhenTheWindowOpensAndEndsWithIt() {
        val o = observing(inWindow = false)
        for (s in approach + crossing) {
            feed(o, s)
            assertFalse(o.desired)
        }
        assertEquals(AdaptiveVisitDetector.Arrival.WALKED_IN, o.detector.arrival)
        o.period(true, 30_000)
        assertTrue(o.desired)
        o.period(false, 32_000)
        assertFalse(o.desired)
        o.period(true, 70_000)
        assertFalse("old arrival cannot open a subsequent window without fresh support", o.desired)
    }

    @Test fun strictMosqueWaitsForExistingDwellAndDepartureStillRequiresConsecutiveAwayReadings() {
        val strict = observing(listOf(hall.copy(requireStrongerEvidence = true)))
        stillUnconfirmed(strict, approach + crossing)
        listOf(Sample(32, 24), Sample(37, 24), Sample(42, 24), Sample(47, 24)).forEach { feed(strict, it) }
        assertEquals(AdaptiveVisitDetector.Arrival.INSIDE, strict.detector.arrival)
        assertTrue(strict.desired)

        val usual = observing()
        (approach + crossing).forEach { feed(usual, it) }
        assertTrue(usual.desired)
        for (s in listOf(Sample(39, -25), Sample(49, -26), Sample(58, -28))) {
            feed(usual, s)
            assertTrue("a measured exit needs a full 20 seconds", usual.desired)
        }
        feed(usual, Sample(59, -29))
        assertFalse(usual.desired)
        assertNull(usual.detector.arrival)
    }

    @Test fun walkingAlongTheWallOrBrieflyCuttingACornerDoesNotConfirm() {
        val alongWall = listOf(Sample(12, -12, -25), Sample(15, -4, -17),
            Sample(18, -3, -9), Sample(21, -3, 0), Sample(24, -3, 8), Sample(27, -4, 16),
            Sample(31, -8, 25), Sample(35, -18, 35))
        val cuttingCorner = listOf(Sample(12, -8, -28), Sample(15, 1, -31),
            Sample(18, 5, -34), Sample(21, 9, -38), Sample(24, 14, -42), Sample(27, 17, -47))
        for (pass in listOf(alongWall, cuttingCorner)) {
            stillUnconfirmed(observing(), approach.map { it.copy(y = pass.first().y) } + pass)
        }
    }

    @Test fun turningBackAcrossTheDoorwayClearsTheIncompleteEntry() {
        val turnBack = listOf(Sample(20, 2), Sample(22, 6), Sample(24, 9), Sample(26, 13),
            Sample(31, -2), Sample(34, 2), Sample(37, 9), Sample(39, 13), Sample(42, 17))
        stillUnconfirmed(observing(), approach + turnBack)
    }

    @Test fun aNearbyHomeAndAnAccuracyEstimateThatChangesWhileStationaryDoNotConfirm() {
        val stationary = observing()
        stillUnconfirmed(stationary, (0..100 step 5).map { Sample(it, -37, accuracy = 3.0) })
        val uncertain = observing()
        stillUnconfirmed(uncertain, (0..80 step 4).map { Sample(it, -7, accuracy = if (it < 40) 18.0 else 4.0) })
    }

    @Test fun poorAccuracyRepeatedPositionsAndAProviderGapDoNotTurnAnApproachIntoAWalkIn() {
        val imprecise = listOf(Sample(20, 15, accuracy = 12.0), Sample(23, 22, accuracy = 12.0),
            Sample(26, 27, accuracy = 12.0), Sample(29, 33, accuracy = 12.0))
        val lostPrecision = crossing.map { if (it.sec == 26) it.copy(accuracy = 35.0) else it } +
            listOf(Sample(30, 21), Sample(32, 25), Sample(34, 29))
        val frozenPosition = listOf(Sample(20, 23), Sample(23, 23), Sample(26, 23), Sample(29, 23))
        val gap = listOf(Sample(20, 2), Sample(23, 9), Sample(40, 15), Sample(43, 19), Sample(46, 22), Sample(49, 25))
        val jump = listOf(Sample(10, 55), Sample(13, 52), Sample(16, 49), Sample(19, 46))
        val uncertainAtDoor = listOf(Sample(20, 2), Sample(26, 11), Sample(30, 4), Sample(34, 12))
        for (samples in listOf(imprecise, lostPrecision, frozenPosition, gap, jump, uncertainAtDoor)) {
            stillUnconfirmed(observing(), approach + samples)
        }
    }

    @Test fun duplicateOutOfOrderMockAndStaleFixesCannotSupplyTheRequiredInsideSequence() {
        val detector = AdaptiveVisitDetector(listOf(hall))
        for (s in approach + Sample(20, 2) + Sample(24, 6)) assertTrue(detector.observe(fix(s), s.at, 0))
        for (s in listOf(Sample(24, 12), Sample(23, 15))) {
            assertFalse(detector.observe(fix(s), 25_000, 0))
        }
        assertFalse(detector.observe(fix(Sample(25, 13)).copy(isMock = true), 25_000, 0))
        assertTrue(detector.observe(fix(Sample(28, 17)), 28_000, 0))
        assertNull(detector.arrival)
        assertFalse(detector.observe(fix(Sample(30, 20)), 61_000, 0))
        assertNull(detector.arrival)
    }

    @Test fun crossingAnExcludedCourtyardBreaksEvidenceAndDisabledAreasCannotSupplyIt() {
        val courtyard = hall.copy(polygons = listOf(SilencePolygon(boundary.exterior, listOf(rectangle(14, 20, -8, 8)))))
        val o = observing(listOf(courtyard, hall.copy(id = "disabled", enabled = false)))
        val throughHole = listOf(Sample(20, 2), Sample(23, 9), Sample(26, 17), Sample(29, 22),
            Sample(31, 26), Sample(33, 30), Sample(35, 34), Sample(38, 38))
        stillUnconfirmed(o, approach + throughHole)
    }

    @Test fun eachAdjacentMosqueNeedsItsOwnOutdoorEvidence() {
        val westHall = hall.copy(id = "west-hall", polygons = listOf(SilencePolygon(rectangle(0, 100, -10, 10))))
        fun northHall(south: Int) = hall.copy(id = "north-hall", polygons = listOf(SilencePolygon(rectangle(-100, 100, south, 80))))
        val changeDirection = listOf(Sample(20, 2), Sample(23, 9), Sample(26, 13),
            Sample(36, 15, 34), Sample(39, 16, 37), Sample(42, 17, 40), Sample(45, 18, 43))
        val walk = approach + changeDirection
        // These outside samples approach the west hall. They stay 12 m from the nearer north wall.
        stillUnconfirmed(observing(listOf(westHall, northHall(12))), walk)
        // At 24 m the north hall has its own clear outside fix, then a genuine measured crossing.
        val separated = observing(listOf(westHall, northHall(24)))
        stillUnconfirmed(separated, walk.dropLast(1))
        feed(separated, walk.last())
        assertEquals(AdaptiveVisitDetector.Arrival.WALKED_IN, separated.detector.arrival)
        assertEquals("north-hall", separated.detector.confirmedAreaId)
        assertTrue(separated.desired)
    }
}
