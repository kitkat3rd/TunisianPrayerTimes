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

class AdaptiveWalkNineArrivalTest {
    private fun point(x: Double, y: Double) = GeoPoint(y / 110_574.28, x / 111_319.49)

    private fun beginWalkNine(): AdaptiveObservation {
        // An invented hall with the local angle of the two walls nearest the recorded walk.
        // Rotating the fixes into this metre grid preserves clearance, movement and uncertainty.
        val hall = SilenceArea("test", 1, listOf(SilencePolygon(listOf(
            point(0.0, 0.0), point(150.0, 0.0), point(150.0, 100.0), point(-13.55, 100.0),
        ))))
        val observation = AdaptiveObservation(listOf(hall), 0)
        observation.period(true, 0)
        observation.start(0)
        // Time zero is the scheduled silencing window opening at 17:02.
        observation.fix(0, 117.9, -36.7, 9.5)
        observation.tick(120_000)
        observation.fix(300_461, 117.945, -36.681, 10.384)
        observation.fix(332_498, 117.160, -36.769, 10.185)
        observation.fix(359_829, 115.173, -37.356, 7.206)
        observation.fix(387_107, 112.725, -29.900, 9.855)
        observation.fix(415_118, 66.488, -29.774, 9.377)
        observation.fix(442_311, 20.398, -26.067, 10.621)
        assertEquals(AdaptiveObservation.Sampling.BALANCED, observation.sampling)
        assertFalse(observation.desired)

        observation.fix(469_453, -0.054, 9.768, 9.964) // First fix just inside the angled wall.
        assertEquals(AdaptiveObservation.Sampling.PRECISE, observation.sampling)
        assertTrue("silence on the first credible crossing at 17:09:49.453", observation.desired)
        assertNull(observation.detector.confirmedAreaId)
        assertFalse(observation.fallback)
        observation.fix(470_067, 0.844, 10.272, 9.893)
        assertTrue(observation.desired)
        observation.fix(471_927, 4.597, 10.411, 9.364)
        assertTrue(observation.desired)
        observation.fix(473_837, 8.479, 9.686, 9.004) // First fully enclosed accuracy circle.
        assertTrue(observation.desired)
        observation.fix(475_680, 11.792, 8.814, 8.308)
        assertTrue(observation.desired)
        assertNull(observation.detector.confirmedAreaId)
        assertFalse(observation.fallback)
        return observation
    }

    private fun AdaptiveObservation.fix(at: Long, x: Double, y: Double, accuracy: Double) {
        assertTrue(observe(PresenceFix(point(x, y), accuracy, at), at))
    }

    @Test fun promptSilenceStaysOnAroundTheSecondWallThroughConfirmationAndEndsOnDeparture() {
        val observation = beginWalkNine()
        fun stillSilent(at: Long, x: Double, y: Double, accuracy: Double) {
            observation.fix(at, x, y, accuracy)
            assertTrue("lost silence at $at", observation.desired)
        }
        stillSilent(478_351, 17.827, 7.170, 7.641) // Brief overlap at the other wall of the corner.
        stillSilent(480_269, 19.952, 8.224, 7.434)
        stillSilent(482_180, 24.135, 8.643, 7.451)
        stillSilent(484_351, 29.668, 7.990, 7.305)
        stillSilent(486_274, 33.368, 7.620, 7.281)
        stillSilent(488_352, 37.595, 7.119, 7.043)
        stillSilent(490_334, 41.755, 7.899, 7.090)
        stillSilent(492_262, 45.814, 8.706, 7.026)
        stillSilent(494_351, 50.317, 9.836, 6.596)
        stillSilent(496_203, 53.989, 10.578, 6.245)
        stillSilent(498_351, 57.811, 13.139, 5.978)
        stillSilent(500_351, 59.671, 15.974, 6.023)
        stillSilent(502_351, 58.840, 17.581, 6.009)
        stillSilent(504_351, 57.814, 17.603, 6.130)
        stillSilent(506_351, 58.397, 16.758, 5.705)
        stillSilent(508_351, 58.364, 16.288, 5.436)
        stillSilent(510_351, 58.587, 15.152, 5.257)
        assertEquals("test", observation.detector.confirmedAreaId)

        // Ordinary indoor sampling until the recorded return trip.
        for (at in 540_351L..1_250_351L step 30_000L) stillSilent(at, 58.6, 15.2, 6.0)
        stillSilent(1_260_999, 55.143, 19.496, 9.866)
        stillSilent(1_288_178, 20.569, 8.222, 8.268)
        stillSilent(1_315_596, -0.074, -11.576, 7.512)
        stillSilent(1_318_121, 1.113, -15.871, 8.172)
        stillSilent(1_320_186, 2.063, -19.385, 8.949)
        stillSilent(1_325_082, 4.739, -27.916, 9.049)
        stillSilent(1_331_668, 15.174, -34.827, 10.331)
        stillSilent(1_337_912, 24.885, -31.503, 9.504)
        stillSilent(1_340_182, 28.517, -30.741, 10.323) // Four milliseconds short of 20 s.
        observation.fix(1_342_041, 31.516, -29.720, 10.557)
        assertFalse(observation.desired)
        assertEquals(AdaptiveVisitDetector.Departure.CLEAR_OUTSIDE, observation.detector.departureOnLastFix)
    }
}
