package com.tunisianprayertimes.mosque

import android.content.Context
import android.util.AtomicFile
import com.tunisianprayertimes.mosque.presence.PresenceFix
import com.tunisianprayertimes.mosque.presence.SilenceArea
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** Records only walks whose observation window began after this installation opted in. */
internal object MosqueWalkSharing {
    private const val DAY = 86_400_000L
    private const val LIMIT = 4800
    private val writer = Executors.newSingleThreadExecutor()
    private var consentApp: Context? = null
    private var consent = 0L
    private var active = emptyList<String>()
    private var lastActive = emptyList<String>()
    private var epochSeen = 0L
    private val outlines = mutableMapOf<String, MutableSet<Pair<String, Long>>>()

    private fun file(c: Context) = AtomicFile(File(c.noBackupFilesDir, "mosque-walk-sharing-since"))

    @Synchronized
    fun since(c: Context): Long {
        if (consentApp !== c.applicationContext) {
            consent =
                runCatching { file(c).readFully().decodeToString().trim().toLong() }
                    .getOrDefault(0L)
            consentApp = c.applicationContext
        }
        return consent
    }

    fun setEnabled(c: Context, enabled: Boolean) {
        synchronized(this) {
            if ((since(c) > 0) == enabled) return
            val value = if (enabled) System.currentTimeMillis().coerceAtLeast(1) else 0L
            val target = file(c)
            val out = target.startWrite()
            try {
                out.write(value.toString().toByteArray())
                target.finishWrite(out)
            } catch (e: Exception) {
                target.failWrite(out)
                throw e
            }
            consent = value
        }
        if (!enabled) {
            MosqueReportDelivery.cancelWalks(c)
            val db = MosqueReportStore.get(c).db
            db.atomic {
                db.execSQL("DELETE FROM walk_samples")
                db.execSQL("DELETE FROM walk_shapes")
                db.execSQL("DELETE FROM walk_windows")
                db.execSQL("DELETE FROM outbox WHERE kind='walk'")
                db.execSQL(
                    "UPDATE receipts SET status='expired' WHERE kind='walk' AND status='queued'"
                )
            }
        }
    }

    private fun write(c: Context, work: MosqueReportStore.() -> Unit) {
        val epoch = since(c)
        if (epoch <= 0L) return
        val app = c.applicationContext
        writer.execute {
            runCatching {
                    val store = MosqueReportStore.get(app)
                    store.db.atomic {
                        if (since(app) != epoch) return@atomic
                        if (epochSeen != epoch) {
                            active = emptyList()
                            lastActive = emptyList()
                            outlines.clear()
                            epochSeen = epoch
                        }
                        store.work()
                    }
                    MosqueReportDelivery.scheduleNew(app, store)
                }
                .onFailure { android.util.Log.w("MosqueReports", "Could not record a shared walk") }
        }
    }

    fun scope(c: Context, periods: MosquePeriods) {
        if (since(c) <= 0L) return
        write(c) {
            val eligible = periods.targets.filter { MosquePeriods.earlyStart(it) >= since(c) }
            val ids = eligible.map(MosquePrayerActions::key)
            if (ids != active) {
                if (active.isNotEmpty()) lastActive = active
                active = ids
                outlines.keys.retainAll(ids.toSet())
            }
            for (w in eligible) {
                val k = MosquePrayerActions.key(w)
                val end = periods.continuationEnd ?: w.end.toEpochMilli()
                if (keys("SELECT key FROM walk_windows WHERE key=?", k).isEmpty()) {
                    val meta = MosqueReportStore.metadata()
                    val fields =
                        JSONObject().put("prayer", w.prayer.name).put("day", w.prayerDay.toString())
                    for (field in
                        listOf("device_model", "android_api_level", "channel", "build")) fields.put(
                        field,
                        meta.get(field),
                    )
                    val start = MosquePeriods.earlyStart(w)
                    fields.put("started", start)
                    db.execSQL(
                        "INSERT INTO walk_windows VALUES(?,?,?,?,?)",
                        arrayOf<Any>(k, UUID.randomUUID().toString(), start, end, fields.toString()),
                    )
                } else
                    db.execSQL(
                        "UPDATE walk_windows SET end=? WHERE key=? AND end!=?",
                        arrayOf<Any>(end, k, end),
                    )
            }
        }
    }

    fun fix(c: Context, fix: PresenceFix, at: Long, used: Boolean, areas: List<SilenceArea>) {
        if (since(c) <= 0 || !fix.point.isValid()) return
        write(c) {
            for (k in active) {
                sample(
                    k,
                    at,
                    if (used) "used" else "rejected",
                    fix.point.longitude,
                    fix.point.latitude,
                    fix.accuracyMeters,
                )
                val captured = outlines.getOrPut(k) { mutableSetOf() }
                for (area in areas) {
                    val version = area.id to area.revision
                    if (!area.enabled || version in captured || captured.size >= 16) continue
                    if (
                        area.polygons.none {
                            it.contains(fix.point) || it.boundaryDistance(fix.point) <= 300
                        }
                    )
                        continue
                    val shape = reportGeometry(area.polygons)
                    if (vertices(shape) <= LIMIT)
                        db.execSQL(
                            "INSERT OR IGNORE INTO walk_shapes VALUES(?,?)",
                            arrayOf(k, shape.toString()),
                        )
                    captured.add(version)
                }
            }
        }
    }

