package com.tunisianprayertimes.mosque.presence

import kotlin.math.hypot

/** The observed exterior approach to one visit, in walking order. Never persisted or shared between visits. */
internal class ExteriorReturnPath(private val polygons: List<SilencePolygon>) {
    enum class IndoorSupport { NONE, CONTINUING, ESTABLISHED }
    private val approach = ArrayDeque<PresenceFix>()
    private var lastExterior: PresenceFix? = null
    private var entryInside: PresenceFix? = null
    private var inside: PresenceFix? = null
    private var lastVisitFix: PresenceFix? = null
    private data class Match(val fix: PresenceFix, val along: Double)
    private var first: Match? = null
    private var last: Match? = null
    private var samples = 0
    val checking get() = first != null

    private fun precision(fix: PresenceFix) = requireNotNull(fix.accuracyMeters)
    private fun separation(a: GeoPoint, b: GeoPoint): Double {
        val (x, y) = project(a, b)
        return hypot(x, y)
    }

    private fun reachable(from: PresenceFix, to: PresenceFix) = to.point.reachableOnFootFrom(
        from.point, to.elapsedRealtimeMillis - from.elapsedRealtimeMillis, precision(from) + precision(to))

    private fun exteriorChord(a: GeoPoint, b: GeoPoint) = polygons.none { it.crossesExterior(a, b) }

    /** An unequivocal exterior fix seeds the path. Its continuous extension can run close to a wall. */
    fun approach(fix: PresenceFix, clearance: Double, inHole: Boolean) {
        val at = fix.elapsedRealtimeMillis
        val accuracy = precision(fix)
        val exterior = lastExterior
        if (exterior != null && (at - exterior.elapsedRealtimeMillis > 75_000 || !reachable(exterior, fix)) ||
            inHole || accuracy > 10.0) clear()
        if (approach.isNotEmpty() && entryInside == null && clearance < -(accuracy + 2.0)) entryInside = fix
        if (inHole || accuracy > 10.0 || clearance <= maxOf(2.0, accuracy / 2)) return
        val previous = approach.lastOrNull()
        if (entryInside != null || previous != null && (!exteriorChord(previous.point, fix.point) ||
                separation(previous.point, fix.point) > 50.0)) clear()
        if (approach.isEmpty() && clearance <= accuracy + 2.0) return
        lastExterior = fix
        if (approach.isEmpty() || separation(approach.last().point, fix.point) >= 3.0) {
            approach.addLast(fix)
            if (approach.size > 40) approach.removeFirst()
        }
    }

    fun confirmed(fix: PresenceFix, support: IndoorSupport) {
        val end = lastExterior
        if (approach.size < 2 || end == null || fix.elapsedRealtimeMillis - end.elapsedRealtimeMillis > 75_000 ||
            !reachable(end, fix) || separation(end.point, fix.point) > 50.0) clear()
        else if (entryInside == null && support == IndoorSupport.ESTABLISHED) entryInside = fix
    }

    /** Distance back along the observed path from its entrance end, with no extrapolation past either end. */
    private fun alongPath(fix: PresenceFix): Double? {
        var length = 0.0
        var bestOffset = Double.POSITIVE_INFINITY
        var bestAlong: Double? = null
        var earliest = Double.POSITIVE_INFINITY
        var latest = Double.NEGATIVE_INFINITY
        for (index in approach.lastIndex downTo 1) {
            val end = approach[index]
            val start = approach[index - 1]
            val (dx, dy) = project(start.point, end.point)
            val segment = hypot(dx, dy)
            val (x, y) = project(fix.point, end.point)
            val along = ((x * dx + y * dy) / segment).coerceIn(0.0, segment)
            val offset = hypot(x - along * dx / segment, y - along * dy / segment)
            val tolerance = maxOf(3.0, minOf(precision(fix), maxOf(precision(start), precision(end))))
            if (offset <= tolerance) {
                val position = length + along
                earliest = minOf(earliest, position)
                latest = maxOf(latest, position)
                if (offset < bestOffset) {
                    bestAlong = position
                    bestOffset = offset
                }
            }
            length += segment
        }
        // At a loop or a doubled-back sidewalk the same fix can match two different legs.
        return bestAlong?.takeIf { latest - earliest <= 2 * maxOf(3.0, precision(fix)) + 2.0 }
    }

    private fun plausibleReturn(inner: PresenceFix, fix: PresenceFix, along: Double): Boolean {
        val entrance = approach.last().point
        val viaEntrance = separation(inner.point, entrance) + along
        val elapsed = fix.elapsedRealtimeMillis - inner.elapsedRealtimeMillis
        if (viaEntrance > walkingDistanceAllowance(elapsed, precision(inner) + precision(fix))) return false
        if (along <= 15.0) return true
        // Balanced sampling may miss the doorway. Connect the last indoor fix to the *observed*
        // entrance passage before accepting a match already partway down the outdoor route.
        val surveyed = entryInside ?: return false
        val (dx, dy) = project(surveyed.point, entrance)
        val (x, y) = project(inner.point, entrance)
        val length2 = dx * dx + dy * dy
        if (length2 == 0.0) return false
        val fraction = ((x * dx + y * dy) / length2).coerceIn(0.0, 1.0)
        val offset = hypot(x - fraction * dx, y - fraction * dy)
        return offset <= maxOf(15.0, precision(inner) + precision(surveyed))
    }

    fun duringVisit(fix: PresenceFix, sourceDistance: Double, otherDistance: Double, inHole: Boolean,
        support: IndoorSupport): Boolean {
        if (approach.size < 2) return false
        val accuracy = precision(fix)
        val at = fix.elapsedRealtimeMillis
        val prior = lastVisitFix
        lastVisitFix = fix
        if (accuracy > 10.0 || prior != null && (at - prior.elapsedRealtimeMillis > 75_000 || !reachable(prior, fix))) {
            inside = null
            clearMatch()
        }
        if (inside?.let { at - it.elapsedRealtimeMillis > 75_000 } == true) inside = null
        // A narrow hall can establish its indoor anchor with the same corroborated stop that
        // proved arrival. A single shallow fix only refreshes an uninterrupted indoor sequence.
        if (support == IndoorSupport.ESTABLISHED ||
            support == IndoorSupport.CONTINUING && prior != null && inside === prior) inside = fix
        if (accuracy > 10.0 || sourceDistance <= maxOf(2.0, accuracy / 2) || otherDistance <= accuracy + 2.0 || inHole) {
            clearMatch()
            return false
        }
        val along = alongPath(fix)
        if (along == null) {
            clearMatch()
            return false
        }
        val previous = last
        if (previous != null && (at - previous.fix.elapsedRealtimeMillis > MAX_DEPARTURE_GAP_MILLIS ||
                !reachable(previous.fix, fix) || !exteriorChord(previous.fix.point, fix.point) ||
                along + accuracy / 2 < previous.along)) clearMatch()
        val match = Match(fix, along)
        val start = first
        if (start == null) {
            val inner = inside ?: return false
            if (!plausibleReturn(inner, fix, along)) return false
            first = match
            samples = 0
        }
        last = match
        samples++
        if (start == null || samples < 3 || at - start.fix.elapsedRealtimeMillis < 20_000) return false
        val progress = maxOf(8.0, accuracy + precision(start.fix))
        return along - start.along > progress && separation(start.fix.point, fix.point) > progress
    }

    fun clearMatch() {
        first = null
        last = null
        samples = 0
    }

    fun clear() {
        approach.clear()
        lastExterior = null
        entryInside = null
        inside = null
        lastVisitFix = null
        clearMatch()
    }
}
