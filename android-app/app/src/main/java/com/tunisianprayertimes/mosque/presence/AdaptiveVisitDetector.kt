package com.tunisianprayertimes.mosque.presence

import kotlin.math.abs
import kotlin.math.hypot

internal const val MAX_DEPARTURE_GAP_MILLIS = 15_000L

/** Evidence stays in memory, separately for each component of each saved mosque. Times are elapsed milliseconds. */
internal class AdaptiveVisitDetector(areas: List<SilenceArea>) {
    private companion object {
        const val RELIABLE_ACCURACY_METERS = 10.0
        const val DOORWAY_ACCURACY_METERS = 15.0
        const val BOUNDARY_MARGIN_METERS = 2.0
        const val TEMPORARY_SILENCE_MILLIS = 45_000L
    }

    enum class Arrival { INSIDE, EDGE, EARLIER_PRECISE, WALKED_IN }
    enum class Departure { CLEAR_OUTSIDE, SUSTAINED_OUTSIDE, EXTERIOR_PATH, ENTRANCE_RETURN }
    private data class Reading(val point: GeoPoint, val at: Long, val accuracy: Double, val distance: Double) {
        fun separation(other: Reading): Double {
            val (x, y) = project(point, other.point)
            return hypot(x, y)
        }
        fun reachableFrom(previous: Reading) =
            point.reachableOnFootFrom(previous.point, at - previous.at, previous.accuracy + accuracy)
        val nearEdge get() = distance <= 3
        val inside get() = distance < -(accuracy + BOUNDARY_MARGIN_METERS)
        val accuracyCircleInside get() = distance < -accuracy
        val shallowInterior get() = accuracy <= RELIABLE_ACCURACY_METERS &&
            distance < -maxOf(BOUNDARY_MARGIN_METERS, accuracy / 2)
        val clearlyOutside get() = distance > accuracy + 8
        val nearby get() = distance > 3 && distance <= accuracy
    }

    private class Stop {
        private val readings = ArrayDeque<Reading>()
        val started get() = readings.isNotEmpty()
        fun clear() = readings.clear()
        fun fits(r: Reading) = readings.all { r.separation(it) <= 12 }
        fun add(r: Reading, qualifies: Boolean, edgeException: Boolean = false) {
            if (!qualifies || !fits(r)) clear()
            if (!qualifies) return
            if (edgeException && !r.nearEdge && (!started || r.distance > r.accuracy ||
                    readings.takeLast(2).any { !it.nearEdge })) { clear(); return }
            readings.addLast(r)
            // Keep enough of the current stop for the longest decision, including its first endpoint.
            while (readings.size > 3 && readings[1].at <= r.at - 45_000) readings.removeFirst()
        }
        fun spans(ms: Long) = readings.size >= 3 && readings.last().at - readings.first().at >= ms
        fun edgeComplete(): Boolean {
            val last = readings.lastOrNull() ?: return false
            val dwell = if (readings.any { it.accuracy > RELIABLE_ACCURACY_METERS }) 25_000L else 15_000L
            if (!last.nearEdge || !spans(dwell)) return false
            // Allow brief edge jitter after corroborated interior readings in this stop.
            var lastInsideAt: Long? = null
            for (index in readings.lastIndex downTo 0) {
                val sample = readings[index]
                if (sample.distance > sample.accuracy) return false
                if (!sample.accuracyCircleInside) continue
                if (lastInsideAt == null) {
                    if (last.at - sample.at > 12_000) return false
                    lastInsideAt = sample.at
                } else if (lastInsideAt - sample.at >= 3_000) {
                    return lastInsideAt - sample.at <= 12_000
                }
            }
            return false
        }
        fun shallowStopComplete() = spans(25_000) && readings.any {
            it.at - readings.first().at >= 1_500 && readings.last().at - it.at >= 1_500
        }
    }

    /** A measured walk from clearly outside to confidently inside this one polygon. */
    private class WalkIn {
        private var outside: Reading? = null
        val outdoorPoint get() = outside?.point
        private var lastStrongInsideAt: Long? = null
        private var firstInside: Reading? = null
        private var secondInsideAt: Long? = null
        private var firstDeepAt: Long? = null
        private var last: Reading? = null
        var crossedAt: Long? = null
            private set
        val awaitingAccurateEntry get() = outside != null && firstInside == null &&
            last?.let { it.distance < 0 && it.accuracy > RELIABLE_ACCURACY_METERS } == true

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
            crossedAt = null
        }

