package com.tunisianprayertimes.mosque

import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.tunisianprayertimes.mosque.trace.SilenceTraceStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, shadows = [AvailablePlayServices::class],
    instrumentedPackages = ["com.google.android.gms.common"])
class SilenceWalkIntegrationTest : MosqueGraceFixture() {
    @After fun releaseTraceDatabase() {
        MosqueSilenceMonitor.environmentChanged(context)
        shadowOf(Looper.getMainLooper()).idle()
        SilenceTraceStore.closeForTest()
    }

    @Test fun observationRouteAndRuleEventsCanBeExportedAfterTheDetectorStops() {
        val main = shadowOf(Looper.getMainLooper())
        val gps = shadowOf(context.getSystemService(LocationManager::class.java))
        gps.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        gps.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        fun point(latitude: Double, longitude: Double) = Location(LocationManager.GPS_PROVIDER).apply {
            this.latitude = latitude
            this.longitude = longitude
            accuracy = 6f
            time = wall.toEpochMilli()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

        begin()
        val sessionId = requireNotNull(SilenceTraceStore.activeId(context))
        assertTrue(gps.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).isEmpty())
        DetectionRecording.foreground(context, token)
        main.idle()
        val oldListener = gps.getLocationUpdateListeners(LocationManager.GPS_PROVIDER).single()
        gps.simulateLocation(point(0.00050, 0.00040))
        main.idle()
        confirm()
        gps.simulateLocation(point(0.00055, 0.00065))
        main.idle()

        MosqueSilenceMonitor.environmentChanged(context)
        assertNull(SilenceTraceStore.activeId(context))
        val entries = SilenceTraceStore.entries(context, sessionId)
        val expected = listOf("recording_started", "route", "rule_requested", "route", "rule_released", "recording_stopped")
        assertEquals(expected, entries.map { it.kind }.filter { it in expected })
        val session = SilenceTraceStore.sessions(context).single { it.id == sessionId }
        assertNotNull(session.endedAt)

        val output = ByteArrayOutputStream()
        SilenceWalkExport.writeHtml(context, session, entries, output)
        val html = output.toString(Charsets.UTF_8.name())
        val geojson = Regex("""<script\b[^>]*\bid="trace"[^>]*>([\s\S]*?)</script>""")
            .find(html)?.groupValues?.get(1) ?: error("Missing embedded walk data")
        val array = JSONObject(geojson).getJSONArray("features")
        val features = (0 until array.length()).map(array::getJSONObject)
        fun kind(feature: JSONObject) = feature.getJSONObject("properties").getString("kind")
        val route = features.filter { kind(it) == "route" }
        assertEquals(2, route.size)
        val lastPoint = route.last().getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(0.00065, lastPoint.getDouble(0), 0.000001)
        assertEquals(0.00055, lastPoint.getDouble(1), 0.000001)
        val requested = features.single { kind(it) == "rule_requested" }
        assertTrue(requested.isNull("geometry"))
        assertEquals(context.getString(SilenceOwner.MOSQUE.title), requested.getJSONObject("properties").getString("detail"))

        // A new observation can start while callbacks for its predecessor are still in Android's queue.
        val nextPlan = requireNotNull(AdaptiveMosqueMonitor.reconcile(context, canStart = true))
        val secondId = requireNotNull(SilenceTraceStore.activeId(context))
        assertTrue(secondId != sessionId)
        DetectionRecording.foreground(context, nextPlan.token)
        main.idle()
        DetectionRecording.serviceGone(context, token)
        oldListener.onLocationChanged(point(0.00100, 0.00100))
        at(-1)
        gps.simulateLocation(point(0.00120, 0.00120))
        main.idle()
        val secondPoint = SilenceTraceStore.entries(context, secondId).single { it.kind == "route" }
        assertEquals(0.00120, requireNotNull(secondPoint.latitude), 0.000001)
        assertEquals(entries, SilenceTraceStore.entries(context, sessionId))
    }
}
