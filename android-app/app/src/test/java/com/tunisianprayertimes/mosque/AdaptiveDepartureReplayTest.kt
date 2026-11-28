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

class AdaptiveDepartureReplayTest {
    private data class Sample(val at: Long, val metersOutside: Double, val accuracy: Double)

    @Test fun outwardCrossingRequestsFreshFixesButReleasesOnlyAfterTwentySecondsClearlyOutside() {
        // Replay the measured perpendicular clearances against an invented straight boundary.
        // Time zero corresponds to 14:04:00 on the recorded walk.
        val boundary = SilencePolygon(listOf(
            GeoPoint(-0.001, -0.001), GeoPoint(0.001, -0.001),
            GeoPoint(0.001, 0.0), GeoPoint(-0.001, 0.0),
        ))
        val mosque = SilenceArea("test", 1, listOf(boundary))
        val observation = AdaptiveObservation(listOf(mosque), 0)
        observation.period(true, 0)
        observation.start(0, forceBurst = true)

        fun deliver(sample: Sample) {
            val point = GeoPoint(0.0, sample.metersOutside / 111_319.49)
            assertTrue(observation.observe(PresenceFix(point, sample.accuracy, sample.at), sample.at))
        }

        // Establish the visit, then supply the ordinary balanced monitor fixes before departure.
        deliver(Sample(0, -12.0, 6.0))
        deliver(Sample(7_000, -12.0, 6.0))
        deliver(Sample(15_746, -12.280, 6.535))
        assertTrue(observation.desired)
        deliver(Sample(43_330, -13.676, 9.728))
        deliver(Sample(70_591, -16.273, 11.109))
        deliver(Sample(98_091, -5.379, 6.950))
        deliver(Sample(125_488, -8.152, 6.230))
        assertEquals(AdaptiveObservation.Sampling.BALANCED, observation.sampling)

        deliver(Sample(152_698, 6.161, 10.147))
        assertTrue(observation.desired)
        assertNull(observation.detector.outsideSince)
        assertEquals(AdaptiveObservation.Sampling.PRECISE, observation.sampling)

        // The independent route recorder saw these between two balanced monitor callbacks.
        // A monitor staying in balanced mode cannot use them to build departure evidence.
        val intermediate = listOf(
            Sample(155_360, 12.260, 9.257), Sample(157_680, 16.532, 9.007),
            Sample(159_727, 22.105, 8.239), Sample(162_081, 25.550, 7.556),
            Sample(164_162, 28.579, 6.994), Sample(166_568, 32.046, 7.365),
            Sample(169_042, 33.847, 8.220), Sample(171_140, 36.703, 9.164),
            Sample(173_331, 38.949, 10.787), Sample(175_405, 39.640, 10.733),
            Sample(177_776, 37.140, 9.508),
        )
        for (sample in intermediate) {
            if (observation.sampling == AdaptiveObservation.Sampling.PRECISE) deliver(sample)
            assertTrue("premature release at ${sample.at}", observation.desired)
        }
        assertEquals(159_727L, observation.detector.outsideSince)
        // This sample was seen by both providers, 20.079 s after the first clear exterior fix.
        deliver(Sample(179_806, 37.237, 8.493))
        assertFalse(observation.desired)
        assertEquals(AdaptiveVisitDetector.Departure.CLEAR_OUTSIDE, observation.detector.departureOnLastFix)
    }
}
