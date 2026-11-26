package com.tunisianprayertimes.mosque

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.mosque.trace.SilenceTraceStore
import com.tunisianprayertimes.mosque.trace.SilenceWalkRecorderService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SilenceWalkIntegrationTest {
    @After fun releaseTraceDatabase() = SilenceTraceStore.closeForTest()

    @Test
    fun gpsWalkAndRealRuleChangesSurviveStopAndExportInTimeOrder() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val system = shadowOf(app)
        system.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val locations = app.getSystemService(LocationManager::class.java)
        val gps = shadowOf(locations)
        gps.setProviderEnabled(LocationManager.FUSED_PROVIDER, false)
        gps.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        gps.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        val notifications = app.getSystemService(NotificationManager::class.java)
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        assertTrue(SilenceCoordinator.enrollOrRepair(app))

        val main = shadowOf(Looper.getMainLooper())
        fun move(latitude: Double, longitude: Double) {
            main.idleFor(Duration.ofSeconds(10))
            gps.simulateLocation(Location(LocationManager.GPS_PROVIDER).apply {
                this.latitude = latitude
                this.longitude = longitude
                accuracy = 6f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
            main.idle()
        }

        assertTrue(SilenceWalkRecorderService.start(app))
        val start = requireNotNull(system.nextStartedService)
        val controller = Robolectric.buildService(SilenceWalkRecorderService::class.java, start).create()
        try {
            controller.get().onStartCommand(start, 0, 1)
            main.idle()
            val sessionId = requireNotNull(SilenceTraceStore.activeId(app))
            move(36.80000, 10.18000)
            assertTrue(SilenceCoordinator.setManual(app, true))
            move(36.80045, 10.18050)
            assertTrue(SilenceCoordinator.setManual(app, false))

            SilenceWalkRecorderService.stop(app, sessionId)
            system.nextStartedService?.let { controller.get().onStartCommand(it, 0, 2) }
            main.idle()
            assertNull(SilenceTraceStore.activeId(app))
            move(36.80100, 10.18100)
            val entries = SilenceTraceStore.entries(app, sessionId)
            val expected = listOf("recording_started", "route", "rule_requested", "route", "rule_released", "recording_stopped")
            assertEquals(expected, entries.map { it.kind }.filter { it in expected })

            val session = SilenceTraceStore.sessions(app).single { it.id == sessionId }
            assertNotNull(session.endedAt)
            val output = ByteArrayOutputStream()
            SilenceWalkExport.writeHtml(app, session, entries, output)
            val html = output.toString(Charsets.UTF_8.name())
            val geojson = Regex("""<script\b[^>]*\bid="trace"[^>]*>([\s\S]*?)</script>""")
                .find(html)?.groupValues?.get(1) ?: error("Missing embedded walk data")
            val array = JSONObject(geojson).getJSONArray("features")
            val features = (0 until array.length()).map(array::getJSONObject)
            fun kind(feature: JSONObject) = feature.getJSONObject("properties").getString("kind")
            val route = features.filter { kind(it) == "route" }
            assertEquals(2, route.size)
            assertEquals(10.18050, route.last().getJSONObject("geometry").getJSONArray("coordinates").getDouble(0), 0.000001)
            assertEquals(36.80045, route.last().getJSONObject("geometry").getJSONArray("coordinates").getDouble(1), 0.000001)
            val requested = features.single { kind(it) == "rule_requested" }
            assertTrue(requested.isNull("geometry"))
            assertEquals(app.getString(SilenceOwner.MANUAL.title), requested.getJSONObject("properties").getString("detail"))

            // Android can deliver the next start before destroying this service after its last stop.
            assertTrue(SilenceWalkRecorderService.start(app))
            controller.get().onStartCommand(requireNotNull(system.nextStartedService), 0, 3)
            val secondId = requireNotNull(SilenceTraceStore.activeId(app))
            assertTrue(secondId != sessionId)
            move(36.80200, 10.18200)
            val secondPoint = SilenceTraceStore.entries(app, secondId).single { it.kind == "route" }
            assertEquals(36.80200, requireNotNull(secondPoint.latitude), 0.000001)
            assertEquals(entries, SilenceTraceStore.entries(app, sessionId))
        } finally {
            controller.destroy()
        }
    }
}