    fun route(c: Context, at: Long, longitude: Double, latitude: Double, accuracy: Double?) =
        write(c) { for (k in active) sample(k, at, "route", longitude, latitude, accuracy) }

    fun event(c: Context, at: Long, kind: String) =
        write(c) {
            val ids = if (active.isEmpty() && kind.startsWith("release")) lastActive else active
            for (k in ids) sample(k, at, kind, null, null, null)
        }

    private fun MosqueReportStore.sample(
        k: String,
        at: Long,
        kind: String,
        lon: Double?,
        lat: Double?,
        accuracy: Double?,
    ) {
        db.execSQL(
            "INSERT INTO walk_samples SELECT key,?,?,?,?,? FROM walk_windows WHERE key=? AND ? BETWEEN start AND start+86400000",
            arrayOf<Any?>(
                at,
                kind,
                lon,
                lat,
                accuracy?.takeIf { it.isFinite() && it in 0.0..100000.0 },
                k,
                at,
            ),
        )
    }

    fun finish(c: Context, now: Long, recovering: Boolean = false) =
        write(c) {
            val cutoff = if (recovering) now - MosquePeriods.CONTINUATION_MILLIS else now
            for (k in keys("SELECT key FROM walk_windows WHERE end<=?", cutoff.toString())) {
                if (!recovering && k in active) continue
                db.rawQuery("SELECT id,start,meta FROM walk_windows WHERE key=?", arrayOf(k)).use {
                    row ->
                    if (
                        row.moveToFirst() &&
                            row.getLong(1) >= since(c) &&
                            now - row.getLong(1) < 14 * DAY
                    ) {
                        val fields = JSONObject(row.getString(2))
                        val points = JSONArray()
                        val events = JSONArray()
                        val shapes = JSONArray()
                        val count =
                            keys(
                                    "SELECT count(*) FROM walk_samples WHERE window=? AND lon IS NOT NULL",
                                    k,
                                )
                                .single()
                                .toLong()
                        var i = 0L
                        db.rawQuery(
                                "SELECT at,kind,lon,lat,accuracy FROM walk_samples WHERE window=? ORDER BY at,rowid",
                                arrayOf(k),
                            )
                            .use { samples ->
                                while (samples.moveToNext()) {
                                    val offset = samples.getLong(0) - row.getLong(1)
                                    val kind = samples.getString(1)
                                    if (samples.isNull(2)) {
                                        if (events.length() < 512)
                                            events.put(JSONArray().put(offset).put(kind))
                                    } else {
                                        // Spread the sample budget over the whole walk, preserving
                                        // both ends.
                                        val keep =
                                            count <= LIMIT ||
                                                i == 0L ||
                                                i * (LIMIT - 1) / (count - 1) !=
                                                    (i - 1) * (LIMIT - 1) / (count - 1)
                                        i++
                                        if (keep)
                                            points.put(
                                                JSONArray()
                                                    .put(offset)
                                                    .put(samples.getDouble(2))
                                                    .put(samples.getDouble(3))
                                                    .put(
                                                        if (samples.isNull(4)) JSONObject.NULL
                                                        else samples.getDouble(4)
                                                    )
                                                    .put(kind)
                                            )
                                    }
                                }
                            }
                        var total = 0
                        for (raw in
                            keys(
                                "SELECT shape FROM walk_shapes WHERE window=? ORDER BY rowid LIMIT 16",
                                k,
                            )) {
                            val shape = JSONObject(raw)
                            val size = vertices(shape)
                            if (total + size <= LIMIT) {
                                shapes.put(shape)
                                total += size
                            }
                        }
                        fields.put("points", points).put("events", events).put("boundaries", shapes)
                        if (points.length() > 0)
                            MosqueReportDelivery.queueSubmission(
                                this,
                                "walk",
                                fields,
                                row.getString(0),
                            )
                    }
                }
                db.execSQL("DELETE FROM walk_samples WHERE window=?", arrayOf(k))
                db.execSQL("DELETE FROM walk_shapes WHERE window=?", arrayOf(k))
                db.execSQL("DELETE FROM walk_windows WHERE key=?", arrayOf(k))
            }
        }

    private fun vertices(shape: JSONObject): Int {
        fun count(a: JSONArray): Int =
            if (a.optJSONArray(0) == null) 1
            else (0 until a.length()).sumOf { count(a.getJSONArray(it)) }
        return count(shape.getJSONArray("coordinates"))
    }

    internal fun awaitWrites() {
        writer.submit {}.get(15, java.util.concurrent.TimeUnit.SECONDS)
    }
}
