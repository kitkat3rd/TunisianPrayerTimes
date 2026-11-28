package com.tunisianprayertimes.mosque

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.presence.AdaptiveObservation
import com.tunisianprayertimes.mosque.presence.AdaptiveVisitDetector
import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.PresenceFix
import com.tunisianprayertimes.mosque.presence.SilenceArea
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import com.tunisianprayertimes.mosque.trace.SilenceTraceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MosqueObservationTraceTest {
    @After fun releaseTraceDatabase() = SilenceTraceStore.closeForTest()

    @Test fun walkingEntryNamesTheCrossingAndKeepsTheFixThatConfirmedIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = SilenceTraceStore.start(context)
        try {
            // Invented equatorial metre grid with its entrance at east = 0.
            fun point(east: Int, north: Int = 0) = GeoPoint(north / 110_574.0, east / 111_319.5)
            val polygon = SilencePolygon(listOf(point(0, -40), point(100, -40), point(100, 40), point(0, 40)))
            val area = SavedSilenceArea("جامع الاختبار", SilenceArea("walk-hall", 1, listOf(polygon)))
            val engine = AdaptiveObservation(listOf(area.geometry), 0)
            engine.start(0, forceBurst = true)
            engine.period(true, 0)
            val route = listOf(0 to -42, 4 to -31, 8 to -20, 20 to 11, 23 to 15, 26 to 20, 29 to 24)
            for ((seconds, east) in route) {
                val fix = PresenceFix(point(east), 5.0, seconds * 1_000L)
                assertTrue(engine.observe(fix, fix.elapsedRealtimeMillis))
                MosqueObservationTrace.engine(context, id, engine, listOf(area), fix)
            }

            assertEquals(AdaptiveVisitDetector.Arrival.WALKED_IN, engine.detector.arrival)
            val events = SilenceTraceStore.entries(context, id).filter { it.kind == "mosque_presence" }
            val entering = events.single { it.detail == context.getString(R.string.mosque_status_entering) }
            assertEquals(point(11).longitude, requireNotNull(entering.longitude), 0.0)
            val event = events.single { area.name in it.detail }
            assertTrue(entering.id < event.id)
            assertEquals(context.getString(R.string.trace_mosque_arrival, area.name,
                context.getString(R.string.trace_mosque_walked_in)), event.detail)
            val confirmingPoint = point(route.last().second)
            assertEquals(confirmingPoint.latitude, requireNotNull(event.latitude), 0.0)
            assertEquals(confirmingPoint.longitude, requireNotNull(event.longitude), 0.0)
            assertEquals(5.0, requireNotNull(event.accuracyMeters), 0.0)
        } finally {
            SilenceTraceStore.stop(context, id)
            SilenceTraceStore.delete(context, id)
        }
    }

    @Test fun namedArrivalCarriesItsEvidencePointAndTimedExpiryHasNoCoordinates() {
        val c = ApplicationProvider.getApplicationContext<Context>()
        val id = SilenceTraceStore.start(c)
        try {
            val center = GeoPoint(36.80, 10.18)
            val shape = SilencePolygon(listOf(GeoPoint(36.799, 10.179), GeoPoint(36.801, 10.179),
                GeoPoint(36.801, 10.181), GeoPoint(36.799, 10.181)))
            val area = SilenceArea("test-area", 1, listOf(shape))
            val areas = listOf(SavedSilenceArea("جامع الاختبار", area))
            val engine = AdaptiveObservation(listOf(area), 0)
            engine.period(true, 0)
            engine.start(0)
            MosqueObservationTrace.engine(c, id, engine, areas)
            for (t in listOf(0L, 8_000L, 16_000L)) {
                val fix = PresenceFix(center, 4.0, t)
                assertTrue(engine.observe(fix, t))
                MosqueObservationTrace.engine(c, id, engine, areas, fix)
                MosqueObservationTrace.engine(c, id, engine, areas)
            }
            engine.tick(316_000)
            MosqueObservationTrace.engine(c, id, engine, areas)
            val entries = SilenceTraceStore.entries(c, id)
            val arrival = entries.single { it.kind == "mosque_presence" && areas.single().name in it.detail }
            assertEquals(center.latitude, requireNotNull(arrival.latitude), 0.0)
            assertEquals(center.longitude, requireNotNull(arrival.longitude), 0.0)
            assertEquals(4.0, requireNotNull(arrival.accuracyMeters), 0.0)
            val decisions = entries.filter { it.kind == "mosque_decision" }
            assertEquals(3, decisions.size)
            assertNull(decisions.first().latitude)
            assertEquals(center.latitude, requireNotNull(decisions[1].latitude), 0.0)
            assertNull(decisions.last().latitude)
            assertNull(entries.single { it.kind == "mosque_visit_ended" }.latitude)
        } finally {
            SilenceTraceStore.stop(c, id)
            SilenceTraceStore.delete(c, id)
        }
    }
}
