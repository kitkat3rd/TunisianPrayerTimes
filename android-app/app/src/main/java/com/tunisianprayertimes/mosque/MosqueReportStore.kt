package com.tunisianprayertimes.mosque

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import com.tunisianprayertimes.AnalyticsTracker
import com.tunisianprayertimes.BuildConfig
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** One database in noBackupFilesDir. No table carries an account or installation identifier. */
internal class MosqueReportStore
private constructor(private val app: Context, val db: SQLiteDatabase) {
    init {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS windows (key TEXT PRIMARY KEY, end_ms INTEGER NOT NULL, state TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS outbox (id TEXT PRIMARY KEY, kind TEXT NOT NULL, created INTEGER NOT NULL, body TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS receipts (id TEXT PRIMARY KEY, secret TEXT NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL, created INTEGER NOT NULL)"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS local (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS walk_windows (key TEXT PRIMARY KEY, id TEXT NOT NULL, start INTEGER, end INTEGER, meta TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS walk_samples (window TEXT, at INTEGER, kind TEXT, lon REAL, lat REAL, accuracy REAL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS walk_sample_window ON walk_samples(window,at)")
        db.execSQL("CREATE TABLE IF NOT EXISTS walk_shapes (window TEXT, shape TEXT, PRIMARY KEY(window,shape))")
    }

    fun value(key: String): String? =
        db.rawQuery("SELECT value FROM local WHERE key=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun put(key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO local VALUES (?,?)", arrayOf(key, value))
    }

    fun window(key: String): JSONObject? =
        db.rawQuery("SELECT state FROM windows WHERE key=?", arrayOf(key)).use {
            if (it.moveToFirst()) JSONObject(it.getString(0)) else null
        }

    fun save(key: String, state: JSONObject) {
        db.execSQL(
            "INSERT OR REPLACE INTO windows VALUES (?,?,?)",
            arrayOf<Any>(key, state.getLong("end"), state.toString()),
        )
    }

    fun keys(sql: String, vararg args: String): List<String> =
        db.rawQuery(sql, arrayOf(*args)).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }

    val newlyQueued = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()

    fun queue(kind: String, body: JSONObject, now: Long): String {
        val id = body.optString("id").ifBlank { UUID.randomUUID().toString() }
        body.put("id", id)
        db.execSQL(
            "INSERT OR IGNORE INTO outbox VALUES (?,?,?,?)",
            arrayOf(id, kind, now, body.toString()),
        )
        if (kind == "metrics")
            db.execSQL(
                """DELETE FROM outbox WHERE id IN
            (SELECT id FROM outbox WHERE kind='metrics' ORDER BY created DESC LIMIT -1 OFFSET 256)"""
            )
        newlyQueued.add(id to kind)
        return id
    }

    fun clearMetrics(clearQuestions: Boolean = true) {
        db.execSQL("DELETE FROM windows")
        db.execSQL("DELETE FROM outbox WHERE kind='metrics'")
        db.execSQL(
            "DELETE FROM local WHERE key NOT IN ('participation','volunteer_trial','question','lastQuestion')"
        )
        if (clearQuestions) db.execSQL("DELETE FROM local WHERE key='question'")
    }

    companion object {
        @Volatile private var singleton: MosqueReportStore? = null

        fun get(c: Context): MosqueReportStore {
            val app = c.applicationContext
            return singleton?.takeIf { it.app === app }
                ?: synchronized(this) {
                    singleton?.takeIf { it.app === app }
                        ?: MosqueReportStore(
                                app,
                                SQLiteDatabase.openOrCreateDatabase(
                                    File(app.noBackupFilesDir, "mosque-reports-v1.db"),
                                    null,
                                ),
                            )
                            .also { singleton = it }
                }
        }

        fun enabled(c: Context): Boolean =
            when (get(c).value("participation")) {
                "on" -> true
                "off" -> false
                else -> BuildConfig.MOSQUE_REPORT_CHANNEL in setOf("dev", "beta")
            }

        fun permitted(c: Context) = enabled(c) && AnalyticsTracker.mosqueMetricsAllowed(c)

        fun metadata() =
            JSONObject().apply {
                put("schema", 1)
                put("channel", BuildConfig.MOSQUE_REPORT_CHANNEL)
                put("build", BuildConfig.VERSION_CODE)
                put("detector", 1)
                put("catalogue", BuildConfig.MOSQUE_CATALOG_VERSION)
                put("android_api_level", Build.VERSION.SDK_INT)
                put(
                    "manufacturer",
                    Build.MANUFACTURER.orEmpty().trim().take(100).ifBlank { "Unknown" },
                )
                put("device_model", Build.MODEL.orEmpty().trim().take(100).ifBlank { "Unknown" })
            }
    }
}

internal inline fun <T> SQLiteDatabase.atomic(block: () -> T): T {
    beginTransaction()
    return try {
        block().also { setTransactionSuccessful() }
    } finally {
        endTransaction()
    }
}

internal fun JSONObject.increment(name: String, amount: Int = 1) {
    put(name, (optLong(name) + amount).coerceAtMost(1_000_000))
}

internal fun delayBand(millis: Long): String =
    when {
        millis < 0 -> "unknown"
        millis < 10_000 -> "lt10"
        millis < 30_000 -> "lt30"
        millis < 60_000 -> "lt60"
        else -> "ge60"
    }
