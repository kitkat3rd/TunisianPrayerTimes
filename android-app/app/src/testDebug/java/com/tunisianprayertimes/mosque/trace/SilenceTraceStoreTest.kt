package com.tunisianprayertimes.mosque.trace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SilenceTraceStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun chronologicalEntriesDistinguishMovementFromDecisionsAndRejectOlderFixes() {
        TraceDatabase(context).use { db ->
            val id = db.start(1_000)
            assertTrue(db.append(id, 3_000, "silence_applied", "فُعّل الصمت", null, null, null))
            assertTrue(db.append(id, 2_000, "route", "gps", 36.8, 10.1, 4.5))
            assertTrue(db.append(id, 3_000, "monitor_fix", "gps", 36.81, 10.1, null))
            assertFalse(db.append(id, 999, "route", "", 36.8, 10.1, 5.0))
            assertFalse(db.append(id, 3_000, "route", "", Double.NaN, 10.0, null))
            db.stop(id, 2_999)
            val entries = db.entries(id)
            assertEquals(listOf("recording_started", "route", "silence_applied", "monitor_fix", "recording_stopped"),
                entries.map { it.kind })
            assertEquals(4.5, entries[1].accuracyMeters!!, 0.0)
            assertNull(entries[2].latitude)
            assertEquals(3_000L, db.sessions().single().endedAt)
        }
    }

    @Test fun interruptedRecordingEndsAtLastSavedEventAndRemainsReadableAfterRestart() {
        val id = TraceDatabase(context).use { db ->
            db.start(1_000).also { db.append(it, 2_500, "route", "", 36.0, 10.0, null) }
        }
        TraceDatabase(context).use { reopened ->
            assertNull(reopened.active)
            assertEquals(TraceSession(id, 1_000, 2_500), reopened.sessions().single())
            assertEquals("recording_interrupted", reopened.entries(id).last().kind)
            val next = reopened.start(9_000)
            assertFalse(reopened.append(id, 9_500, "route", "", 36.0, 10.0, 3.0))
            reopened.stop(id, 10_000)
            assertEquals(next, reopened.active?.id)
            assertEquals(listOf("recording_started"), reopened.entries(next).map { it.kind })
        }
    }

    @Test fun failedStopWriteDeactivatesImmediatelyAndRecoversAtTheLastPersistedEntry() {
        TraceDatabase(context).use { db ->
            val id = db.start(1_000)
            db.append(id, 2_500, "route", "", 36.0, 10.0, null)
            db.writableDatabase.execSQL("""CREATE TEMP TRIGGER refuse_stop BEFORE UPDATE ON sessions
                BEGIN SELECT RAISE(ABORT, 'write unavailable'); END""")
            db.stop(id, 3_000)
            assertNull(db.active)
            assertFalse(db.append(id, 3_500, "route", "", 36.0, 10.0, null))
            assertEquals(2_500L, db.sessions().single().endedAt)
            assertEquals("recording_interrupted", db.entries(id).last().kind)
        }
    }

    @Test fun savedWalkKeepsTheClockZoneUsedWhenRecordingStarted() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Africa/Tunis"))
            TraceDatabase(context).use { db -> db.stop(db.start(1_000), 2_000) }
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            TraceDatabase(context).use { db ->
                assertEquals("Africa/Tunis", db.sessions().single().timeZoneId)
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun movingThePhoneClockBackPreservesTheWalkDurationAndSampleOrder() {
        TraceDatabase(context).use { db ->
            val id = db.start(at = 90_000, elapsed = 20_000)
            // After a five-second walk, the wall clock was reset to 1,000.
            val eventAt = db.timelineTime(at = 1_000, wallNow = 1_000, elapsedNow = 25_000)
            val fixAt = db.timelineTime(at = 93_000, elapsedAt = 23_000, wallNow = 1_000, elapsedNow = 25_000)
            val staleAt = db.timelineTime(at = 2_000, elapsedAt = 18_000, wallNow = 1_000, elapsedNow = 25_000)
            assertTrue(db.append(id, eventAt, "device_clock", "", null, null, null))
            assertTrue(db.append(id, fixAt, "route", "", 36.0, 10.0, null))
            assertFalse(db.append(id, staleAt, "route", "", 36.0, 10.0, null))
            assertEquals(listOf(90_000L, 93_000L, 95_000L), db.entries(id).map { it.at })
        }
    }

    @Test fun deleteRemovesTheCompletedWalkOnlyAndAnOldIdentifierIsNeverReused() {
        TraceDatabase(context).use { db ->
            val id = db.start(1_000)
            db.delete(id)
            assertEquals(id, db.sessions().single().id)
            db.stop(id, 2_000)
            db.delete(id)
            assertTrue(db.sessions().isEmpty())
            assertTrue(db.entries(id).isEmpty())
            val next = db.start(3_000)
            assertTrue(next > id)
            db.stop(id, 3_500)
            assertNull(db.sessions().single().endedAt)
        }
    }

    @Test fun concurrentStartsAndWritersShareOneSession() {
        TraceDatabase(context).use { db ->
            val pool = Executors.newFixedThreadPool(4)
            try {
                val ids = pool.invokeAll(List(16) { i ->
                    Callable {
                        db.start(1_000).also { id ->
                            db.append(id, 2_000L + i, "marker", i.toString(), null, null, null)
                        }
                    }
                }).map { it.get() }.toSet()
                val id = ids.single()
                db.stop(id, 3_000)
                assertFalse(db.append(id, 3_000, "marker", "late", null, null, null))
                assertEquals((0..15).map(Int::toString), db.entries(id).filter { it.kind == "marker" }.map { it.detail })
            } finally {
                pool.shutdownNow()
            }
        }
    }
}
