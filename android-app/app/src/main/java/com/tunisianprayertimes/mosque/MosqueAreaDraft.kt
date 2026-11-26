package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.SilenceArea
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import java.util.UUID

internal enum class BoundaryEditMode { VIEW, DRAW, ADJUST }

internal data class AreaDraft(
    val id: String?,
    val name: String,
    val coordinates: String,
    val enabled: Boolean = true,
    val key: String = UUID.randomUUID().toString(),
    val mode: BoundaryEditMode = BoundaryEditMode.VIEW,
    val fixedPolygons: List<SilencePolygon> = emptyList(),
    val cataloguePin: GeoPoint? = null,
) {
    fun withPolygons(polygons: List<SilencePolygon>): AreaDraft {
        val exterior = polygons.singleOrNull()?.takeIf { it.holes.isEmpty() }?.exterior
        return copy(mode = BoundaryEditMode.VIEW, coordinates = exterior?.let(::coordinateText).orEmpty(),
            fixedPolygons = if (exterior == null) polygons else emptyList())
    }
}

internal fun Mosque.approvedPolygons(): List<SilencePolygon> {
    if (!automaticSilencingEligible || !polygonEligible) return emptyList()
    return runCatching {
        require(polygons.size in 1..PrivateSilenceAreaStore.MAX_RINGS_OR_PARTS)
        polygons.map { polygon ->
            require(polygon.rings.size in 1..PrivateSilenceAreaStore.MAX_RINGS_OR_PARTS)
            val rings = polygon.rings.map { ring ->
                buildList {
                    ring.forEach { coordinate ->
                        val point = GeoPoint(coordinate.latitude, coordinate.longitude)
                        if (lastOrNull() != point) add(point)
                    }
                }.openOutline().also { require(it.size in 3..PrivateSilenceAreaStore.MAX_VERTICES) }
            }
            SilencePolygon(rings.first(), rings.drop(1))
        }.also { SilenceArea(id, 0, it) }
    }.getOrDefault(emptyList())
}

internal fun coordinateText(points: List<GeoPoint>) =
    points.openOutline().joinToString("\n") { "${it.latitude}, ${it.longitude}" }

internal fun List<GeoPoint>.openOutline() = if (size > 1 && first() == last()) dropLast(1) else this

internal fun insertBoundaryVertex(points: List<GeoPoint>, edge: Int, fraction: Double): List<GeoPoint>? {
    if (fraction <= 0.0 || fraction >= 1.0) return null
    val a = points[edge]
    val b = points[(edge + 1) % points.size]
    val point = GeoPoint(
        a.latitude + fraction * (b.latitude - a.latitude),
        a.longitude + fraction * (b.longitude - a.longitude),
    )
    if (point == a || point == b) return null
    return points.toMutableList().apply { add(edge + 1, point) }
}
