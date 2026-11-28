package com.tunisianprayertimes.mosque

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.BuildConfig
import com.tunisianprayertimes.FirstRunPlaceChoice
import com.tunisianprayertimes.Locality
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.silence.EndResolution
import com.tunisianprayertimes.silence.SilenceWindow
import java.time.Instant
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MosqueMetricsTest {
    private val c: Context
        get() = ApplicationProvider.getApplicationContext()

    private val store
        get() = MosqueReportStore.get(c)

    private val day = LocalDate.of(2026, 11, 28)
    private val start = Instant.parse("2026-11-28T05:00:00Z")
    private val end = start.plusSeconds(1800)

    private fun window(prayer: Prayer = Prayer.FAJR) =
        SilenceWindow(prayer, day, start, start, end, EndResolution.AS_SET, false)

    private fun reports(): List<JSONObject> {
        MosqueMetrics.awaitWrites()
        return store.keys("SELECT body FROM outbox WHERE kind='metrics'").map(::JSONObject)
    }

    @Before
    fun prepare() {
        PrefsManager.initializeFirstRun(c)
        PrefsManager.completeFirstRun(
            c,
            FirstRunPlaceChoice.Local(Locality("sfax", "صفاقس", "", 1, 386, "صفاقس")),
        )
        store.clearMetrics()
        store.db.execSQL("DELETE FROM local")
        assertTrue(
            MosqueReportStore.permitted(c) || BuildConfig.MOSQUE_REPORT_CHANNEL == "production"
        )
    }

    @Test
    fun preciseWalkRequiresNewConsentAndKeepsOnlyNearbyGeometry() {
        if (runCatching { androidx.work.WorkManager.getInstance(c) }.isFailure)
            androidx.work.WorkManager.initialize(c, androidx.work.Configuration.Builder().build())
        val sharing = MosqueWalkSharing
        assertEquals(0L, sharing.since(c))
        sharing.setEnabled(c,true)
        val since = sharing.since(c)
        val at = since + 660_000
        val begin = Instant.ofEpochMilli(at)
        val next = SilenceWindow(Prayer.DHUHR,day,begin,begin,begin.plusSeconds(120),EndResolution.AS_SET,false)
        val earlier = next.copy(adhan=Instant.ofEpochMilli(since-1))
        val pt = com.tunisianprayertimes.mosque.presence.GeoPoint(48.01,2.01)
        fun area(x: Double, id: String): com.tunisianprayertimes.mosque.presence.SilenceArea {
            val ring = listOf(pt.copy(longitude=x),pt.copy(longitude=x+0.001),pt.copy(latitude=48.011,longitude=x))
            return com.tunisianprayertimes.mosque.presence.SilenceArea(id,1,listOf(com.tunisianprayertimes.mosque.presence.SilencePolygon(ring)))
        }
        val areas = listOf(area(2.01,"private near"),area(3.01,"private far"))
        val fix = com.tunisianprayertimes.mosque.presence.PresenceFix(pt,7.0,1000)
        fun scope(w: SilenceWindow) = sharing.scope(c,MosquePeriods(null,null,listOf(w)))
        fun close() {
            sharing.scope(c,MosquePeriods(null,null,emptyList()))
            sharing.finish(c,at+121_000)
            sharing.awaitWrites()
        }
        try {
            scope(earlier); sharing.fix(c,fix,at,true,areas); close()
            assertTrue(store.keys("SELECT id FROM outbox WHERE kind='walk'").isEmpty())
            scope(next); sharing.fix(c,fix,at,true,areas)
            sharing.route(c,at+10,2.01,48.01,8.0)
            sharing.event(c,at+20,"activation")
            close()
            val body = store.keys("SELECT body FROM outbox WHERE kind='walk'").single()
            val detail = JSONObject(body).getJSONObject("fields")
            assertEquals(2,detail.getJSONArray("points").length())
            assertEquals(1,detail.getJSONArray("boundaries").length())
            assertEquals("activation",detail.getJSONArray("events").getJSONArray(0).getString(1))
            assertFalse(body.contains("private near"))
            assertTrue(store.keys("SELECT window FROM walk_samples").isEmpty())
        } finally { sharing.setEnabled(c,false) }
        assertTrue(store.keys("SELECT id FROM outbox WHERE kind='walk'").isEmpty())
        assertTrue(store.keys("SELECT id FROM receipts WHERE kind='walk' AND status='queued'").isEmpty())
    }

    @Test
    fun developerFieldTestCanReportInFranceWhileHonouringAnExplicitOptOut() {
        val phone = c.getSystemService(android.telephony.TelephonyManager::class.java)
        org.robolectric.Shadows.shadowOf(phone).setSimCountryIso("fr")
        val marker = java.io.File(c.noBackupFilesDir, "mosque-report-field-test")
        store.put("participation", "on")
        assertFalse(MosqueReportStore.permitted(c))
        assertTrue(marker.createNewFile())
        try {
            assertEquals(BuildConfig.DEBUG, MosqueReportStore.permitted(c))
            store.put("participation", "off")
            assertFalse(MosqueReportStore.permitted(c))
        } finally {
            assertTrue(marker.delete())
        }
    }

    @Test
    fun defaultsAndExplicitChoice() {
        assertEquals(
            BuildConfig.MOSQUE_REPORT_CHANNEL in setOf("dev", "beta"),
            MosqueReportStore.enabled(c),
        )
        store.put("participation", "off")
        MosqueMetrics.scheduled(c, listOf(window()), start.minusSeconds(700).toEpochMilli())
        assertTrue(reports().isEmpty())
        assertTrue(store.keys("SELECT key FROM windows").isEmpty())
        store.put("participation", "on")
        assertTrue(MosqueReportStore.enabled(c))
    }

    @Test
    fun faultPrecedesOneFinalSummaryAndPreservesOriginalDeviceAndPrayer() {
        store.put("participation", "on")
        val w = window()
        MosqueMetrics.scheduled(c, listOf(w), start.minusSeconds(700).toEpochMilli())
        MosqueMetrics.scope(
            c,
            MosquePeriods(null, null, listOf(w)),
            start.toEpochMilli(),
            true,
            emptyList(),
        )
        MosqueMetrics.started(c, start.toEpochMilli())
        MosqueMetrics.fix(c, true, 12.0, 100, true, start.toEpochMilli())
        MosqueMetrics.rule(c, true, false, start.plusSeconds(1).toEpochMilli())
        MosqueMetrics.rule(c, true, false, start.plusSeconds(3).toEpochMilli())
        val early = reports().single()
        assertEquals("fault", early.getString("kind"))
        MosqueMetrics.finish(c, end.toEpochMilli())
        MosqueMetrics.finish(c, end.plusSeconds(2).toEpochMilli())
        val result = reports().single { it.getString("kind") == "summary" }
        assertEquals("FAJR", result.getString("prayer"))
        assertEquals(day.toString(), result.getString("day"))
        assertEquals(2, result.getJSONObject("counts").getInt("activation_failed"))
        assertEquals(1, result.getJSONObject("counts").getInt("expected"))
        assertEquals(early.getString("device_model"), result.getString("device_model"))
        assertEquals(35, result.getInt("android_api_level"))
        assertFalse(result.has("coordinates"))
    }

    @Test
    fun overlappingWindowsAttributeOneAndroidWriteToSharedCoverage() {
        store.put("participation","on")
        val both = listOf(window(), window(Prayer.ASR))
        MosqueMetrics.scheduled(c, both, start.minusSeconds(700).toEpochMilli())
        MosqueMetrics.scope(c, MosquePeriods(null, null, both), start.toEpochMilli(), true, emptyList())
        MosqueMetrics.rule(c, true, true, start.toEpochMilli())
        MosqueMetrics.rule(c, false, true, end.toEpochMilli())
        MosqueMetrics.finish(c, end.toEpochMilli())
        val summaries = reports().filter { it.getString("kind") == "summary" }
        assertEquals(1, summaries.sumOf { it.getJSONObject("counts").optInt("activation_ok") })
        assertEquals(1, summaries.single { it.getString("prayer") == "SHARED" }.getJSONObject("counts").getInt("release_ok"))
        assertEquals(2, summaries.sumOf { it.getJSONObject("counts").optInt("expected") })
    }

    @Test
    fun graceDoesNotAlertEarlyAndOverdueCleanupDoesNotDuplicateTheSummary() {
        store.put("participation","on")
        val w = window()
        MosqueMetrics.scheduled(c,listOf(w),start.minusSeconds(700).toEpochMilli())
        MosqueMetrics.scope(c,MosquePeriods(null,null,listOf(w)),start.toEpochMilli(),true,emptyList())
        MosqueMetrics.rule(c,true,true,start.toEpochMilli())
        MosqueMetrics.hold(c,true,null)
        MosqueMetrics.finish(c,end.toEpochMilli())
        assertTrue(reports().isEmpty())
        val hard=end.plusSeconds(900).toEpochMilli()
        MosqueMetrics.rule(c,false,false,hard)
        MosqueMetrics.finish(c,hard)
        MosqueMetrics.finish(c,hard+5000)
        MosqueMetrics.rule(c,false,true,hard+6000,cleanup=true)
        MosqueMetrics.finish(c,hard+6000)
        val all=reports()
        assertEquals(1,all.count {it.getString("kind")=="summary"})
        assertEquals(1,all.count {it.getString("kind")=="fault" && it.getJSONObject("counts").has("release_overdue")})
        assertEquals(1,all.count {it.getString("kind")=="recovery"})
    }

    @Test
    fun missingAndSkippedWindowsAreDifferent() {
        store.put("participation", "on")
        MosqueMetrics.scheduled(
            c,
            listOf(window(), window(Prayer.ASR)),
            start.minusSeconds(700).toEpochMilli(),
        )
        MosqueMetrics.dismiss(c, listOf("ASR@" + day))
        MosqueMetrics.finish(c, end.toEpochMilli())
        val summary = reports().associate { it.getString("prayer") to it.getJSONObject("counts") }
        assertEquals(1, summary.getValue("FAJR").getInt("missed_start"))
        assertEquals(0, summary.getValue("ASR").optInt("missed_start"))
    }
}
