package com.tunisianprayertimes.mosque.presence

/** Evidence from one confirmed visit with a measured entrance crossing. All times are elapsed milliseconds. */
internal class EntranceReturn(
    private val polygon: SilencePolygon,
    private val entrance: ExteriorCrossing,
    private val otherPolygons: List<SilencePolygon>,
) {
    private companion object {
        const val STOP_MILLIS = 180_000L
        const val RETURN_MILLIS = 120_000L
        const val INDOOR_GAP_MILLIS = 75_000L
        const val EXTERIOR_PAIR_MILLIS = 12_000L
    }

    private var stop: GeoPoint? = null
    private var stopStart = 0L
    private var lastAtStop = 0L
    private var stayed = false
    private var previous: PresenceFix? = null
    private var lastInside: PresenceFix? = null
    private var closestToEntrance = Double.POSITIVE_INFINITY
    private var crossedHere = false
    private data class ExteriorFix(val at: Long, val clearance: Double, val circleClearance: Double)
    private var firstOutside: ExteriorFix? = null
    private var lastClearance = 0.0
    val returning get() = lastInside != null
    val exteriorCheckUntil get() = firstOutside?.at?.plus(EXTERIOR_PAIR_MILLIS)

    fun interrupt() {
        lastInside = null
        closestToEntrance = Double.POSITIVE_INFINITY
        crossedHere = false
        firstOutside = null
    }

    private fun forgetStop() {
        stop = null
        stayed = false
        interrupt()
    }

    fun observe(fix: PresenceFix, clearance: Double, otherClearance: Double, inHole: Boolean, now: Long): Boolean {
        val at = fix.elapsedRealtimeMillis
        val accuracy = requireNotNull(fix.accuracyMeters)
        val prior = previous
        if (prior != null && (at - prior.elapsedRealtimeMillis > INDOOR_GAP_MILLIS ||
                !fix.point.reachableOnFootFrom(prior.point, at - prior.elapsedRealtimeMillis,
                    accuracy + requireNotNull(prior.accuracyMeters)))) forgetStop()
        if (inHole || otherClearance <= accuracy) {
            forgetStop()
            return false
        }
        if (accuracy > 30 || now - at > MAX_DEPARTURE_GAP_MILLIS) {
            interrupt()
            return false
        }
        previous = fix
        // After leaving the stopping place, allow a short walk to the door. An old stop
        // cannot authorize a later exit after the user has been moving elsewhere.
        if (stop != null && at - lastAtStop > RETURN_MILLIS) forgetStop()
        if (clearance < 0) {
            if (crossedHere) interrupt()
            observeIndoors(fix, clearance, accuracy)
            return false
        }
        return observeOutdoors(fix, clearance, prior) && now - at <= 5_000
    }

    private fun observeOutdoors(fix: PresenceFix, clearance: Double, prior: PresenceFix?): Boolean {
        val at = fix.elapsedRealtimeMillis
        val accuracy = requireNotNull(fix.accuracyMeters)
        val inner = lastInside ?: run {
            if (clearance > accuracy) forgetStop()
            return false
        }
        if (accuracy > 10 || at - inner.elapsedRealtimeMillis > INDOOR_GAP_MILLIS ||
            fix.point.distanceFrom(entrance.point) > 25) {
            interrupt()
            return false
        }
        if (!crossedHere) {
            if (clearance == 0.0) return false
            val crossing = polygon.singleExteriorCrossing(inner.point, fix.point)
            if (crossing == null || crossing.segmentIndex != entrance.segmentIndex ||
                crossing.point.distanceFrom(entrance.point) > 15 ||
                otherPolygons.any { it.crossesBoundary(inner.point, fix.point) }) {
                interrupt()
                return false
            }
            crossedHere = true
        } else if (prior != null && (at - prior.elapsedRealtimeMillis > EXTERIOR_PAIR_MILLIS ||
                polygon.crossesBoundary(prior.point, fix.point) ||
                otherPolygons.any { it.crossesBoundary(prior.point, fix.point) })) {
            interrupt()
            return false
        }
        if (clearance <= accuracy) {
            firstOutside = null
            return false
        }
        val first = firstOutside
        if (first != null && (at - first.at > EXTERIOR_PAIR_MILLIS || clearance < lastClearance - 1.0)) {
            interrupt()
            return false
        }
        lastClearance = clearance
        if (first == null) {
            firstOutside = ExteriorFix(at, clearance, clearance - accuracy)
            return false
        }
        return at - first.at >= 2_000 && clearance - first.clearance >= 2.0 &&
            clearance - accuracy > first.circleClearance
    }

    private fun observeIndoors(fix: PresenceFix, clearance: Double, accuracy: Double) {
        val at = fix.elapsedRealtimeMillis
        val toEntrance = fix.point.distanceFrom(entrance.point)
        val reliableIndoor = accuracy <= 15 && clearance < -accuracy
        if (!stayed && accuracy <= 15 && stop?.let { fix.point.distanceFrom(it) > 12 } == true) forgetStop()
        if (reliableIndoor && toEntrance >= 30) {
            val anchor = stop
            if (anchor == null || !stayed && at - lastAtStop > INDOOR_GAP_MILLIS) {
                stop = fix.point
                stopStart = at
            }
            if (fix.point.distanceFrom(requireNotNull(stop)) <= 12) {
                lastAtStop = at
                stayed = stayed || at - stopStart >= STOP_MILLIS
                interrupt()
                return
            }
        }
        val anchor = stop ?: return
        if (!stayed || accuracy > 10 || toEntrance > closestToEntrance + accuracy) {
            interrupt()
            return
        }
        // Require an indoor observation well on the way back before considering any exterior pair.
        if (!returning && (!reliableIndoor || toEntrance > 35 ||
                anchor.distanceFrom(entrance.point) - toEntrance < maxOf(20.0, 2 * accuracy))) return
        closestToEntrance = minOf(closestToEntrance, toEntrance)
        lastInside = fix
    }
}
