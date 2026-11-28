package com.tunisianprayertimes.mosque.presence

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS84 coordinates for a deliberately saved local area, never an inferred GPS-scatter boundary. */
data class GeoPoint(val latitude: Double, val longitude: Double) {
    fun isValid(): Boolean = latitude.isFinite() && longitude.isFinite() &&
        latitude in -85.0..85.0 && longitude in -180.0..180.0
}

/** Small simple polygon, in geographic coordinates. Closed or open input rings are accepted. */
data class SilencePolygon(val exterior: List<GeoPoint>, val holes: List<List<GeoPoint>> = emptyList()) {
    init {
        require(validRing(exterior)) { "Invalid, crossing, degenerate or oversized exterior" }
        require(holes.all(::validRing)) { "Invalid hole" }
        val outer = openRing(exterior)
        val inner = holes.map(::openRing)
        require(inner.all { hole -> !ringsIntersect(outer, hole) && hole.all { pointInRing(it, outer) } }) {
            "Holes must lie strictly inside their exterior"
        }
        require(inner.indices.all { i -> (i + 1 until inner.size).all { j ->
            !ringsIntersect(inner[i], inner[j]) && !pointInRing(inner[i][0], inner[j]) &&
                !pointInRing(inner[j][0], inner[i])
        } }) { "Touching, overlapping or nested holes are unsupported" }
    }

    internal fun contains(point: GeoPoint): Boolean = pointInRing(point, openRing(exterior)) &&
        holes.none { pointInRing(point, openRing(it)) }

    internal fun inHole(point: GeoPoint): Boolean = holes.any { pointInRing(point, openRing(it)) }

    /** Two exterior samples can straddle a building or cut across a concave corner. */
    internal fun crossesExterior(from: GeoPoint, to: GeoPoint): Boolean = crossesRing(from, to, exterior)

    internal fun crossesBoundary(from: GeoPoint, to: GeoPoint): Boolean =
        crossesExterior(from, to) || holes.any { crossesRing(from, to, it) }

    internal fun boundaryDistance(point: GeoPoint): Double = (listOf(exterior) + holes).minOf { ring ->
        val vertices = openRing(ring)
        vertices.indices.minOf { i ->
            val a = project(vertices[i], point)
            val b = project(vertices[(i + 1) % vertices.size], point)
            distanceToSegment(a.first, a.second, b.first, b.second)
        }
    }
}

/** Multipart is deliberately restricted to disjoint components; unsupported unions are rejected. */
data class SilenceArea(
    val id: String,
    val revision: Long,
    val polygons: List<SilencePolygon>,
    val enabled: Boolean = true,
    val requireStrongerEvidence: Boolean = false,
) {
    init {
        require(id.isNotBlank() && revision >= 0 && polygons.isNotEmpty())
        require(polygons.indices.all { i -> (i + 1 until polygons.size).all { j ->
            val a = openRing(polygons[i].exterior)
            val b = openRing(polygons[j].exterior)
            !ringsIntersect(a, b) && !pointInRing(a[0], b) && !pointInRing(b[0], a)
        } }) { "Merge touching/overlapping components in a validated editor before use" }
    }
}

data class PresenceFix(
    val point: GeoPoint,
    val accuracyMeters: Double?,
    val elapsedRealtimeMillis: Long,
    val isMock: Boolean = false,
)

internal data class ExteriorCrossing(val point: GeoPoint, val segmentIndex: Int)

/** A single crossing through the interior of an exterior edge, with both observations off the wall. */
internal fun SilencePolygon.singleExteriorCrossing(from: GeoPoint, to: GeoPoint): ExteriorCrossing? {
    if (!from.isValid() || !to.isValid() || holes.any { crossesRing(from, to, it) }) return null
    val ring = openRing(exterior)
    var crossing: ExteriorCrossing? = null
    for (index in ring.indices) {
        val a = ring[index]
        val b = ring[(index + 1) % ring.size]
        if (!segmentsIntersect(from, to, a, b)) continue
        // At a vertex both adjacent edges are touched. Concave outlines can be crossed several times.
        if (crossing != null) return null
        val fromSide = signedArea2(a, b, from)
        val toSide = signedArea2(a, b, to)
        if (kotlin.math.abs(fromSide) <= 1e-14 || kotlin.math.abs(toSide) <= 1e-14 || fromSide * toSide >= 0) return null
        val fraction = fromSide / (fromSide - toSide)
        val point = GeoPoint(from.latitude + fraction * (to.latitude - from.latitude),
            from.longitude + fraction * (to.longitude - from.longitude))
        crossing = ExteriorCrossing(point, index)
    }
    return crossing
}

private fun openRing(ring: List<GeoPoint>): List<GeoPoint> =
    if (ring.size > 1 && ring.first() == ring.last()) ring.dropLast(1) else ring

private fun crossesRing(from: GeoPoint, to: GeoPoint, vertices: List<GeoPoint>): Boolean {
    val ring = openRing(vertices)
    return ring.indices.any { i -> segmentsIntersect(from, to, ring[i], ring[(i + 1) % ring.size]) }
}

