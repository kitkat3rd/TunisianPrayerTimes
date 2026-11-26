package com.tunisianprayertimes.mosque.presence

import kotlin.math.hypot

/** Evidence stays in memory, separately for each component of each saved mosque. Times are elapsed milliseconds. */
internal class AdaptiveVisitDetector(areas: List<SilenceArea>) {
    private companion object {
        const val RELIABLE_ACCURACY_METERS = 10.0
        const val BOUNDARY_MARGIN_METERS = 2.0
    }

    enum class Arrival { INSIDE, EDGE, EARLIER_PRECISE, NEARBY, WALKED_IN }
    private data class Reading(val point: GeoPoint, val at: Long, val accuracy: Double, val distance: Double) {
        fun separation(other: Reading): Double {
            val (x, y) = project(point, other.point)
            return hypot(x, y)
        }
        fun reachableFrom(previous: Reading) =
            point.reachableOnFootFrom(previous.point, at - previous.at, previous.accuracy + accuracy)
        val nearEdge get() = distance <= 3
        val inside get() = distance < -(accuracy + BOUNDARY_MARGIN_METERS)
        val clearlyOutside get() = distance > accuracy + 8
        val nearby get() = distance > 3 && distance <= accuracy
    }

    private class Stop {
        private val readings = ArrayDeque<Reading>()
        val started get() = readings.isNotEmpty()
        fun clear() = readings.clear()
        fun add(r: Reading, qualifies: Boolean, edgeException: Boolean = false) {
            if (!qualifies || readings.any { r.separation(it) > 12 }) clear()
            if (!qualifies) return
            if (edgeException && !r.nearEdge && (!started || r.distance > r.accuracy ||
                    readings.takeLast(2).any { !it.nearEdge })) { clear(); return }
            readings.addLast(r)
            // Keep enough of the current stop for the longest decision, including its first endpoint.
            while (readings.size > 3 && readings[1].at <= r.at - 45_000) readings.removeFirst()
        }
        fun spans(ms: Long) = readings.size >= 3 && readings.last().at - readings.first().at >= ms
        fun edgeComplete() = started && readings.last().nearEdge &&
            spans(if (readings.any { it.accuracy > RELIABLE_ACCURACY_METERS }) 25_000 else 15_000)
    }

    /** A measured walk from clearly outside to confidently inside this one polygon. */
    private class WalkIn {
        private var outside: Reading? = null
        private var lastStrongInsideAt: Long? = null
        private var firstInside: Reading? = null
        private var secondInsideAt: Long? = null
        private var firstDeepAt: Long? = null
        private var last: Reading? = null

        fun clear() {
            clearPassage()
            lastStrongInsideAt = null
            last = null
        }

        private fun clearPassage() {
            outside = null
            firstInside = null
            secondInsideAt = null
            firstDeepAt = null
        }

        fun observe(r: Reading): Boolean {
            if (r.accuracy > RELIABLE_ACCURACY_METERS) { clearPassage(); last = null; return false }
            val previous = last
            val entry = firstInside
            // A balanced outside reading can be up to a minute old. The inside evidence must
            // come from one uninterrupted sequence, and an actual excursion starts a new attempt.
            val maxGap = if (entry == null) 60_000L else 12_000L
            val gap = previous?.let { r.at - it.at }
            val jump = previous != null && r.at - previous.at <= 60_000 && !r.reachableFrom(previous)
            val brokenSequence = gap != null && gap > maxGap
            val endedPassage = entry != null && (r.distance >= 0 || r.at - entry.at > 30_000)
            if (brokenSequence || endedPassage || jump) clearPassage()
            last = r
            if (r.inside) lastStrongInsideAt = r.at
            if (r.distance >= 0) {
                // An outdoor fix establishes the starting side even on a parallel path. After
                // a strong indoor fix, require corroboration before accepting a new exterior.
                val recentlyInside = lastStrongInsideAt?.let { r.at - it <= 60_000 } == true
                val exteriorConfirmed = previous != null && previous.clearlyOutside &&
                    r.at - previous.at in 1_500L..60_000L
                if (r.clearlyOutside && !jump && (!recentlyInside || exteriorConfirmed)) {
                    outside = r
                    lastStrongInsideAt = null
                }
                return false
            }
            val anchor = outside ?: return false
            val start = firstInside ?: run {
                if (r.at - anchor.at > 60_000 || !r.reachableFrom(anchor)) { clearPassage(); return false }
                firstInside = r
                r
            }
            if (secondInsideAt == null && r.at - start.at >= 1_500) secondInsideAt = r.at
            // Between two strong fixes, the accuracy circle must stay inside. The points can
            // settle on the same coordinates after the measured advance through the entrance.
            if (r.distance >= -r.accuracy) {
                firstDeepAt = null
                return false
            }
            if (!r.inside) return false
            val deepStart = firstDeepAt ?: r.at.also { firstDeepAt = it }
            val middle = secondInsideAt ?: return false
            // From an uncertain edge, require actual inward progress. If the crossing landed
            // well inside between samples, subsequent movement can be in any direction.
            val advance = if (start.inside) start.separation(r) else start.distance - r.distance
            return r.at - middle >= 1_500 && r.at - deepStart >= 3_000 && r.at - start.at >= 8_000 &&
                advance >= maxOf(2.0, minOf(start.accuracy, r.accuracy) / 2) &&
                r.reachableFrom(anchor) && r.reachableFrom(start)
        }
    }

