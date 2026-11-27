package com.tunisianprayertimes.quran.tafsir

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate
import java.util.concurrent.TimeUnit

data class TafsirCorrection(val text: String, val reason: String, val date: String)

/** Text-only, cumulative overlays. Stable IDs and the base hash keep verse placement in the shipped edition. */
object TafsirCorrections {
    internal const val INDEX_LIMIT = 256 * 1024
    private const val PATCH_LIMIT = 100_000
    private const val WORK_NAME = "tafsir-corrections"
    private val editionHash = Regex("[0-9a-f]{64}")
    private val bookId = Regex("[a-z][a-z0-9_-]{0,39}")
    private val patchName = Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]*")
    private val cache = mutableMapOf<File, Overlay>()
    private val empty = Overlay(0, emptyMap())

    private data class Overlay(val version: Long, val units: Map<String, TafsirCorrection>)
    internal data class Config(val index: URL, val key: PublicKey)
    internal data class Entry(val book: String, val base: String, val version: Long,
                              val path: String, val bytes: Int, val sha256: String)

    internal fun config(catalog: JSONObject): Config? {
        if (catalog.optBoolean("preview")) return null
        val data = catalog.optJSONObject("corrections") ?: return null
        return runCatching {
            val index = URL(data.getString("indexUrl"))
            require(index.protocol == "https" && index.host.isNotBlank() && index.userInfo == null)
            val publicKey = X509EncodedKeySpec(Base64.decode(data.getString("publicKey"), Base64.DEFAULT))
            Config(index, KeyFactory.getInstance("RSA").generatePublic(publicKey))
        }.getOrNull()
    }

    /** Called when the reader loads its catalog. Without a publisher key and endpoint no work is enqueued. */
    fun schedule(context: Context, catalogJson: JSONObject, installed: Map<String, String>) {
        scheduleEditions(context, catalogJson, installed.mapValues { (_, base) -> setOf(base) })
    }

    @Synchronized
    fun scheduleEditions(context: Context, catalogJson: JSONObject, installed: Map<String, Set<String>>) {
        val app = context.applicationContext
        val basesJson = JSONObject()
        val keep = mutableSetOf<String>()
        for ((book, editions) in installed.toSortedMap()) {
            if (!bookId.matches(book)) continue
            val valid = editions.filter(editionHash::matches).sorted()
            if (valid.isEmpty()) continue
            basesJson.put(book, JSONArray(valid))
            valid.mapTo(keep) { base -> file(app, book, base).name }
        }
        val prefs = app.getSharedPreferences(WORK_NAME, Context.MODE_PRIVATE)
        val bases = basesJson.toString()
        if (prefs.getString("bases", null) != bases) {
            prefs.edit().putString("bases", bases).apply()
            directory(app).listFiles()?.forEach { existing ->
                val baseName = existing.name.removeSuffix(".bak").removeSuffix(".new")
                if (baseName.endsWith(".json") && baseName !in keep) {
                    val obsolete = File(existing.parentFile, baseName)
                    AtomicFile(obsolete).delete()
                    cache.remove(obsolete)
                }
            }
        }
        if (keep.isEmpty() || config(catalogJson) == null ||
            !timeToCheck(prefs.getLong("lastCheck", 0), System.currentTimeMillis())) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<TafsirCorrectionsWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private fun timeToCheck(last: Long, now: Long) =
        last <= 0 || last > now || now - last >= TimeUnit.DAYS.toMillis(1)

    @Synchronized
    internal fun beginCheck(context: Context): Map<String, Set<String>>? {
        val prefs = context.getSharedPreferences(WORK_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!timeToCheck(prefs.getLong("lastCheck", 0), now)) return null
        val bases = storedEditions(context)
        if (bases.isEmpty()) return null
        prefs.edit().putLong("lastCheck", now).apply()
        return bases
    }

    private fun storedEditions(context: Context): Map<String, Set<String>> {
        val prefs = context.getSharedPreferences(WORK_NAME, Context.MODE_PRIVATE)
        val json = JSONObject(prefs.getString("bases", "{}") ?: "{}")
        return buildMap {
            for (book in json.keys()) {
                val stored = json.opt(book)
                val bases = when (stored) {
                    // A pending job from a single-edition release may run before the reader next opens.
                    is String -> setOf(stored)
                    is JSONArray -> (0 until stored.length()).map { stored.optString(it) }.toSet()
                    else -> emptySet()
                }
                if (bases.isNotEmpty()) put(book, bases)
            }
        }
    }

    internal fun verifiedIndex(bytes: ByteArray, publicKey: PublicKey): List<Entry> {
        require(bytes.size <= INDEX_LIMIT)
        val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
        val payload = Base64.decode(envelope.getString("payload"), Base64.DEFAULT)
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(publicKey)
        signature.update(payload)
        require(signature.verify(Base64.decode(envelope.getString("signature"), Base64.DEFAULT)))
        val index = JSONObject(payload.toString(Charsets.UTF_8))
        require(index.getInt("version") == 1)
        val entries = index.getJSONArray("files")
        val result = List(entries.length()) { i ->
            val row = entries.getJSONObject(i)
            Entry(row.getString("book"), row.getString("baseId"), row.getLong("version"),
                row.getString("path"), row.getInt("bytes"), row.getString("sha256")).also {
                require(bookId.matches(it.book) && editionHash.matches(it.base) && editionHash.matches(it.sha256))
                require(it.version > 0 && it.bytes in 1 until PATCH_LIMIT && patchName.matches(it.path))
            }
        }
        require(result.distinctBy { it.book to it.base }.size == result.size)
        return result
    }

    private fun decode(bytes: ByteArray, book: String, base: String): Overlay {
        require(bytes.size < PATCH_LIMIT)
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getString("book") == book && json.getString("baseId") == base)
        val version = json.getLong("version")
        require(version > 0)
        val rows = json.getJSONArray("units")
        val units = buildMap {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val id = row.getString("id")
                val text = row.getString("text")
                val reason = row.getString("reason")
                val date = LocalDate.parse(row.getString("date")).toString()
                require(id.isNotBlank() && text.isNotBlank() && reason.isNotBlank() && id !in this)
                put(id, TafsirCorrection(text, reason, date))
            }
        }
        return Overlay(version, units)
    }

    private fun directory(context: Context) = File(context.noBackupFilesDir, WORK_NAME)
    private fun file(context: Context, book: String, base: String) = File(directory(context), "${book}-${base}.json")

    @Synchronized
    private fun current(context: Context, book: String, base: String): Overlay {
        if (!bookId.matches(book) || !editionHash.matches(base)) return empty
        val target = file(context, book, base)
        return cache.getOrPut(target) {
            try {
                decode(AtomicFile(target).readFully(), book, base)
            } catch (_: Exception) {
                empty
            }
        }
    }

    fun forBase(context: Context, bookId: String, baseId: String): Map<String, TafsirCorrection> =
        current(context, bookId, baseId).units

    internal fun version(context: Context, entry: Entry): Long = current(context, entry.book, entry.base).version

    @Synchronized
    internal fun install(context: Context, entry: Entry, bytes: ByteArray): Boolean {
        require(bytes.size == entry.bytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(hash == entry.sha256)
        val overlay = decode(bytes, entry.book, entry.base)
        require(overlay.version == entry.version)
        // A pending download may finish after the reader changes or removes the installed edition.
        if (entry.base !in storedEditions(context)[entry.book].orEmpty() || version(context, entry) >= entry.version) return false
        val target = file(context, entry.book, entry.base)
        val atomic = AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
        cache[target] = overlay
        return true
    }
}