private fun validRing(input: List<GeoPoint>): Boolean {
    val ring = openRing(input)
    if (ring.size < 3 || ring.distinct().size != ring.size || ring.any { !it.isValid() }) return false
    // This local tangent-plane engine does not support poles, dateline wrapping or large areas.
    if (ring.maxOf { it.longitude } - ring.minOf { it.longitude } > 0.03 ||
        ring.maxOf { it.latitude } - ring.minOf { it.latitude } > 0.03) return false
    val xy = ring.map { project(it, ring[0]) }
    val area2 = xy.indices.sumOf { i -> val j = (i + 1) % xy.size
        xy[i].first * xy[j].second - xy[j].first * xy[i].second
    }
    if (kotlin.math.abs(area2) < 0.01) return false
    return firstBoundaryCrossing(ring, closed = true) == null
}

internal fun firstBoundaryCrossing(points: List<GeoPoint>, closed: Boolean): Pair<Int, Int>? {
    val count = if (closed) points.size else (points.size - 1).coerceAtLeast(0)
    for (i in 0 until count) {
        for (j in i + 2 until count) {
            if (closed && i == 0 && j == count - 1) continue
            if (segmentsIntersect(points[i], points[(i + 1) % points.size], points[j], points[(j + 1) % points.size])) {
                return i to j
            }
        }
    }
    return null
}

private fun ringsIntersect(a: List<GeoPoint>, b: List<GeoPoint>): Boolean = a.indices.any { i ->
    b.indices.any { j -> segmentsIntersect(a[i], a[(i + 1) % a.size], b[j], b[(j + 1) % b.size]) }
}

private fun signedArea2(p: GeoPoint, q: GeoPoint, r: GeoPoint) =
    (q.longitude - p.longitude) * (r.latitude - p.latitude) -
        (q.latitude - p.latitude) * (r.longitude - p.longitude)

private fun segmentsIntersect(a: GeoPoint, b: GeoPoint, c: GeoPoint, d: GeoPoint): Boolean {
    fun on(p: GeoPoint, q: GeoPoint, r: GeoPoint): Boolean =
        kotlin.math.abs(signedArea2(p, q, r)) <= 1e-14 &&
            r.latitude >= minOf(p.latitude, q.latitude) - 1e-12 &&
            r.latitude <= maxOf(p.latitude, q.latitude) + 1e-12 &&
            r.longitude >= minOf(p.longitude, q.longitude) - 1e-12 &&
            r.longitude <= maxOf(p.longitude, q.longitude) + 1e-12
    val abC = signedArea2(a, b, c); val abD = signedArea2(a, b, d)
    val cdA = signedArea2(c, d, a); val cdB = signedArea2(c, d, b)
    return (abC * abD < 0 && cdA * cdB < 0) || on(a, b, c) || on(a, b, d) || on(c, d, a) || on(c, d, b)
}

private fun pointInRing(point: GeoPoint, ring: List<GeoPoint>): Boolean {
    var inside = false
    for (i in ring.indices) {
        val a = ring[i]; val b = ring[(i + 1) % ring.size]
        if ((a.latitude > point.latitude) != (b.latitude > point.latitude) &&
            point.longitude < a.longitude + (b.longitude - a.longitude) *
            (point.latitude - a.latitude) / (b.latitude - a.latitude)) inside = !inside
    }
    return inside
}

internal fun project(point: GeoPoint, origin: GeoPoint): Pair<Double, Double> {
    val radians = origin.latitude * PI / 180
    val eccentricity2 = 6.69437999014e-3
    val denominator = 1 - eccentricity2 * sin(radians) * sin(radians)
    val lonScale = 6_378_137.0 / sqrt(denominator) * cos(radians) * PI / 180
    val latScale = 6_378_137.0 * (1 - eccentricity2) / (denominator * sqrt(denominator)) * PI / 180
    return (point.longitude - origin.longitude) * lonScale to (point.latitude - origin.latitude) * latScale
}

/** Allow for a brisk walking pace plus the reported horizontal errors of both fixes. */
internal fun walkingDistanceAllowance(elapsedMillis: Long, combinedAccuracyMeters: Double): Double =
    combinedAccuracyMeters + 4.0 * elapsedMillis / 1_000.0

internal fun GeoPoint.distanceFrom(other: GeoPoint): Double {
    val (east, north) = project(this, other)
    return hypot(east, north)
}

internal fun GeoPoint.reachableOnFootFrom(previous: GeoPoint, elapsedMillis: Long, combinedAccuracyMeters: Double) =
    distanceFrom(previous) <= walkingDistanceAllowance(elapsedMillis, combinedAccuracyMeters)

private fun distanceToSegment(ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax; val dy = by - ay
    val length2 = dx * dx + dy * dy
    val fraction = if (length2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / length2).coerceIn(0.0, 1.0)
    return hypot(ax + fraction * dx, ay + fraction * dy)
}
