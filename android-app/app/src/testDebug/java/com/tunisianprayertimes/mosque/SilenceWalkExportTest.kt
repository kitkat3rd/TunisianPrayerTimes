package com.tunisianprayertimes.mosque

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import com.tunisianprayertimes.mosque.trace.TraceEntry
import com.tunisianprayertimes.mosque.trace.TraceSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SilenceWalkExportTest {
    @Test
    fun includesOnlyNearbyEnabledBoundaryPartsWithTheirNamesAndHolesAtExportTime() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun square(lat: Double, lon: Double, span: Double) = listOf(
            GeoPoint(lat, lon), GeoPoint(lat, lon + span),
            GeoPoint(lat + span, lon + span), GeoPoint(lat + span, lon),
        )
        val outer = square(36.0004, 10.0003, 0.0004)
        val hole = square(36.0005, 10.0004, 0.0001)
        val nearby = SilencePolygon(square(35.9995, 9.9995, 0.00015))
        val betweenFixes = SilencePolygon(square(36.025, 10.025, 0.0002))
        val name = "مسجد </script>"
        PrivateSilenceAreaStore.save(context, AreaDraft("near", name, ""),
            listOf(SilencePolygon(outer, listOf(hole)), nearby, betweenFixes))
        PrivateSilenceAreaStore.save(context, AreaDraft("distant", "بعيد", ""), listOf(betweenFixes))
        PrivateSilenceAreaStore.save(context, AreaDraft("disabled", "غير مفعّل", "", enabled = false), listOf(nearby))
        val entries = listOf(
            TraceEntry(1, 2000, "route", "gps", 36.0, 10.0, 4.0),
            TraceEntry(2, 3000, "route", "gps", 36.06, 10.06, 4.0),
        )
        val output = ByteArrayOutputStream()
        SilenceWalkExport.writeHtml(context, TraceSession(9, 1000, 4000), entries, output)
        val data = output.toString("UTF-8").substringAfter("type=\"application/geo+json\" id=\"trace\">")
            .substringBefore("</script>")
        assertFalse(data.contains("<"))
        val exported = JSONObject(data)
        assertEquals(2, exported.getJSONArray("features").length())
        val boundaries = exported.getJSONObject("boundaries")
        val features = boundaries.getJSONArray("features")
        assertEquals(1, features.length())
        val area = features.getJSONObject(0)
        assertEquals("near", area.getString("id"))
        val properties = area.getJSONObject("properties")
        assertEquals(name, properties.getString("name"))
        assertEquals("silence_boundary_at_export", properties.getString("kind"))
        assertEquals(boundaries.getLong("asOf"), properties.getLong("asOf"))
        val geometry = area.getJSONObject("geometry")
        assertEquals("MultiPolygon", geometry.getString("type"))
        val components = geometry.getJSONArray("coordinates")
        assertEquals(2, components.length())
        val first = components.getJSONArray(0)
        assertEquals(2, first.length())
        val exportedHole = first.getJSONArray(1)
        assertEquals(5, exportedHole.length())
        assertEquals(exportedHole.getJSONArray(0).toString(), exportedHole.getJSONArray(4).toString())
        assertEquals(hole.first().longitude, exportedHole.getJSONArray(0).getDouble(0), 0.0)
    }

    @Test
    fun exportsTimedWgs84PointsAndUnlocatedEventsWithoutExecutingUserText() {
        val detail = """اسم "</ScRiPt><script>test()</script>" """
        val entries = listOf(
            TraceEntry(2, 2000, "test_arrived", detail, null, null, null),
            TraceEntry(1, 2000, "route", "gps", 36.812, 10.188, 4.5),
            TraceEntry(3, 18000, "test_left", "", null, null, null),
        )
        val output = ByteArrayOutputStream()
        val session = TraceSession(9, 1000, 19000, "Africa/Tunis")
        SilenceWalkExport.writeHtml(ApplicationProvider.getApplicationContext<Context>(), session, entries, output)
        val html = output.toString("UTF-8")
        val data = html.substringAfter("type=\"application/geo+json\" id=\"trace\">").substringBefore("</script>")
        assertFalse(data.contains("<", ignoreCase = true))
        val trace = JSONObject(data)
        assertEquals("FeatureCollection", trace.getString("type"))
        assertEquals("Africa/Tunis", trace.getJSONObject("session").getString("timeZone"))
        val features = trace.getJSONArray("features")
        assertEquals(3, features.length())
        val fix = features.getJSONObject(0)
        assertEquals(1, fix.getLong("id"))
        val point = fix.getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(10.188, point.getDouble(0), 0.0)
        assertEquals(36.812, point.getDouble(1), 0.0)
        assertEquals(4.5, fix.getJSONObject("properties").getDouble("accuracyMeters"), 0.0)
        val event = features.getJSONObject(1)
        assertTrue(event.isNull("geometry"))
        assertEquals(detail, event.getJSONObject("properties").getString("detail"))
        assertEquals(2000L, event.getJSONObject("properties").getLong("at"))
        assertEquals(1L, event.getJSONObject("properties").getLong("routeContextId"))
        assertFalse(features.getJSONObject(2).getJSONObject("properties").has("routeContextId"))
    }
}
