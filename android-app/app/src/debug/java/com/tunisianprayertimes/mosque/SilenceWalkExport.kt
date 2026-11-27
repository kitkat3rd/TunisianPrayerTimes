package com.tunisianprayertimes.mosque

import android.content.Context
import com.tunisianprayertimes.mosque.trace.TRACE_ROUTE_MAX_GAP_MS
import com.tunisianprayertimes.mosque.trace.TraceEntry
import com.tunisianprayertimes.mosque.trace.TraceSession
import com.tunisianprayertimes.mosque.trace.TraceSteps
import com.tunisianprayertimes.mosque.trace.breaksRouteContinuity
import com.tunisianprayertimes.mosque.trace.displayTitle
import com.tunisianprayertimes.mosque.trace.hasCoordinates
import com.tunisianprayertimes.mosque.trace.nearbyRouteIndices
import com.tunisianprayertimes.mosque.trace.nearbySilenceAreas
import com.tunisianprayertimes.mosque.trace.stepDelta
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.io.Writer

object SilenceWalkExport {
    private const val TEMPLATE = "mosques/silence-walk.html"
    private const val DATA_MARKER = "<!--TRACE_GEOJSON-->"

    /** Writes a standalone offline viewer. The caller owns the destination stream. */
    fun writeHtml(context: Context, session: TraceSession, entries: List<TraceEntry>, output: OutputStream) {
        val writer = output.bufferedWriter(Charsets.UTF_8)
        val capturedAt = System.currentTimeMillis()
        val areaRead = PrivateSilenceAreaStore.read(context)
        val boundaries = JSONObject().put("type", "FeatureCollection").put("asOf", capturedAt)
            .put("available", areaRead.available).put("partial", areaRead.rejected > 0)
            .put("features", JSONArray().apply {
                nearbySilenceAreas(entries, areaRead.areas).forEach { area ->
                    val feature = PrivateSilenceAreaStore.geoJsonFeature(area)
                    feature.getJSONObject("properties").put("kind", "silence_boundary_at_export")
                        .put("asOf", capturedAt).put("revision", area.geometry.revision)
                    put(feature)
                }
            })
        context.assets.open(TEMPLATE).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (line == DATA_MARKER) writeGeoJson(writer, session, entries, boundaries) else writer.write(line)
                writer.write("\n")
            }
        }
        writer.flush()
    }

    private fun writeGeoJson(writer: Writer, session: TraceSession, entries: List<TraceEntry>, boundaries: JSONObject) {
        val timeline = entries.sortedWith(compareBy(TraceEntry::at, TraceEntry::id))
        val steps = TraceSteps(timeline)
        val metadata = JSONObject()
            .put("id", session.id)
            .put("startedAt", session.startedAt)
            .put("endedAt", session.endedAt ?: JSONObject.NULL)
            .put("timeZone", session.timeZoneId)
            .put("steps", JSONObject().put("recordedCount", steps.recordedCount ?: JSONObject.NULL)
                .put("sources", JSONArray(steps.sources)).put("interrupted", steps.interrupted)
                .put("summary", steps.summary))
        writer.write("""{"type":"FeatureCollection","schemaVersion":1,"session":""")
        writer.write(scriptSafeJson(metadata))
        writer.write(""","boundaries":""")
        writer.write(scriptSafeJson(boundaries))
        writer.write(""","routeMaxGapMs":$TRACE_ROUTE_MAX_GAP_MS,"features":[""")
        val nearbyRoute = nearbyRouteIndices(timeline)
        // Write one feature at a time so a long walk doesn't require a second copy as a JSON tree.
        timeline.forEachIndexed { index, entry ->
            if (index > 0) writer.write(",")
            val geometry = if (entry.hasCoordinates()) {
                JSONObject().put("type", "Point").put("coordinates", JSONArray().put(entry.longitude).put(entry.latitude))
            } else JSONObject.NULL
            val properties = JSONObject()
                .put("at", entry.at)
                .put("kind", entry.kind)
                .put("title", entry.displayTitle)
                .put("detail", entry.detail)
                .put("accuracyMeters", entry.accuracyMeters?.takeIf { it.isFinite() && it >= 0 } ?: JSONObject.NULL)
            if (entry.breaksRouteContinuity) properties.put("breaksRouteContinuity", true)
            entry.stepDelta?.let { properties.put("stepDelta", it) }
            steps.intervalStart(entry)?.let { properties.put("stepIntervalStart", it) }
            steps.recordedThrough(index)?.let { properties.put("recordedStepsThrough", it) }
            if (nearbyRoute[index] >= 0) properties.put("routeContextId", timeline[nearbyRoute[index]].id)
            writer.write(scriptSafeJson(JSONObject()
                .put("type", "Feature").put("id", entry.id)
                .put("geometry", geometry).put("properties", properties)))
        }
        writer.write("]}")
    }

    // A name or diagnostic containing </script> must survive in data without ending the HTML data block.
    private fun scriptSafeJson(value: JSONObject): String = value.toString().replace("<", "\\u003c")
}