    private class Component(val areaId: String, val polygon: SilencePolygon, val stricter: Boolean) {
        private val history = ArrayDeque<Reading>()
        private val inside = Stop()
        private val edge = Stop()
        private val drift = Stop()
        private val nearby = Stop()
        private val walkIn = if (stricter) null else WalkIn()
        private val spacedInterior = ArrayDeque<Reading>(3)
        private var previousInterior: Reading? = null
        private var firstDoor: Reading? = null
        private var door: Reading? = null
        private var lastAt: Long? = null
        var arrival: Arrival? = null
            private set

        fun clear() {
            spacedInterior.clear()
            previousInterior = null
            endBurst()
        }

        fun endBurst() {
            walkIn?.clear()
            clearDwellEvidence()
        }

        private fun clearDwellEvidence() {
            history.clear()
            inside.clear(); edge.clear(); drift.clear(); nearby.clear()
            firstDoor = null; door = null; lastAt = null; arrival = null
        }

        private fun approaching(): Boolean {
            if (history.size < 6) return false
            val first = history.take(3)
            val last = history.takeLast(3)
            fun median(group: List<Reading>, value: (Reading) -> Double) = group.map(value).sorted()[1]
            val drop = median(first, Reading::distance) - median(last, Reading::distance)
            return drop > maxOf(6.0, median(first, Reading::accuracy) + median(last, Reading::accuracy))
        }

        private fun recordSpacedInterior(r: Reading): Boolean {
            // Keep the ordinary boundary margin for reliable fixes. For every metre of excess
            // uncertainty, require another metre of clearance inside the saved polygon.
            val extraMargin = maxOf(0.0, r.accuracy - RELIABLE_ACCURACY_METERS)
            val requiredClearance = r.accuracy + BOUNDARY_MARGIN_METERS + extraMargin
            if (r.distance >= -requiredClearance) {
                spacedInterior.clear()
                previousInterior = null
                return false
            }
            val previous = previousInterior
            val first = spacedInterior.firstOrNull()
            // Balanced requests ask for one fix per minute. Allow delivery jitter, and require
            // three separated observations so a single delayed fix cannot complete a dense dwell.
            if (previous != null && (r.at - previous.at > 75_000 || !r.reachableFrom(previous)) ||
                first != null && !r.reachableFrom(first)) spacedInterior.clear()
            previousInterior = r
            val latest = spacedInterior.lastOrNull()
            if (latest == null || r.at - latest.at >= 20_000) spacedInterior.addLast(r)
            return spacedInterior.size >= 3
        }

        /** Monitoring can start inside. A continuous, accurate indoor walk also establishes presence. */
        private fun sustainedInterior(r: Reading): Boolean {
            if (recordSpacedInterior(r)) return true
            var newer = r
            for (i in history.lastIndex downTo 0) {
                val sample = history[i]
                if (sample.accuracy > RELIABLE_ACCURACY_METERS || !sample.inside ||
                    !newer.reachableFrom(sample) || !r.reachableFrom(sample))
                    return false
                if (history.size - i >= 3 && r.at - sample.at >= 15_000) return true
                newer = sample
            }
            return false
        }

