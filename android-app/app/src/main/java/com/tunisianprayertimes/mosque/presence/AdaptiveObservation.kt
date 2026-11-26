package com.tunisianprayertimes.mosque.presence

/** Sampling and DND eligibility have independent lifetimes. All durations use elapsed realtime. */
internal class AdaptiveObservation(areas: List<SilenceArea>, private val openedAt: Long) {
    enum class Sampling { PARKED, BALANCED, PRECISE }
    val detector = AdaptiveVisitDetector(areas)
    var sampling = Sampling.PARKED
        private set
    var generation = 0L
        private set
    var fallback = false
        private set
    var approachingOnLastFix = false
        private set
    private var unavailable = false
    private var eligiblePeriod = false
    private var requested = false
    private var diagnosticStart: Long? = null
    private var acceptedAt: Long? = null
    private var lastValid: Long? = null
    private var lastDistance: Double? = null
    private var lastRadius = 0.0
    private var retried = false
    private var recoveryStart: Long? = null
    private var recoveryOutside: Long? = null
    private var burstEnd: Long? = null
    private var lastMovementBurst: Long? = null
    private var lastGeofence: Pair<Int, Long>? = null
    private var supportingBurst: Long? = null
    private data class ApproachFix(val at: Long, val distance: Double, val accuracy: Double)
    private val recentApproach = ArrayDeque<ApproachFix>()
    private var recentExterior: PresenceFix? = null
    private var checkedInterior = false
    private var lastEntryCheck: Long? = null

    private fun change(next: Sampling, force: Boolean = false) {
        if (next != sampling || force) {
            generation++
            acceptedAt = null
            recentApproach.clear()
            sampling = next
        }
    }

    fun start(now: Long, forceBurst: Boolean = false) {
        if (unavailable) return
        if (forceBurst && sampling != Sampling.PRECISE) burst(now)
        else if (sampling == Sampling.PARKED) change(Sampling.BALANCED)
    }

    fun geofence(now: Long, transition: Int): Boolean {
        val previous = lastGeofence
        if (previous?.first == transition && now - previous.second < 120_000) return false
        lastGeofence = transition to now
        start(now, forceBurst = true)
        return true
    }

    private fun burst(now: Long, duration: Long = 120_000) {
        burstEnd = now + duration
        change(Sampling.PRECISE, force = sampling == Sampling.PRECISE)
    }

    private fun park() {
        change(Sampling.PARKED)
        burstEnd = null; diagnosticStart = null; lastValid = null; retried = false
        recentExterior = null; checkedInterior = false
        detector.clearEvidence()
    }

    fun locationDisabled(disabled: Boolean, now: Long) {
        if (unavailable == disabled) return
        unavailable = disabled
        if (disabled) {
            park()
            detector.reset()
            fallback = eligiblePeriod
            recoveryStart = null; recoveryOutside = null
        } else if (fallback) start(now, forceBurst = true)
    }

    fun period(active: Boolean, now: Long) {
        if (active == eligiblePeriod) return
        eligiblePeriod = active
        requested = false
        fallback = active && unavailable
        recoveryStart = null; recoveryOutside = null
        if (active && !unavailable && detector.arrival == null) {
            // Re-run an expired pre-window diagnostic. Never count parked time.
            if (sampling == Sampling.PARKED || diagnosticStart != null) {
                diagnosticStart = null; retried = false
                change(Sampling.BALANCED, force = true)
            }
        }
        tick(now)
    }

    fun accepted(request: Long, now: Long) {
        if (request != generation || sampling == Sampling.PARKED) return
        acceptedAt = now
        if (diagnosticStart == null && lastValid == null) diagnosticStart = now
    }

    fun observe(fix: PresenceFix, now: Long, watchForReturn: Boolean = false): Boolean {
        tick(now)
        if (sampling == Sampling.PARKED || unavailable || !detector.observe(fix, now, openedAt)) return false
        approachingOnLastFix = false
        lastValid = now; diagnosticStart = null; retried = false
        val radius = requireNotNull(fix.accuracyMeters)
        val distance = detector.distance
        if (fallback) {
            if (recoveryStart == null) { recoveryStart = now; burst(now) }
            recoveryOutside = if (detector.clearlyAway) recoveryOutside ?: fix.elapsedRealtimeMillis else null
            if (detector.arrival != null || recoveryOutside?.let { fix.elapsedRealtimeMillis - it >= 20_000 } == true)
                endFallback()
        }
        val near = distance <= radius + 100
        if (detector.arrival != null) {
            if (detector.clearlyAway && sampling != Sampling.PRECISE) burst(now)
            else if (detector.lastSupport == fix.elapsedRealtimeMillis && detector.outsideSince == null) {
                change(Sampling.BALANCED); burstEnd = null
            }
        } else if (!fallback) {
            if (!near) park()
            else sampleApproach(fix, distance, radius, now, watchForReturn)
        }
        lastDistance = distance; lastRadius = radius
        tick(now)
        return true
    }

