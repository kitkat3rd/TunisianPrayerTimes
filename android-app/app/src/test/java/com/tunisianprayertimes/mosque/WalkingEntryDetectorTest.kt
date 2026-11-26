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

    @Test fun approachingOneMosqueCannotSupplyWalkingEvidenceForAnAdjacentMosque() {
        val westHall = hall.copy(id = "west-hall", polygons = listOf(SilencePolygon(rectangle(0, 100, -12, 12))))
        val northHall = hall.copy(id = "north-hall", polygons = listOf(SilencePolygon(rectangle(-100, 100, 24, 80))))
        // The same initial samples get nearer the west wall and stay at a fixed distance from the north wall.
        val changeDirection = listOf(Sample(20, 2), Sample(23, 9), Sample(26, 13),
            Sample(36, 15, 34), Sample(39, 16, 37), Sample(42, 17, 40), Sample(45, 18, 43))
        stillUnconfirmed(observing(listOf(westHall, northHall)), approach + changeDirection)
    }
}