        fun observe(r: Reading, holding: Arrival?): Boolean {
            if (r.accuracy > 30 || polygon.inHole(r.point)) { clear(); return false }
            if (lastAt?.let { r.at - it > 12_000 } == true) clearDwellEvidence()
            lastAt = r.at
            if (holding != null) {
                edge.add(r, r.nearEdge || edge.started, edgeException = true)
                nearby.add(r, holding == Arrival.NEARBY && !stricter && r.nearby)
                return r.inside || edge.edgeComplete() || nearby.spans(45_000)
            }
            history.addLast(r)
            while (history.first().at < r.at - 90_000) history.removeFirst()
            var approach = approaching()
            if (approach && r.nearEdge && r.accuracy <= 8) {
                val first = firstDoor
                if (first == null || first.separation(r) > 12) firstDoor = r
                else if (r.at - first.at >= 5_000) { door = r; firstDoor = r }
            } else firstDoor = null
            if (door?.let { r.at - it.at > 45_000 } == true) door = null
            if (!r.nearEdge && door?.let { r.accuracy <= 8 || it.separation(r) > it.accuracy + r.accuracy } == true) {
                clear()
                lastAt = r.at; history.addLast(r); approach = false
            }
            inside.add(r, r.inside)
            edge.add(r, (edge.started || approach) && (edge.started || r.nearEdge), edgeException = true)
            drift.add(r, door != null && r.accuracy > 8 && r.nearby)
            nearby.add(r, !stricter && r.nearby && (nearby.started || approach))
            arrival = when {
                inside.spans(15_000) -> Arrival.INSIDE
                walkIn?.observe(r) == true -> Arrival.WALKED_IN
                !stricter && sustainedInterior(r) -> Arrival.INSIDE
                edge.edgeComplete() -> Arrival.EDGE
                drift.spans(25_000) -> Arrival.EARLIER_PRECISE
                nearby.spans(45_000) -> Arrival.NEARBY
                else -> null
            }
            return arrival != null
        }
    }

    private val components = areas.filter { it.enabled }.flatMap { area ->
        area.polygons.map { Component(area.id, it, area.requireStrongerEvidence) }
    }
    private var source: Component? = null
    val confirmedAreaId get() = source?.areaId
    private var lastTimestamp = -1L
    var arrival: Arrival? = null
        private set
    var lastSupport: Long? = null
        private set
    var lastUsable: Long? = null
        private set
    var outsideSince: Long? = null
        private set
    var distance = Double.POSITIVE_INFINITY
        private set
    var clearlyAway = false
        private set
    val expiry get() = if (arrival == null) null else minOf(lastSupport!! + 900_000, lastUsable!! + 300_000)
    fun tick(now: Long) { if (expiry?.let { now >= it } == true) reset() }
    fun clearEvidence() { components.forEach { it.clear() } }
    fun endBurst() { components.forEach { it.endBurst() } }
    fun reset() {
        clearEvidence()
        source = null; arrival = null; lastSupport = null; lastUsable = null; outsideSince = null
    }

    /** True for a valid provider reading, even when its uncertainty makes it unusable for arrival. */
    fun observe(fix: PresenceFix, now: Long, since: Long): Boolean {
        tick(now)
        val at = fix.elapsedRealtimeMillis
        val a = fix.accuracyMeters
        if (fix.isMock || !fix.point.isValid() || at < since || at <= lastTimestamp || at > now ||
            now - at > 30_000 || a == null || !a.isFinite() || a <= 0) return false
        lastTimestamp = at
        distance = Double.POSITIVE_INFINITY
        val previouslyPresent = arrival != null
        var supported = false
        for (component in components) {
            val shape = component.polygon
            val clearance = shape.boundaryDistance(fix.point)
            val d = if (shape.contains(fix.point)) -clearance else clearance
            distance = minOf(distance, d)
            val reading = Reading(fix.point, at, a, d)
            if (previouslyPresent) {
                if (component === source) supported = component.observe(reading, arrival) || supported
                else if (a <= 30 && reading.inside) { supported = true }
            } else if (component.observe(reading, null) && arrival == null) {
                arrival = component.arrival
                source = component
                supported = true
            }
        }
        clearlyAway = distance > a + 8
        if (arrival != null) {
            if (a <= 100) lastUsable = now
            if (supported) lastSupport = at
            if (a <= 100) outsideSince = if (a <= 30 && clearlyAway) outsideSince ?: at else null
            if (outsideSince?.let { at - it >= 20_000 && a <= 30 && clearlyAway } == true) reset()
        }
        return true
    }
}
