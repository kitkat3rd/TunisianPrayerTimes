package com.tunisianprayertimes.mosque.presence

import kotlin.math.hypot

internal const val MAX_WAKE_CIRCLES = 100

/** Android's circular wake region around one saved polygon. The polygon alone supplies visit evidence. */
internal data class MosqueWakeCircle(val center: GeoPoint, val radius: Float) {
    fun overlaps(fix: PresenceFix): Boolean {
        val (east, north) = project(fix.point, center)
        return hypot(east, north) <= radius.toDouble() + requireNotNull(fix.accuracyMeters)
    }
}

internal fun mosqueWakeCircles(areas: List<SilenceArea>): List<MosqueWakeCircle> =
    areas.filter { it.enabled }.flatMap { area ->
        area.polygons.map { polygon ->
            val vertices = polygon.exterior
            val center = GeoPoint(vertices.map { it.latitude }.average(), vertices.map { it.longitude }.average())
            val outerRadius = vertices.maxOf { vertex ->
                val (east, north) = project(vertex, center)
                hypot(east, north)
            }
            MosqueWakeCircle(center, maxOf(100.0, outerRadius + 30.0).toFloat())
        }
    }
