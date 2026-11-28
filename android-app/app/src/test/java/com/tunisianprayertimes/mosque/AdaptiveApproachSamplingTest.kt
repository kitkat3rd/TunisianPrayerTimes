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

class AdaptiveApproachSamplingTest {
    private val mosque = SilencePolygon(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.001, 0.0),
        GeoPoint(0.001, 0.001), GeoPoint(0.0, 0.001)))

    private fun AdaptiveObservation.fix(at: Long, metersOutside: Double, accuracy: Double) {
        val point = GeoPoint(0.0005, 0.001 + metersOutside / 111_319.49)
        observe(PresenceFix(point, accuracy, at), at)
    }

    private fun balanced(initialDistance: Double = 37.0): AdaptiveObservation {
        val machine = AdaptiveObservation(listOf(SilenceArea("test", 1, listOf(mosque))), 0)
        machine.period(true, 0)
        machine.start(0, forceBurst = true)
        machine.fix(1_000, initialDistance, 3.0)
        machine.tick(120_000)
        assertEquals(AdaptiveObservation.Sampling.BALANCED, machine.sampling)
        return machine
    }

    private fun duringMovementCooldown(): AdaptiveObservation {
        val m = AdaptiveObservation(listOf(SilenceArea("test", 1, listOf(mosque))), 0)
        m.period(true, 0)
        m.start(0)
        m.fix(1_000, 33.0, 7.0) // Ordinary movement-triggered burst, followed by normal expiry.
        m.tick(121_000)
        m.fix(125_000, 33.0, 7.0)
        assertEquals(AdaptiveObservation.Sampling.BALANCED, m.sampling)
        return m
    }

    @Test fun crossingBetweenBalancedUpdatesGetsFreshFixesDuringTheMovementCooldown() {
        val m = duringMovementCooldown()
        m.fix(152_000, -3.0, 7.0)
        assertEquals(AdaptiveObservation.Sampling.PRECISE, m.sampling)
        assertEquals(197_000L, m.requestEnd)
        assertTrue("crossing the boundary starts temporary silence while collecting fresh fixes", m.desired)
        assertNull(m.detector.arrival)
        m.fix(155_000, -6.0, 5.0)
        m.fix(158_000, -10.0, 5.0)
        m.fix(163_000, -13.0, 5.0)
        m.fix(168_000, -13.0, 5.0)
        m.fix(174_000, -13.0, 5.0)
        assertTrue(m.desired)
        assertEquals(AdaptiveObservation.Sampling.BALANCED, m.sampling)
    }

    @Test fun aBorderlineDoorwayFixGetsOneShortCheckDuringTheMovementCooldown() {
        val m = duringMovementCooldown()
        m.fix(152_000, -3.0, 11.0)
        assertFalse(m.desired)
        assertEquals(AdaptiveObservation.Sampling.PRECISE, m.sampling)
        assertEquals(182_000L, m.requestEnd)
        m.fix(154_000, 1.0, 10.5) // The uncertainty circle still straddles the doorway.
        assertFalse(m.desired)
        assertEquals(182_000L, m.requestEnd)
        m.fix(157_000, -4.7, 8.7)
        assertTrue(m.desired)
        assertEquals(202_000L, m.detector.tentativeUntil)
    }

    @Test fun ordinaryInsideDwellCanConfirmAStationaryVisitWithNoFreshOutsideFix() {
        val m = duringMovementCooldown()
        m.fix(200_000, -18.0, 6.0)
        assertEquals(230_000L, m.requestEnd)
        assertFalse(m.desired)
        m.fix(206_000, -18.0, 6.0)
        m.fix(211_000, -18.0, 6.0)
        assertFalse(m.desired)
        m.fix(216_000, -18.0, 6.0)
        assertTrue(m.desired)
        assertEquals(AdaptiveObservation.Sampling.BALANCED, m.sampling)
    }

    @Test fun extraBurstRequiresCredibleEvidenceAndDoesNotRepeatWhileLingeringInside() {
        val m = duringMovementCooldown()
        m.fix(127_000, -1.0, 7.0) // A 34 m jump in 2 seconds exceeds both uncertainty radii and walking speed.
        m.fix(154_000, -2.0, 7.0)
        assertEquals(AdaptiveObservation.Sampling.BALANCED, m.sampling)
        m.fix(160_000, -12.0, 7.0) // Now there is a reading clearly inside, even without an approach.
        assertEquals(190_000L, m.requestEnd)
        assertFalse(m.desired)
        m.tick(190_000)
        m.fix(195_000, 25.0, 6.0)
        m.fix(201_000, 25.0, 6.0) // Corroborate the return outside after the strong indoor fix.
        m.fix(225_000, -2.0, 6.0) // A fresh crossing qualifies for temporary silence during the entry-check cooldown.
        assertTrue(m.desired)
        assertEquals(270_000L, m.requestEnd)
        m.fix(255_000, -12.0, 7.0)
        assertEquals(270_000L, m.requestEnd)
        assertNull(m.detector.arrival)
        m.tick(270_000)
        assertFalse(m.desired)
        assertEquals(AdaptiveObservation.Sampling.BALANCED, m.sampling)

        val stationary = duringMovementCooldown()
        stationary.fix(200_000, -2.0, 7.0) // Outside anchor is more than a minute old.
        assertEquals(AdaptiveObservation.Sampling.BALANCED, stationary.sampling)
        stationary.fix(203_000, -14.0, 8.0)
        assertEquals(233_000L, stationary.requestEnd)
        stationary.tick(233_000)
        for (t in 240_000L..600_000L step 30_000L) {
            stationary.fix(t, -14.0, 8.0)
            assertEquals(AdaptiveObservation.Sampling.BALANCED, stationary.sampling)
        }
    }

    @Test fun gradualApproachStartsABoundedBurstOutsideTheMosqueWithoutSilencing() {
        val m = balanced()
        m.fix(150_000, 37.0, 3.0)
        m.fix(177_000, 35.0, 9.0)
        m.fix(204_000, 31.0, 8.0)
        assertEquals(AdaptiveObservation.Sampling.BALANCED, m.sampling)
        m.fix(231_000, 21.0, 9.0)
        assertEquals(AdaptiveObservation.Sampling.PRECISE, m.sampling)
        assertEquals(351_000L, m.requestEnd)
        assertFalse(m.desired)

        m.fix(240_000, 18.0, 8.0)
        assertEquals(351_000L, m.requestEnd)
        m.tick(351_000)
        m.fix(352_000, 37.0, 3.0)
        m.fix(379_000, 35.0, 9.0)
        m.fix(406_000, 31.0, 8.0)
        m.fix(433_000, 21.0, 9.0)
        assertEquals(AdaptiveObservation.Sampling.BALANCED, m.sampling)
        assertFalse(m.desired)
    }

    @Test fun gradualApproachNeedsRecentAccurateEvidenceWithinTheFinalThirtyMeters() {
        val expired = balanced()
        expired.fix(150_000, 37.0, 3.0)
        expired.fix(177_000, 35.0, 9.0)
        expired.fix(204_000, 31.0, 8.0)
        expired.fix(280_000, 21.0, 9.0)
        assertEquals(AdaptiveObservation.Sampling.BALANCED, expired.sampling)

        val imprecise = balanced()
        imprecise.fix(150_000, 37.0, 3.0)
        imprecise.fix(177_000, 35.0, 9.0)
        imprecise.fix(204_000, 31.0, 45.0)
        imprecise.fix(231_000, 21.0, 9.0)
        assertEquals(AdaptiveObservation.Sampling.BALANCED, imprecise.sampling)

        val far = balanced(47.0)
        far.fix(150_000, 47.0, 3.0)
        far.fix(177_000, 45.0, 9.0)
        far.fix(204_000, 41.0, 8.0)
        far.fix(231_000, 31.0, 9.0)
        assertEquals(AdaptiveObservation.Sampling.BALANCED, far.sampling)
    }
}
