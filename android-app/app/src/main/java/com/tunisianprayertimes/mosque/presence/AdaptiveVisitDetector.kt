package com.tunisianprayertimes.mosque.presence

import kotlin.math.hypot

/** Evidence stays in memory, separately for each component of each saved mosque. Times are elapsed milliseconds. */
internal class AdaptiveVisitDetector(areas: List<SilenceArea>) {
    enum class Arrival { INSIDE, EDGE, EARLIER_PRECISE, NEARBY }
    private data class Reading(val point: GeoPoint, val at: Long, val accuracy: Double, val distance: Double) {
        fun separation(other: Reading): Double {
            val (x, y) = project(point, other.point)
            return hypot(x, y)
        }
        val nearEdge get() = distance <= 3
        val inside get() = distance < -(accuracy + 2)
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
            spans(if (readings.any { it.accuracy > 10 }) 25_000 else 15_000)
    }

    private class Component(val polygon: SilencePolygon, val stricter: Boolean) {
        private val history = ArrayDeque<Reading>()
        private val inside = Stop()
        private val edge = Stop()
        private val drift = Stop()
        private val nearby = Stop()
        private var firstDoor: Reading? = null
        private var door: Reading? = null
        private var lastAt: Long? = null
        var arrival: Arrival? = null
            private set

        fun clear() {
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

        fun observe(r: Reading, holding: Arrival?): Boolean {
            if (r.accuracy > 30 || polygon.inHole(r.point)) { clear(); return false }
            if (lastAt?.let { r.at - it > 12_000 } == true) clear()
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
                edge.edgeComplete() -> Arrival.EDGE
                drift.spans(25_000) -> Arrival.EARLIER_PRECISE
                nearby.spans(45_000) -> Arrival.NEARBY
                else -> null
            }
            return arrival != null
        }
    }

    private val components = areas.filter { it.enabled }.flatMap { area ->
        area.polygons.map { Component(it, area.requireStrongerEvidence) }
    }
    private var source: Component? = null
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