    private fun sampleApproach(fix: PresenceFix, distance: Double, accuracy: Double, now: Long,
        watchForReturn: Boolean) {
        val entry = needsEntryCheck(fix, distance, accuracy)
        if (sampling != Sampling.BALANCED && !watchForReturn) return
        val singleStep = lastDistance?.let { it - distance > maxOf(6.0, accuracy + lastRadius) } == true
        val gradual = cumulativeApproach(fix.elapsedRealtimeMillis, distance, accuracy)
        approachingOnLastFix = singleStep || gradual
        if (sampling != Sampling.BALANCED) return
        if (lastDistance == null || approachingOnLastFix &&
            (lastMovementBurst == null || now - lastMovementBurst!! >= 300_000)) {
            if (entry) checkedInterior = true
            lastMovementBurst = now
            burst(now)
        } else if (entry && (lastEntryCheck == null || now - lastEntryCheck!! >= 300_000)) {
            // Balanced GPS may only report every minute. Give the detector several fresh fixes now.
            checkedInterior = true
            lastEntryCheck = now
            burst(now, duration = 30_000)
        }
    }

    private fun needsEntryCheck(fix: PresenceFix, distance: Double, accuracy: Double): Boolean {
        if (accuracy > 10.0) recentExterior = null
        if (distance > accuracy + 8 && accuracy <= 30.0) {
            checkedInterior = false
            recentExterior = fix.takeIf { accuracy <= 10.0 }
        }
        if (checkedInterior || distance >= 0 || accuracy > 30.0) return false
        if (distance < -(accuracy + 2)) return true
        // An imprecise edge alone cannot justify an extra burst. Check an actual recent crossing.
        val outside = recentExterior ?: return false
        val elapsed = fix.elapsedRealtimeMillis - outside.elapsedRealtimeMillis
        if (elapsed !in 1..60_000L) return false
        val reachable = fix.point.reachableOnFootFrom(outside.point, elapsed, requireNotNull(outside.accuracyMeters) + accuracy)
        if (!reachable) recentExterior = null
        return reachable
    }

    /** A gradual approach can cross the error margin over several balanced updates without crossing it in one.
     * Use it only near a boundary, where the existing two-minute burst can cover the final approach. */
    private fun cumulativeApproach(at: Long, distance: Double, accuracy: Double): Boolean {
        if (accuracy > 30.0) {
            recentApproach.clear()
            return false
        }
        while (recentApproach.isNotEmpty() && at - recentApproach.first().at > 120_000L) {
            recentApproach.removeFirst()
        }
        val previous = recentApproach.lastOrNull()
        val approaching = distance <= 30.0 && previous != null && previous.distance > distance &&
            recentApproach.any { anchor ->
                anchor.distance > previous.distance && anchor.distance - distance > maxOf(6.0, anchor.accuracy + accuracy)
            }
        if (recentApproach.size >= 6) recentApproach.removeFirst()
        recentApproach.addLast(ApproachFix(at, distance, accuracy))
        return approaching
    }

    private fun endFallback() {
        fallback = false; recoveryStart = null; recoveryOutside = null
    }

    fun tick(now: Long) {
        detector.tick(now)
        if (unavailable) { fallback = eligiblePeriod; requested = fallback; return }
        if (burstEnd?.let { now >= it } == true) {
            burstEnd = null
            detector.endBurst()
            change(Sampling.BALANCED)
        }
        if (recoveryStart?.let { now >= it + 120_000 } == true) endFallback()
        val diag = diagnosticStart
        if (diag != null && now >= diag + 90_000) {
            fallback = eligiblePeriod
            recoveryStart = null; recoveryOutside = null
            detector.reset()
            park()
        } else if (diag != null && now >= diag + 45_000 && !retried) {
            retried = true
            change(sampling, force = true)
        } else if (diag == null && sampling != Sampling.PARKED && lastValid != null && acceptedAt != null) {
            val interval = if (sampling == Sampling.PRECISE) 5_000L else 60_000L
            if (now - maxOf(lastValid!!, acceptedAt!!) >= 2 * interval) {
                lastValid = null
                change(sampling, force = true) // Start the diagnostic only when Android accepts this request.
            }
        }
        val support = detector.lastSupport
        if (detector.arrival != null && support != null && sampling == Sampling.BALANCED &&
            now - support >= 180_000 && (supportingBurst == null || now - supportingBurst!! >= 180_000)) {
            supportingBurst = now; burst(now)
        }
        val fresh = support != null && now - support <= 30_000 && detector.outsideSince == null
        requested = eligiblePeriod && (fallback || detector.arrival != null && (requested || fresh))
    }

    val desired get() = requested
    val isUnavailable get() = unavailable
    val requestEnd get() = listOfNotNull(burstEnd, diagnosticStart?.plus(90_000), recoveryStart?.plus(120_000)).minOrNull()
    val deadline get() = listOfNotNull(requestEnd, detector.expiry,
        diagnosticStart?.takeUnless { retried }?.plus(45_000)).minOrNull()
    val state get() = when {
        fallback -> PresenceState.SCHEDULE_FALLBACK
        detector.outsideSince != null -> PresenceState.EXITING
        detector.arrival != null -> PresenceState.VISIT_CONFIRMED
        sampling != Sampling.PARKED -> PresenceState.ACQUIRING
        else -> PresenceState.IDLE
    }
}