        fun observe(r: Reading): Boolean {
            val previous = last
            if (r.accuracy > RELIABLE_ACCURACY_METERS) {
                // Keep the original outdoor fix and its timestamp through doorway uncertainty.
                // A later accurate indoor fix must still establish the crossing.
                val anchor = outside
                val overlapsDoorway = r.accuracy <= DOORWAY_ACCURACY_METERS && abs(r.distance) <= r.accuracy
                if (firstInside == null && anchor != null && previous != null &&
                    (r.clearlyOutside || overlapsDoorway) && r.at - anchor.at <= 60_000 &&
                    r.reachableFrom(anchor) && r.reachableFrom(previous)) {
                    last = r
                } else {
                    clearPassage()
                    last = null
                }
                return false
            }
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
                if (anchor.distance - r.distance > anchor.accuracy + r.accuracy) crossedAt = r.at
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

    /** The crossing can support a later measured stop. Its temporary silence has a fixed deadline. */
    private class Crossing(private val first: Reading, val entrance: ExteriorCrossing?) {
        val at get() = first.at
        val until = at + TEMPORARY_SILENCE_MILLIS
        private var last = first
        private var firstOutsideAt: Long? = null
        var supportsStop = true
            private set

        fun interrupt() {
            supportsStop = false
            firstOutsideAt = null
        }

        fun continues(r: Reading, shallowStop: Stop): Boolean {
            val gap = r.at - last.at
            if (!r.reachableFrom(last) || !r.reachableFrom(first)) supportsStop = false
            last = r
            // Three balanced readings may take two minutes. Only an uninterrupted stop that
            // started during the temporary window can finish confirming after its deadline.
            if (gap > 75_000 || r.at > at + 150_000) return false
            if (r.at >= until && !(supportsStop && r.shallowInterior && shallowStop.started && shallowStop.fits(r))) return false
            if (r.distance < 0) {
                firstOutsideAt = null
                return true
            }
            if (r.distance > r.accuracy) return false
            // A centre just across the wall can be indoor GPS jitter. Require another
            // exterior-side observation over four seconds, with no long gap or return inside.
            if (gap > 12_000 || firstOutsideAt == null) firstOutsideAt = r.at
            return r.at - firstOutsideAt!! < 4_000
        }
    }

    private class Component(val areaId: String, val polygon: SilencePolygon, val stricter: Boolean) {
        private val history = ArrayDeque<Reading>()
        private val inside = Stop()
        private val edge = Stop()
        private val drift = Stop()
        private val shallowInterior = Stop()
        private val walkIn = if (stricter) null else WalkIn()
        val awaitingAccurateEntry get() = walkIn?.awaitingAccurateEntry == true
        var crossing: Crossing? = null
            private set
        private val spacedInterior = ArrayDeque<Reading>(3)
        private var previousInterior: Reading? = null
        private var firstDoor: Reading? = null
        private var door: Reading? = null
        private var lastAt: Long? = null
        var arrival: Arrival? = null
            private set

        fun clear() {
            crossing = null
            clearMeasurements()
        }

        fun unusableFix() {
            crossing?.interrupt()
            clearMeasurements()
        }

        private fun clearMeasurements() {
            spacedInterior.clear()
            previousInterior = null
            walkIn?.clear()
            clearDwellEvidence()
        }

        fun endBurst() {
            // A change of request does not invalidate a fresh exterior fix. WalkIn checks
            // the actual age, reachability and gaps between delivered fixes.
            clearDwellEvidence(keepShallowStop = crossing != null)
        }

        private fun clearDwellEvidence(keepShallowStop: Boolean = false) {
            history.clear()
            inside.clear(); edge.clear(); drift.clear()
            if (!keepShallowStop) shallowInterior.clear()
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

        private fun approachedShallowStop(r: Reading, approach: Boolean): Boolean {
            crossing?.let { return it.supportsStop }
            return (shallowInterior.started || approach) && history.any {
                it.clearlyOutside && it.accuracy <= RELIABLE_ACCURACY_METERS &&
                    r.at - it.at <= 60_000 && r.reachableFrom(it)
            }
        }

        fun indoorReference(r: Reading, holding: Arrival?): ExteriorReturnPath.IndoorSupport {
            if (r.accuracy > RELIABLE_ACCURACY_METERS) return ExteriorReturnPath.IndoorSupport.NONE
            if (r.inside) return ExteriorReturnPath.IndoorSupport.ESTABLISHED
            if (holding != Arrival.EDGE || stricter || !r.shallowInterior) return ExteriorReturnPath.IndoorSupport.NONE
            return if (shallowInterior.shallowStopComplete()) ExteriorReturnPath.IndoorSupport.ESTABLISHED
                else ExteriorReturnPath.IndoorSupport.CONTINUING
        }

        fun observe(r: Reading, holding: Arrival?): Boolean {
            if (holding != null) crossing = null
            if (r.accuracy > 30) { unusableFix(); return false }
            if (crossing?.continues(r, shallowInterior) == false) {
                crossing = null
                shallowInterior.clear()
            }
            if (polygon.inHole(r.point)) { clearMeasurements(); return false }
            if (lastAt?.let { r.at - it > 12_000 } == true) clearDwellEvidence(keepShallowStop = crossing != null)
            lastAt = r.at
            if (holding != null) {
                edge.add(r, r.nearEdge || edge.started, edgeException = true)
                if (!stricter && holding == Arrival.EDGE) shallowInterior.add(r, r.shallowInterior)
                return r.inside || edge.edgeComplete() || (!stricter && holding == Arrival.EDGE && r.shallowInterior)
            }
            history.addLast(r)
            while (history.first().at < r.at - 90_000) history.removeFirst()
            var approach = approaching()
            if (approach && r.accuracyCircleInside && r.accuracy <= 8) {
                val first = firstDoor
                if (first == null || first.separation(r) > 12) firstDoor = r
                else if (r.at - first.at >= 5_000) { door = r; firstDoor = r }
            } else firstDoor = null
            if (door?.let { r.at - it.at > 45_000 } == true) door = null
            if (door?.let { r.distance > r.accuracy || !r.nearEdge &&
                    (r.accuracy <= 8 || it.separation(r) > it.accuracy + r.accuracy) } == true) {
                clearMeasurements()
                lastAt = r.at; history.addLast(r); approach = false
            }
            inside.add(r, r.inside)
            edge.add(r, (edge.started || approach) && (edge.started || r.nearEdge), edgeException = true)
            drift.add(r, door != null && r.accuracy > 8 && r.nearby)
            val walkedIn = walkIn?.observe(r) == true
            if (walkIn?.crossedAt == r.at && crossing == null) {
                val entrance = walkIn.outdoorPoint?.let { polygon.singleExteriorCrossing(it, r.point) }
                    ?.takeIf { it.point.distanceFrom(r.point) <= 15.0 }
                crossing = Crossing(r, entrance)
            }
            // A narrow hall may never enclose the entire uncertainty circle. Require a measured
            // approach from outside followed by a sustained stop on the indoor side.
            val shallowCandidate = !stricter && r.shallowInterior && approachedShallowStop(r, approach)
            shallowInterior.add(r, shallowCandidate)
            arrival = when {
                inside.spans(15_000) -> Arrival.INSIDE
                walkedIn -> Arrival.WALKED_IN
                !stricter && sustainedInterior(r) -> Arrival.INSIDE
                edge.edgeComplete() || shallowInterior.shallowStopComplete() -> Arrival.EDGE
                drift.spans(25_000) -> Arrival.EARLIER_PRECISE
                else -> null
            }
            return arrival != null
        }
    }

    private val components = areas.filter { it.enabled }.flatMap { area ->
        area.polygons.map { Component(area.id, it, area.requireStrongerEvidence) }
    }
    private val exteriorPath = ExteriorReturnPath(components.map { it.polygon })
    private var entranceReturn: EntranceReturn? = null
    val approachingEntrance get() = entranceReturn?.returning == true
    val entranceCheckUntil get() = entranceReturn?.exteriorCheckUntil
    val awaitingAccurateEntry get() = components.any { it.awaitingAccurateEntry }
    val returningToExteriorPath get() = exteriorPath.checking
    var departureOnLastFix: Departure? = null
        private set
    private var source: Component? = null
    private data class TentativeEntry(val component: Component, val until: Long)
    private var tentative: TentativeEntry? = null
    val tentativeUntil get() = tentative?.until
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
    private var fullyOutsideSince: Long? = null
    private var previousExterior: Reading? = null
    val checkingExterior get() = fullyOutsideSince != null
    var distance = Double.POSITIVE_INFINITY
        private set
    var clearlyAway = false
        private set
    val expiry get() = if (arrival == null) null else minOf(lastSupport!! + 900_000, lastUsable!! + 300_000)
    fun tick(now: Long) {
        if (tentativeUntil?.let { now >= it } == true) tentative = null
        if (expiry?.let { now >= it } == true) reset()
    }
    fun clearEvidence() {
        tentative = null
        entranceReturn = null
        components.forEach { it.clear() }
        exteriorPath.clear()
        clearExteriorEvidence()
    }
    fun endBurst() { components.forEach { it.endBurst() } }
    fun reset() {
        clearEvidence()
        source = null; arrival = null; lastSupport = null; lastUsable = null
        departureOnLastFix = null
    }

    private fun clearExteriorEvidence() {
        outsideSince = null
        fullyOutsideSince = null
        previousExterior = null
    }

    private fun continuesOutside(previous: Reading, r: Reading): Boolean {
        if (r.at - previous.at > MAX_DEPARTURE_GAP_MILLIS) return false
        // Near a wall, successive fixes must describe a plausible walk or stop. Clearly
        // distant observations can also come from someone who drove away after the visit.
        if ((!previous.clearlyOutside || !r.clearlyOutside) && !r.reachableFrom(previous)) return false
        // A segment shorter than the sum of the endpoint clearances cannot cross a boundary.
        if (r.separation(previous) < r.distance + previous.distance) return true
        return components.none { it.polygon.crossesBoundary(previous.point, r.point) }
    }

    private fun exteriorDeparture(r: Reading): Departure? {
        val clear = r.accuracy <= 30 && r.clearlyOutside
        if (!clear && (r.accuracy > RELIABLE_ACCURACY_METERS || r.distance <= r.accuracy + BOUNDARY_MARGIN_METERS)) {
            clearExteriorEvidence()
            return null
        }
        val previous = previousExterior
        if (previous != null && !continuesOutside(previous, r)) clearExteriorEvidence()
        previousExterior = r
        val fullyOutside = fullyOutsideSince ?: r.at.also { fullyOutsideSince = it }
        outsideSince = if (clear) outsideSince ?: r.at else null
        // With at most 15 seconds between fixes these spans require at least three and four
        // observations respectively. The slower path also works at an unobserved door.
        return when {
            outsideSince?.let { r.at - it >= 20_000 } == true -> Departure.CLEAR_OUTSIDE
            r.at - fullyOutside >= 45_000 -> Departure.SUSTAINED_OUTSIDE
            else -> null
        }
    }

    /** True for a valid provider reading, even when its uncertainty makes it unusable for arrival. */
    fun observe(fix: PresenceFix, now: Long, since: Long): Boolean {
        tick(now)
        val at = fix.elapsedRealtimeMillis
        val a = fix.accuracyMeters
        if (fix.isMock || at < since || at <= lastTimestamp || at > now || now - at > 30_000) return false
        lastTimestamp = at
        departureOnLastFix = null
        if (!fix.point.isValid() || a == null || !a.isFinite() || a <= 0) {
            entranceReturn?.interrupt()
            components.forEach { it.unusableFix() }
            clearExteriorEvidence()
            exteriorPath.clearMatch()
            return false
        }
        distance = Double.POSITIVE_INFINITY
        val previouslyPresent = arrival != null
        var supported = false
        var sourceDistance = Double.POSITIVE_INFINITY
        var otherDistance = Double.POSITIVE_INFINITY
        var indoorSupport = ExteriorReturnPath.IndoorSupport.NONE
        var inHole = false
        var entered: Component? = null
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
                entranceReturn = component.crossing?.takeIf { it.supportsStop }?.entrance?.let { entrance ->
                    EntranceReturn(shape, entrance, components.filter { it !== component }.map { it.polygon })
                }
                supported = true
            }
            if (!previouslyPresent && component.crossing?.at == at) entered = component
            if (component === source) {
                sourceDistance = d
                indoorSupport = component.indoorReference(reading, arrival)
            } else otherDistance = minOf(otherDistance, d)
            if (d >= 0 && shape.holes.isNotEmpty() && shape.inHole(fix.point)) inHole = true
        }
        clearlyAway = distance > a + 8
        if (!previouslyPresent) {
            val previous = tentative
            if (previous?.component?.crossing == null) {
                // Overlapping areas may hand off the crossing without renewing the original deadline.
                val next = entered ?: previous?.let { components.firstOrNull { (it.crossing?.until ?: -1) > at } }
                tentative = next?.let { TentativeEntry(it, minOf(requireNotNull(it.crossing).until, previous?.until ?: Long.MAX_VALUE)) }
            }
            exteriorPath.approach(fix, distance, inHole)
            if (arrival != null) exteriorPath.confirmed(fix, indoorSupport)
        }
        if (arrival != null) {
            tentative = null
            if (a <= 100) lastUsable = now
            if (supported) lastSupport = at
            val exterior = exteriorDeparture(Reading(fix.point, at, a, distance))
            val pathExit = exteriorPath.duringVisit(fix, sourceDistance, otherDistance, inHole, indoorSupport)
            val doorExit = entranceReturn?.observe(fix, sourceDistance, otherDistance, inHole, now) == true
            val departure = exterior ?: Departure.EXTERIOR_PATH.takeIf { pathExit } ?: Departure.ENTRANCE_RETURN.takeIf { doorExit }
            if (departure != null && now - at <= MAX_DEPARTURE_GAP_MILLIS) {
                reset()
                departureOnLastFix = departure
            }
        }
        return true
    }
}
