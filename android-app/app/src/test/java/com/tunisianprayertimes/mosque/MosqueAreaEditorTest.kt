package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import com.tunisianprayertimes.mosque.presence.firstBoundaryCrossing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MosqueAreaEditorTest {
    @Test
    fun `an open trace can continue past a provisional crossing at closure`() {
        val partial = listOf(point(0, 0), point(40, 0), point(40, 40), point(60, 40))
        assertNull(firstBoundaryCrossing(partial, closed = false))
        assertEquals(1 to 3, firstBoundaryCrossing(partial, closed = true))

        val finished = partial + listOf(point(60, -20), point(-20, -20))
        assertNull(firstBoundaryCrossing(finished, closed = true))
        assertEquals(finished, SilencePolygon(finished).exterior)
    }

    @Test
    fun `edge insertion stays on the original segment and preserves the covered area`() {
        val points = rectangle(0, 0, 120, 80)
        val updated = checkNotNull(insertBoundaryVertex(points, edge = 0, fraction = 0.5))
        assertEquals(point(60, 0).latitude, updated[1].latitude, 1e-12)
        assertEquals(point(60, 0).longitude, updated[1].longitude, 1e-12)
        assertNull(insertBoundaryVertex(points, edge = 0, fraction = 0.0))
        assertNull(insertBoundaryVertex(points, edge = 0, fraction = 1.0))
        val polygon = SilencePolygon(updated)
        assertTrue(polygon.contains(point(60, 5)))
        assertTrue(!polygon.contains(point(60, -5)))
    }

    @Test
    fun `catalogue import preserves multiple parts and holes and drops consecutive duplicate vertices`() {
        val outer = rectangle(0, 0, 120, 80)
        val repeated = listOf(outer.first()) + outer
        val hole = rectangle(15, 15, 30, 30)
        val second = rectangle(130, 0, 160, 80)
        val mosque = Mosque(
            "reviewed", "Reviewed", null, null, null, "mapped_polygon",
            polygonEligible = true, automaticSilencingEligible = true, boundaryCoverageStatus = "reviewed",
            polygons = listOf(MosquePolygon(listOf(repeated.map(::coordinate), hole.map(::coordinate))),
                MosquePolygon(listOf(second.map(::coordinate)))),
        )
        val expected = listOf(SilencePolygon(outer, listOf(hole)), SilencePolygon(second))
        val actual = mosque.approvedPolygons()
        assertEquals(expected, actual)
        val draft = AreaDraft("catalogue:reviewed", "Reviewed", "").withPolygons(actual)
        assertEquals(expected, draft.fixedPolygons)
        assertEquals("", draft.coordinates)
        assertNotNull(draft.id)
    }

    private fun point(x: Int, y: Int) = GeoPoint(36.795 + y * 0.000001, 10.171 + x * 0.000001)
    private fun coordinate(point: GeoPoint) = MosqueCoordinate(point.longitude, point.latitude)
    private fun rectangle(x1: Int, y1: Int, x2: Int, y2: Int) = listOf(point(x1, y1), point(x2, y1), point(x2, y2), point(x1, y2))
}
