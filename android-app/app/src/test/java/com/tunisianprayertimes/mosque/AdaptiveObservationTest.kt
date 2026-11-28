package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.mosque.presence.*
import org.junit.Assert.*
import org.junit.Test

class AdaptiveObservationTest {
    private val rows = requireNotNull(javaClass.getResourceAsStream("/mosque-adaptive-megrine.txt"))
        .bufferedReader().useLines { lines -> lines.map { it.split(' ') }.toList() }.groupBy { it.first() }
    private fun point(x: Double, y: Double) = GeoPoint(36.76868 - y / 110_972.0, 10.22545 + x / 89_273.0)
    private val polygon = SilencePolygon(rows.getValue("boundary").map { point(it[1].toDouble(), it[2].toDouble()) })
    private fun areas(strict: Boolean = false) = listOf(SilenceArea("TN01775", 1, listOf(polygon), requireStrongerEvidence = strict))
    private fun fix(t: Long, x: Double = 2.0, y: Double = -3.0, a: Double = 4.0) = PresenceFix(point(x,y), a, t)

    @Test fun allSixteenWalksUseTheProductionDecisionUnderBothPolicies() {
        for (strict in listOf(false, true)) {
            val expected = mapOf("precise" to 60, "coarse" to null, "adjacent" to null, "inside" to 60, "door" to null,
                "jitter" to null, "shifted" to null, "opposite" to null,
                "pass" to null, "frontage" to null, "stationary" to null, "gap" to null, "wait" to null,
                "noHistory" to null, "accurateOpposite" to null, "indoorUnclear" to null)
            assertEquals(expected.keys, rows.keys - "boundary")
            for ((name, first) in expected) {
                val machine = AdaptiveObservation(areas(strict), 0)
                machine.start(0, forceBurst = true)
                val readings = rows.getValue(name).associateBy { it[1].toInt() }
                val on = mutableListOf<Int>()
                for (t in (readings.keys + 60 + 120).sorted()) {
                    val now = t * 1_000L
                    machine.period(t in 60 until 120, now)
                    readings[t]?.let { machine.observe(fix(now, it[2].toDouble(), it[3].toDouble(), it[4].toDouble()), now) }
                    machine.tick(now)
                    if (machine.desired) on += t
                }
                assertEquals("$name strict=$strict", first, on.firstOrNull())
                assertFalse(120 in on)
                if (name == "precise") assertFalse(115 in on)
            }
        }
    }

    @Test fun onlyAnAcceptedRequestCanEstablishAnOutageAndRetriesShareItsDeadline() {
        val m = AdaptiveObservation(areas(),0)
        m.period(true,0); m.start(0); m.tick(240_000); assertFalse(m.desired)
        m.accepted(m.generation-1,240_000); m.tick(350_000); assertFalse(m.desired)
        m.accepted(m.generation,350_000); val generation = m.generation
        m.tick(395_000); assertTrue(generation != m.generation)
        m.accepted(m.generation,396_000)
        m.tick(439_999); assertFalse(m.desired)
        m.tick(440_000); assertTrue(m.desired); assertTrue(m.fallback)
        m.period(false,440_001); assertFalse(m.desired)
    }

    @Test fun validButImpreciseAndFarReadingsDoNotSilenceAtHome() {
        val m = AdaptiveObservation(areas(),0)
        m.wakeCoverageUntil = 300_000
        m.period(true,0); m.start(0); m.accepted(m.generation,0)
        m.observe(fix(30_000, 2_000.0, 10.0, 160.0),30_000)
        m.tick(240_000); assertFalse(m.desired); assertFalse(m.fallback)
        assertEquals(AdaptiveObservation.Sampling.PARKED,m.sampling)
    }

    @Test fun hardOutageAndRecoveryRespectWindowAndRequireSustainedOutside() {
        val m = AdaptiveObservation(areas(),0)
        m.locationDisabled(true,0); assertFalse(m.desired)
        m.period(true,60_000); assertTrue(m.desired)
        m.locationDisabled(false,61_000); m.accepted(m.generation,61_000)
        m.observe(fix(62_000,1_000.0,0.0,70.0),62_000); assertTrue(m.desired)
        m.observe(fix(105_000,1_000.0,0.0,70.0),105_000); assertTrue(m.desired)
        m.observe(fix(115_000,1_000.0,0.0,70.0),115_000); assertTrue(m.desired)
        m.observe(fix(125_000,1_000.0,0.0,70.0),125_000); assertFalse(m.desired)
    }

    @Test fun validUncertainRecoveryExpiresAndPreWindowTimeoutMustBeRechecked() {
        val m = AdaptiveObservation(areas(),0)
        m.start(0); m.accepted(m.generation,0); m.tick(90_000); assertFalse(m.desired)
        m.period(true,100_000); m.accepted(m.generation,100_000); m.tick(190_000); assertTrue(m.desired)
        m.start(191_000,true); m.accepted(m.generation,191_000)
        for (t in 191_000L..311_000L step 5_000) m.observe(fix(t,a=70.0),t)
        assertFalse(m.desired)
    }

    @Test fun cachedMockAndDuplicateReadingsCannotBuildArrival() {
        val d = AdaptiveVisitDetector(areas())
        assertFalse(d.observe(fix(0),31_000,0))
        assertFalse(d.observe(fix(32_001),32_000,0))
        assertFalse(d.observe(fix(32_000).copy(isMock=true),32_000,0))
        assertTrue(d.observe(fix(33_000),33_000,0))
        assertFalse(d.observe(fix(33_000),42_000,0))
        assertFalse(d.observe(fix(32_999),43_000,0))
        assertNull(d.arrival)
    }

    @Test fun aHoleRemainsExcludedAndNearbyIsLocalToOneComponent() {
        val outer = listOf(point(-100.0,-100.0),point(100.0,-100.0),point(100.0,100.0),point(-100.0,100.0))
        val withHole = SilencePolygon(outer, listOf(polygon.exterior))
        val d = AdaptiveVisitDetector(listOf(SilenceArea("hole",1,listOf(withHole))))
        for (t in 0L..80_000 step 5_000) d.observe(fix(t),t,0)
        assertNull(d.arrival)
    }

    @Test fun alreadySeatedWorshipperIsRecognizedUnderBothPoliciesAndExpiresWithoutFreshSupport() {
        for (strict in listOf(false, true)) {
            val observation = AdaptiveObservation(areas(strict), 0)
            observation.start(0)
            observation.period(true, 0)
            for (time in listOf(0L, 8_000L)) {
                observation.observe(fix(time), time)
                assertFalse(observation.desired)
            }
            observation.observe(fix(16_000), 16_000)
            assertEquals(AdaptiveVisitDetector.Arrival.INSIDE, observation.detector.arrival)
            assertTrue(observation.desired)
            assertEquals(AdaptiveObservation.Sampling.BALANCED, observation.sampling)
            observation.tick(316_000)
            assertFalse(observation.desired)
        }
    }
}
