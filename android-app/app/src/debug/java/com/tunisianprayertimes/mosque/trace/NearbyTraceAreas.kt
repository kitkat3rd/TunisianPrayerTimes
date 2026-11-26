package com.tunisianprayertimes.mosque.trace

import com.tunisianprayertimes.mosque.SavedSilenceArea
import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

private const val NEAR_ROUTE_METERS = 150.0

/** Include only currently enabled polygon components within 150 m of a recorded coordinate. */
internal fun nearbySilenceAreas(entries: List<TraceEntry>, areas: List<SavedSilenceArea>): List<SavedSilenceArea> {
    if (areas.none { it.geometry.enabled }) return emptyList()
    val fixes = entries.asSequence().filter { it.hasCoordinates() }
        .map { GeoPoint(it.latitude!!, it.longitude!!) }.distinct().sortedBy { it.latitude }.toList()
    if (fixes.isEmpty()) return emptyList()
    return areas.filter { it.geometry.enabled }.mapNotNull { area ->
        val polygons = area.geometry.polygons.filter { it.hasNearbyFix(fixes) }
        when (polygons.size) {
            0 -> null
            area.geometry.polygons.size -> area
            else -> area.copy(geometry = area.geometry.copy(polygons = polygons))
        }
    }
}

private fun SilencePolygon.hasNearbyFix(sorted: List<GeoPoint>): Boolean {
    // A conservative geographic window narrows expensive edge-distance checks to local fixes.
    // Presence geometry validates each individual polygon as small and non-dateline-crossing.
    val padding = NEAR_ROUTE_METERS / 110_000.0
    val south = exterior.minOf { it.latitude } - padding
    val north = exterior.maxOf { it.latitude } + padding
    val west = exterior.minOf { it.longitude }
    val east = exterior.maxOf { it.longitude }
    val longitudePadding = padding / cos(Math.toRadians(max(abs(south), abs(north))))
    // Find the first latitude >= south, including duplicates at the boundary.
    var low = 0
    var high = sorted.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (sorted[middle].latitude < south) low = middle + 1 else high = middle
    }
    for (index in low until sorted.size) {
        val point = sorted[index]
        if (point.latitude > north) break
        val longitude = west + ((point.longitude - west + 540) % 360 - 180)
        if (longitude !in (west - longitudePadding)..(east + longitudePadding)) continue
        val local = if (longitude == point.longitude) point else point.copy(longitude = longitude)
        if (contains(local) || boundaryDistance(local) <= NEAR_ROUTE_METERS) return true
    }
    return false
}
