package com.tunisianprayertimes.mosque.presence

/** Sampling and DND eligibility have independent lifetimes. All durations use elapsed realtime. */
internal class AdaptiveObservation(areas: List<SilenceArea>, private val openedAt: Long) {
    enum class Sampling { PARKED, BALANCED, PRECISE }
    val detector = AdaptiveVisitDetector(areas)
    val wakeCircles = mosqueWakeCircles(areas)
    var wakeCoverageUntil = 0L
    var observationEnd = 0L
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
    private var recoveryOutside: LongRange? = null
    private var burstEnd: Long? = null
    private var lastMovementBurst: Long? = null
    private var lastGeofence: Pair<Int, Long>? = null
    private var lastBoundaryCheck: Long? = null
    private var probingExit = false
    private var nextVisitProbe: Long? = null
    private companion object {
        const val EXIT_PROBE_MILLIS = 30_000L
        const val VISIT_PROBE_RETRY_MILLIS = 300_000L
    }
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
            if (next != Sampling.PRECISE) probingExit = false
        }
    }

    fun start(now: Long, forceBurst: Boolean = false) {
        if (unavailable) return
        if (forceBurst && sampling != Sampling.PRECISE) burst(now)
        else if (sampling == Sampling.PARKED) change(Sampling.BALANCED)
    }

    fun geofence(now: Long, transition: Int): Boolean {
        val previous = lastGeofence
        if (sampling != Sampling.PARKED && previous?.first == transition && now - previous.second < 120_000) return false
        lastGeofence = transition to now
        if (detector.arrival != null) probeVisit(now) else start(now, forceBurst = true)
        return true
    }

    private fun burst(now: Long, duration: Long = 120_000, probeExit: Boolean = false) {
        probingExit = probeExit
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
            wakeCoverageUntil = 0L
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
        if (sampling == Sampling.PARKED || unavailable) return false
        val at = fix.elapsedRealtimeMillis
        if (!detector.observe(fix, now, openedAt)) {
            // A new but unusable measurement interrupts recovery. Old or mock callbacks add no evidence.
            val previous = recoveryOutside
            if (previous != null && !fix.isMock && at > previous.last && at <= now) recoveryOutside = null
            return false
        }
        approachingOnLastFix = false
        lastValid = now; diagnosticStart = null; retried = false
        val radius = requireNotNull(fix.accuracyMeters)
        val distance = detector.distance
        val near = distance <= radius + 100
        if (fallback) {
            if (recoveryStart == null) { recoveryStart = now; burst(now) }
            val previous = recoveryOutside
            recoveryOutside = when {
                !detector.clearlyAway || (near && radius > 30) || now - at > MAX_DEPARTURE_GAP_MILLIS -> null
                previous == null || at - previous.last > MAX_DEPARTURE_GAP_MILLIS -> at..at
                else -> previous.first..at
            }
            if (detector.arrival != null || recoveryOutside?.let { it.last - it.first >= 20_000 } == true) endFallback()
        }
        val tentativeUntil = detector.tentativeUntil
        if (detector.arrival != null) {
            sampleVisit(at, distance, radius, now)
        } else if (tentativeUntil != null && !fallback) {
            // Keep collecting arrival evidence until this crossing is confirmed or expires.
            if (sampling != Sampling.PRECISE || (burstEnd ?: now) < tentativeUntil)
                burst(now, duration = tentativeUntil - now)
        } else if (!fallback) {
            if (near) sampleApproach(fix, distance, radius, now, watchForReturn)
            else if (wakeCoverageUntil > now && wakeCoverageUntil >= observationEnd - 1_000 &&
                wakeCircles.size in 1..MAX_WAKE_CIRCLES && wakeCircles.none { it.overlaps(fix) }) {
                park()
            } else {
                // Inside a wake circle there may be no further transition before reaching the mosque.
                // An unregistered or expiring circle cannot wake a parked observer either.
                change(Sampling.BALANCED)
                burstEnd = null
            }
        }
        lastDistance = distance; lastRadius = radius
        tick(now)
        return true
    }

    private fun sampleVisit(at: Long, distance: Double, accuracy: Double, now: Long) {
        if (detector.checkingExterior || detector.returningToExteriorPath) {
            // Stronger exit evidence gets its full sampling window, even late in a short check.
            if (sampling != Sampling.PRECISE || probingExit) burst(now)
            return
        }
        // An indoor approach probe may expire just before the doorway. Collect the second
        // exterior fix while the first is still fresh, even during the ordinary probe cooldown.
        val until = detector.entranceCheckUntil
        if (until != null && until > now) {
            if (sampling != Sampling.PRECISE || (burstEnd ?: now) < until)
                burst(now, duration = until - now, probeExit = true)
            return
        }
        if (accuracy > 30 && detector.clearlyAway) {
            probeVisit(now)
            return
        }
        if (detector.lastSupport == at && nextVisitProbe?.let { now >= it } == true) nextVisitProbe = null
        val backInside = if (probingExit) distance < 0 && accuracy <= 30 else detector.lastSupport == at
        // A centre just outside every saved polygon warrants more fixes. The detector alone decides departure.
        if (sampling == Sampling.BALANCED && (detector.approachingEntrance || distance > 0 && accuracy <= 30) &&
            lastBoundaryCheck?.let { now - it < 120_000 } != true) {
            lastBoundaryCheck = now
            burst(now, duration = EXIT_PROBE_MILLIS, probeExit = true)
        } else if (backInside && !detector.approachingEntrance) {
            change(Sampling.BALANCED)
            burstEnd = null
        }
    }

    /** Weak cues and missing indoor support share a budget. Usable exit evidence bypasses it in sampleVisit. */
    private fun probeVisit(now: Long, supportExpired: Boolean = false) {
        val retry = nextVisitProbe
        if (retry != null && now < retry) return
        nextVisitProbe = now + VISIT_PROBE_RETRY_MILLIS
        // A precision request already in progress gives the weak evidence its chance to improve.
        if (sampling != Sampling.BALANCED) return
        if (supportExpired && retry == null) burst(now)
        else burst(now, duration = EXIT_PROBE_MILLIS, probeExit = true)
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
        if (distance < -(accuracy + 2) || detector.awaitingAccurateEntry) return true
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
        if (detector.arrival == null) {
            lastBoundaryCheck = null
            nextVisitProbe = null
            probingExit = false
        }
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
            now - support >= 180_000) probeVisit(now, supportExpired = true)
        val fresh = support != null && now - support <= 30_000 && !checkingExit
        requested = eligiblePeriod && (fallback || detector.tentativeUntil != null ||
            detector.arrival != null && (requested || fresh))
    }

    val desired get() = requested
    private val checkingExit get() = detector.checkingExterior || detector.returningToExteriorPath || detector.entranceCheckUntil != null
    val isUnavailable get() = unavailable
    val requestEnd get() = listOfNotNull(burstEnd, diagnosticStart?.plus(90_000), recoveryStart?.plus(120_000)).minOrNull()
    val deadline get() = listOfNotNull(requestEnd, detector.tentativeUntil, detector.expiry,
        diagnosticStart?.takeUnless { retried }?.plus(45_000)).minOrNull()
    val state get() = when {
        fallback -> PresenceState.SCHEDULE_FALLBACK
        checkingExit -> PresenceState.EXITING
        detector.arrival != null -> PresenceState.VISIT_CONFIRMED
        desired && detector.tentativeUntil != null -> PresenceState.ENTERING
        sampling != Sampling.PARKED -> PresenceState.ACQUIRING
        else -> PresenceState.IDLE
    }
}
