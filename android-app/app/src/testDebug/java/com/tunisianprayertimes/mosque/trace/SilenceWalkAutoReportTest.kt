package com.tunisianprayertimes.mosque.trace

import android.Manifest
import android.app.Application
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SilenceWalkAutoReportTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After fun closeStore() = SilenceTraceStore.closeForTest()

    private fun trace(file: File): JSONObject {
        val html = file.readText()
        assertTrue("Only complete HTML should be visible to a pull", html.trimEnd().endsWith("</html>"))
        return JSONObject(html.substringAfter("type=\"application/geo+json\" id=\"trace\">").substringBefore("</script>"))
    }

    private fun kinds(trace: JSONObject): List<String> {
        val features = trace.getJSONArray("features")
        return List(features.length()) { features.getJSONObject(it).getJSONObject("properties").getString("kind") }
    }

    @Test fun stoppingRecordingCreatesACompleteOfflineReportWithoutOpeningTheReportsScreen() {
        // Wait for the separate startup scan before creating the walk under test.
        val old = TraceDatabase(app).use { db ->
            db.start(1_000L).also { db.stop(it, 2_000L) }
        }
        SilenceWalkReports.install(app)
        awaitReport(old)

        val id = SilenceTraceStore.start(app)
        val html = SilenceWalkReports.file(app, id)
        assertEquals(File(app.noBackupFilesDir, "silence-walk-reports/silence-walk-$id.html"), html)
        assertFalse(html.exists())
        assertTrue(SilenceTraceStore.append(app, "route", "gps", latitude = 36.8, longitude = 10.1,
            accuracyMeters = 4.0, sessionId = id))
        assertTrue(SilenceTraceStore.append(app, "rule_released", "مسجد", sessionId = id))
        SilenceTraceStore.stop(app, id)

        val json = trace(awaitReport(id))
        val metadata = json.getJSONObject("session")
        assertEquals(id, metadata.getLong("id"))
        assertEquals(SilenceTraceStore.sessions(app).first { it.id == id }.endedAt,
            metadata.getLong("endedAt"))
        assertEquals(listOf("recording_started", "route", "rule_released", "recording_stopped"), kinds(json))
        val coordinates = json.getJSONArray("features").getJSONObject(1)
            .getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(10.1, coordinates.getDouble(0), 0.0)
        assertEquals(36.8, coordinates.getDouble(1), 0.0)
    }

    @Test fun coldRecoveryBackfillsEqualStartTimesAndPreservesAnActiveWalkAndExistingHtml() {
        val (first, second) = TraceDatabase(app).use { db ->
            val first = db.start(1_000L)
            db.stop(first, 2_000L)
            val second = db.start(1_000L)
            assertTrue(db.append(second, 1_500L, "route", "gps", 36.8, 10.1, 5.0))
            first to second // Simulate process death before the second walk's stop callback.
        }

        SilenceWalkReports.reconcile(app)
        val saved = SilenceWalkReports.file(app, first)
        val interrupted = SilenceWalkReports.file(app, second)
        assertNotEquals(saved, interrupted)
        assertEquals(listOf("recording_started", "recording_stopped"), kinds(trace(saved)))
        val recovered = trace(interrupted)
        assertEquals(1_500L, recovered.getJSONObject("session").getLong("endedAt"))
        assertEquals(listOf("recording_started", "route", "recording_interrupted"), kinds(recovered))
        assertTrue(saved.setLastModified(1_600_000_000_000L))
        val modified = saved.lastModified()
        val active = SilenceTraceStore.start(app)

        SilenceWalkReports.reconcile(app)
        assertEquals(modified, saved.lastModified())
        assertEquals(active, SilenceTraceStore.activeId(app))
        assertFalse(SilenceWalkReports.file(app, active).exists())
    }

    @Test fun failedSaveCanBeRetriedAndDeletingAWalkAlsoRemovesItsHtml() {
        val (keep, remove) = TraceDatabase(app).use { db ->
            val keep = db.start(1_000L)
            db.stop(keep, 2_000L)
            val remove = db.start(3_000L)
            db.stop(remove, 4_000L)
            keep to remove
        }
        val html = SilenceWalkReports.file(app, remove)
        val directory = requireNotNull(html.parentFile)
        assertTrue(directory.createNewFile()) // Block mkdir, even for hosts with administrator rights.
        fun runWorker() = TestListenableWorkerBuilder<SilenceWalkReportWorker>(app).build().doWork()
        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertFalse(html.exists())
        assertEquals(setOf(keep, remove), SilenceTraceStore.sessions(app).map { it.id }.toSet())
        assertTrue(directory.delete())

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(remove, trace(html).getJSONObject("session").getLong("id"))
        SilenceTraceStore.delete(app, remove)
        val staleRequest = TestListenableWorkerBuilder<SilenceWalkReportWorker>(app)
            .setInputData(workDataOf(SilenceWalkReports.SESSION to remove)).build()
        assertEquals(ListenableWorker.Result.success(), staleRequest.doWork())
        SilenceWalkReports.reconcile(app)
        assertFalse(html.exists())
        assertEquals(keep, trace(SilenceWalkReports.file(app, keep)).getJSONObject("session").getLong("id"))
    }

    @Test fun latestReportLookupPreservesAnUnfinishedFirstWalk() {
        assertEquals("none", LatestWalkReportReceiver.latest(app))
        val active = SilenceTraceStore.start(app)
        assertEquals("none", LatestWalkReportReceiver.latest(app))
        assertEquals(active, SilenceTraceStore.activeId(app))
        assertFalse(SilenceWalkReports.file(app, active).exists())
    }

    @Test fun latestReportLookupRestoresTheNewestCompletedHtmlWhileAnotherWalkIsActive() {
        val (first, completed) = TraceDatabase(app).use { db ->
            val first = db.start(3_000L)
            db.stop(first, 4_000L)
            // The phone clock moved back. Creation order still identifies the latest walk.
            val completed = db.start(1_000L)
            db.stop(completed, 2_000L)
            first to completed
        }
        SilenceWalkReports.install(app)
        awaitReport(first)
        val html = awaitReport(completed)
        assertTrue(html.delete())
        val active = SilenceTraceStore.start(app)

        assertEquals("silence-walk-$completed.html", LatestWalkReportReceiver.latest(app))
        assertEquals(completed, trace(awaitReport(completed)).getJSONObject("session").getLong("id"))
        assertEquals(active, SilenceTraceStore.activeId(app))
        assertFalse(SilenceWalkReports.file(app, active).exists())
    }

    @Test fun latestReportBroadcastRequiresTheAdbShellPermission() {
        val receiver = app.packageManager.getReceiverInfo(ComponentName(app, LatestWalkReportReceiver::class.java), 0)
        assertTrue(receiver.exported)
        assertEquals(Manifest.permission.DUMP, receiver.permission)
    }

    private fun awaitReport(id: Long): File {
        val html = SilenceWalkReports.file(app, id)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (!html.isFile && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Finished report $id was not published", html.isFile)
        return html
    }
}

