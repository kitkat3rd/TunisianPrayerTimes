package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import com.tunisianprayertimes.mosque.presence.singleExteriorCrossing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExteriorCrossingTest {
    private fun point(east: Int, north: Int) = GeoPoint(north / 10_000.0, east / 10_000.0)
    private val outer = listOf(point(0, 0), point(8, 0), point(8, 8), point(0, 8))

    @Test fun bothDirectionsIdentifyTheSamePlaceAndEdgeOfOpenOrClosedRings() {
        for (ring in listOf(outer, outer + outer.first())) {
            val polygon = SilencePolygon(ring)
            val a = point(-2, 1)
            val b = point(2, 3)
            for ((from, to) in listOf(a to b, b to a)) {
                val crossing = requireNotNull(polygon.singleExteriorCrossing(from, to))
                assertEquals(3, crossing.segmentIndex)
                assertEquals(point(0, 2).latitude, crossing.point.latitude, 1e-12)
                assertEquals(0.0, crossing.point.longitude, 1e-12)
            }
        }
    }

    @Test fun touchingOrTravellingAlongTheBoundaryCannotIdentifyADoorway() {
        val polygon = SilencePolygon(outer)
        assertNull(polygon.singleExteriorCrossing(point(-1, -1), point(2, 2)))
        assertNull(polygon.singleExteriorCrossing(point(-1, 0), point(5, 0)))
        assertNull(polygon.singleExteriorCrossing(point(0, 4), point(1, 4)))
        assertNull(polygon.singleExteriorCrossing(point(2, 4), point(5, 4)))
    }

    @Test fun aChordCrossingACourtyardOrMoreThanOneExteriorEdgeIsAmbiguous() {
        val courtyard = listOf(point(3, 3), point(5, 3), point(5, 5), point(3, 5))
        val polygon = SilencePolygon(outer, listOf(courtyard))
        assertNull(polygon.singleExteriorCrossing(point(-1, 4), point(6, 4)))
        assertNull(polygon.singleExteriorCrossing(point(-1, 2), point(9, 2)))

        val wings = SilencePolygon(outer.take(3) + listOf(point(6, 8), point(6, 2),
            point(2, 2), point(2, 8), point(0, 8)))
        assertNull(wings.singleExteriorCrossing(point(-1, 5), point(7, 5)))
    }
}
