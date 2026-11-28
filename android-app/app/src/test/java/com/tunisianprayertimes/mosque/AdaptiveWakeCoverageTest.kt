package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.mosque.presence.*
import com.tunisianprayertimes.mosque.presence.AdaptiveObservation.Sampling
import org.junit.Assert.*
import org.junit.Test

class AdaptiveWakeCoverageTest {
    private fun point(east: Int, north: Int) = GeoPoint(north / 110_574.0, east / 111_319.5)
    private fun fix(at: Long, north: Int, accuracy: Double = 5.0) = PresenceFix(point(0, north), accuracy, at)
    private val longHall = SilenceArea("long", 1, listOf(SilencePolygon(
        listOf(point(-150, -20), point(150, -20), point(150, 20), point(-150, 20)))))

    private fun begin() = AdaptiveObservation(listOf(longHall), 0).apply {
        observationEnd = 600_000
        period(true, 0)
        start(0, forceBurst = true)
    }

    @Test fun enterAndDwellInsideTheWakeCircleCanLeadDirectlyToAVisitWithoutAnotherTransition() {
        val m = begin()
        m.wakeCoverageUntil = m.observationEnd
        val region = m.wakeCircles.single()
        assertEquals(181.3, region.radius.toDouble(), 0.2)
        // 145 m from the wall but already inside the circle. No more fence crossings on this walk.
        for ((at, transition) in listOf(0L to 1, 60_000L to 4)) {
            assertTrue(m.geofence(at, transition))
            assertTrue(region.overlaps(fix(at, 165)))
            assertTrue(m.observe(fix(at, 165), at))
            assertEquals(Sampling.BALANCED, m.sampling)
            assertFalse(m.desired)
        }
        for ((at, north) in listOf(90_000L to 80, 120_000L to 32, 130_000L to 6, 138_000L to 5, 146_000L to 5)) {
            assertTrue(m.observe(fix(at, north), at))
        }
        assertNotNull(m.detector.arrival)
        assertTrue(m.desired)
    }

    @Test fun parkRequiresTheWholeAccuracyDiskToBeOutsideEveryRegisteredCircle() {
        val m = begin()
        m.wakeCoverageUntil = m.observationEnd
        assertTrue(m.observe(fix(1_000, 185), 1_000))
        assertEquals(Sampling.BALANCED, m.sampling)
        assertTrue(m.observe(fix(31_000, 200), 31_000))
        assertEquals(Sampling.PARKED, m.sampling)
    }

    @Test fun missingOrShortRegistrationKeepsOnlyBalancedSamplingOutside() {
        val m = begin()
        for ((at, until) in listOf(1_000L to 0L, 31_000L to 90_000L, 61_000L to 0L)) {
            m.wakeCoverageUntil = until
            assertTrue(m.observe(fix(at, 400), at))
            assertEquals(Sampling.BALANCED, m.sampling)
            assertFalse(m.desired)
        }
        m.wakeCoverageUntil = m.observationEnd
        assertTrue(m.observe(fix(91_000, 400), 91_000))
        assertEquals(Sampling.PARKED, m.sampling)
    }

    @Test fun aParkedObserverAcceptsAnotherEnterEvenWhenThePreviousTransitionWasEnter() {
        val m = begin()
        m.wakeCoverageUntil = m.observationEnd
        assertTrue(m.geofence(0, 1))
        assertTrue(m.observe(fix(1_000, 400), 1_000))
        assertEquals(Sampling.PARKED, m.sampling)
        assertTrue(m.geofence(20_000, 1))
        assertNotEquals(Sampling.PARKED, m.sampling)
        assertFalse(m.geofence(21_000, 1))
    }
}
