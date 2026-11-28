package com.tunisianprayertimes.mosque.trace

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import android.util.Log
import androidx.core.database.sqlite.transaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.ZoneId

data class TraceSession(
    val id: Long, val startedAt: Long, val endedAt: Long?,
    val timeZoneId: String = ZoneId.systemDefault().id,
)

data class TraceEntry(
    val id: Long,
    val at: Long,
    val kind: String,
    val detail: String,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Double?,
)

/** Sessions, coordinates and event details stay in the app's no-backup directory. */
object SilenceTraceStore {
    @Volatile private var database: TraceDatabase? = null
    private val revision = MutableStateFlow(0L)
    val changes: StateFlow<Long> = revision.asStateFlow()

    @Synchronized private fun store(context: Context): TraceDatabase =
        database ?: TraceDatabase(context.applicationContext) { revision.update { it + 1 } }
            .also { database = it }

    /** Release the process-owned SQLite handle before a test tears down its Android runtime. */
    internal fun closeForTest() {
        SilenceWalkReports.closeForTest()
        synchronized(this) {
            val db = database
            database = null
            db?.close()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun activeId(context: Context): Long? = database?.active?.id

    fun start(context: Context, elapsedMillis: Long = SystemClock.elapsedRealtime()): Long {
        // A delivered fix can be the first evidence of a return. Preserve its acquisition time.
        val at = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - elapsedMillis)
        return store(context).start(at, elapsedMillis)
    }

    fun stop(context: Context, sessionId: Long) {
        val db = store(context)
        if (db.stop(sessionId, db.timelineTime())) SilenceWalkReports.closed(context, sessionId)
    }

    fun sessions(context: Context): List<TraceSession> = store(context).sessions()

    internal fun session(context: Context, id: Long): TraceSession? = store(context).session(id)

    internal fun latestCompletedId(context: Context): Long? = store(context).latestCompletedId()

    fun entries(context: Context, sessionId: Long): List<TraceEntry> = store(context).entries(sessionId)

    fun delete(context: Context, sessionId: Long) = SilenceWalkReports.delete(context, sessionId, store(context))

    @Suppress("UNUSED_PARAMETER")
    fun append(
        context: Context, kind: String, detail: String = "", at: Long? = null,
        latitude: Double? = null, longitude: Double? = null, accuracyMeters: Double? = null,
        sessionId: Long? = null, elapsedMillis: Long? = null,
    ): Boolean {
        // Passive events only append to an open detection report.
        val db = database ?: return false
        val expected = sessionId ?: db.active?.id ?: return false
        return try {
            val time = db.timelineTime(at, elapsedMillis)
            val saved = db.append(expected, time, kind, detail, latitude, longitude, accuracyMeters)
            if (saved) {
                val sharing = com.tunisianprayertimes.mosque.MosqueWalkSharing
                when (kind) {
                    "route" -> if (latitude != null && longitude != null) sharing.route(context,time,longitude,latitude,accuracyMeters)
                    "route_provider","recorder_error","recording_interrupted" -> sharing.event(context,time,"route_gap")
                    "test_arrived" -> sharing.event(context,time,"manual_arrival")
                    "test_left" -> sharing.event(context,time,"manual_departure")
                }
            }
            saved
        } catch (_: SQLiteException) {
            // A full disk or broken diagnostics database must not change the actual DND decision.
            Log.w("SilenceTrace", "Could not persist a test event")
            false
        }
    }
}

internal class TraceDatabase(context: Context, private val changed: () -> Unit = {}) :
    SQLiteOpenHelper(context, File(context.noBackupFilesDir, "silence-walks.db").absolutePath, null, 1) {

    private data class Recording(val session: TraceSession, val startedElapsed: Long)
    @Volatile private var recording: Recording? = null
    val active: TraceSession? get() = recording?.session

    @Synchronized override fun close() {
        recording = null
        super.close()
    }

    // Keep route and decision times on one continuous axis when testing across a phone clock change.
    // Android locations supply elapsed realtime. For other events the caller may have captured
    // a wall timestamp shortly before dispatching a write, so retain the age of that event.
    fun timelineTime(
        at: Long? = null, elapsedAt: Long? = null,
        wallNow: Long = System.currentTimeMillis(), elapsedNow: Long = SystemClock.elapsedRealtime(),
    ): Long {
        val walk = recording ?: return at ?: wallNow
        val elapsed = elapsedAt ?: if (at == null) elapsedNow else elapsedNow - (wallNow - at)
        return walk.session.startedAt + (elapsed - walk.startedElapsed)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        // AUTOINCREMENT keeps an old notification or provider callback from targeting a reused ID.
        db.execSQL("""CREATE TABLE sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL, ended_at INTEGER,
            time_zone_id TEXT NOT NULL
        )""")
        db.execSQL("""CREATE TABLE entries (
            id INTEGER PRIMARY KEY, session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
            at INTEGER NOT NULL, kind TEXT NOT NULL, detail TEXT NOT NULL,
            latitude REAL, longitude REAL, accuracy_meters REAL
        )""")
        db.execSQL("CREATE INDEX entries_chronological ON entries(session_id, at, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported silence trace database upgrade from " + oldVersion + " to " + newVersion)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // The foreground recorder is tied to this process. A kill must not silently resume tracking.
        val interrupted = db.rawQuery("""SELECT s.id, COALESCE(MAX(e.at), s.started_at)
            FROM sessions s LEFT JOIN entries e ON s.id = e.session_id
            WHERE s.ended_at IS NULL GROUP BY s.id""", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getLong(1)) }
        }
        if (interrupted.isNotEmpty()) db.transaction {
            interrupted.forEach { (id, end) ->
                finish(this, id, end, "recording_interrupted", "انقطع التسجيل")
            }
        }
    }

    @Synchronized fun start(at: Long, elapsed: Long = SystemClock.elapsedRealtime()): Long {
        active?.let { return it.id }
        val db = writableDatabase
        val zoneId = ZoneId.systemDefault().id
        val id = db.transaction {
            val session = insertOrThrow("sessions", null, ContentValues().apply {
                put("started_at", at)
                put("time_zone_id", zoneId)
            })
            insertEntry(this, session, at, "recording_started", "بدأ تسجيل المسار")
            session
        }
        recording = Recording(TraceSession(id, at, null, zoneId), elapsed)
        changed()
        return id
    }

    @Synchronized fun append(
        expected: Long, at: Long, kind: String, detail: String,
        latitude: Double?, longitude: Double?, accuracyMeters: Double?,
    ): Boolean {
        val recording = active ?: return false
        if (recording.id != expected || at < recording.startedAt || kind.isBlank()) return false
        if ((latitude == null) != (longitude == null)) return false
        if (latitude != null && latitude !in -90.0..90.0) return false
        if (longitude != null && longitude !in -180.0..180.0) return false
        val accuracy = accuracyMeters?.takeIf { latitude != null && it.isFinite() && it >= 0 }
        insertEntry(writableDatabase, expected, at, kind, detail, latitude, longitude, accuracy)
        changed()
        return true
    }

    @Synchronized fun stop(sessionId: Long, now: Long): Boolean {
        if (active?.id != sessionId) return false
        try {
            writableDatabase.transaction {
                val last = rawQuery("SELECT MAX(at) FROM entries WHERE session_id = ?", args(sessionId)).use {
                    it.moveToFirst()
                    it.getLong(0)
                }
                finish(this, sessionId, maxOf(last, now), "recording_stopped", "انتهى تسجيل المسار")
            }
        } catch (_: SQLiteException) {
            // The next open recovers the unfinished walk at its last durable event.
            close()
            Log.w("SilenceTrace", "Could not persist the end of a test walk")
        } finally {
            recording = null
            changed()
        }
        return true
    }

    private fun finish(db: SQLiteDatabase, id: Long, at: Long, kind: String, detail: String) {
        db.update("sessions", ContentValues().apply { put("ended_at", at) }, "id = ?", args(id))
        insertEntry(db, id, at, kind, detail)
    }

    fun sessions(): List<TraceSession> = writableDatabase.rawQuery(
        "SELECT id, started_at, ended_at, time_zone_id FROM sessions ORDER BY id DESC", null
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.session())
        }
    }

    fun session(id: Long): TraceSession? = writableDatabase.rawQuery(
        "SELECT id, started_at, ended_at, time_zone_id FROM sessions WHERE id = ?", args(id),
    ).use { if (it.moveToFirst()) it.session() else null }

    fun latestCompletedId(): Long? = writableDatabase.rawQuery(
        "SELECT id FROM sessions WHERE ended_at IS NOT NULL ORDER BY id DESC LIMIT 1", null,
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun Cursor.session() = TraceSession(getLong(0), getLong(1), if (isNull(2)) null else getLong(2), getString(3))

    fun entries(sessionId: Long): List<TraceEntry> = writableDatabase.rawQuery(
        """SELECT id, at, kind, detail, latitude, longitude, accuracy_meters FROM entries
            WHERE session_id = ? ORDER BY at, id""", args(sessionId)
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(TraceEntry(
                cursor.getLong(0), cursor.getLong(1), cursor.getString(2), cursor.getString(3),
                cursor.maybeDouble(4), cursor.maybeDouble(5), cursor.maybeDouble(6)
            ))
        }
    }

    @Synchronized fun delete(id: Long) {
        if (writableDatabase.delete("sessions", "id = ? AND ended_at IS NOT NULL", args(id)) > 0) changed()
    }

    private fun args(id: Long) = arrayOf(id.toString())

    private fun Cursor.maybeDouble(column: Int) = if (isNull(column)) null else getDouble(column)

    private fun insertEntry(
        db: SQLiteDatabase, sessionId: Long, at: Long, kind: String, detail: String,
        latitude: Double? = null, longitude: Double? = null, accuracyMeters: Double? = null,
    ) {
        db.insertOrThrow("entries", null, ContentValues().apply {
            put("session_id", sessionId)
            put("at", at)
            put("kind", kind)
            put("detail", detail)
            put("latitude", latitude)
            put("longitude", longitude)
            put("accuracy_meters", accuracyMeters)
        })
    }
}
