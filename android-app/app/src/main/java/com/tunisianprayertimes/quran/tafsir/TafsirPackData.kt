package com.tunisianprayertimes.quran.tafsir

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDoneException
import com.tunisianprayertimes.quran.assets.QuranAssetSource
import com.tunisianprayertimes.quran.assets.QuranAssets
import com.tunisianprayertimes.quran.assets.QuranPackLayout
import java.io.File
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.ZipFile

internal object TafsirPackData {
    private fun file(context: Context): File? = when (val source = QuranAssets.resolve(context, QuranPackLayout.TAFSIR_ASSET)) {
        is QuranAssetSource.Downloaded -> source.file
        is QuranAssetSource.Bundled -> bundledFile(context)
        null -> null
    }

    // SQLite needs a filesystem path. A standalone APK already contains the approved database;
    // unpack it once per edition. A cache eviction simply restores it from the signed APK.
    @Volatile private var cached: File? = null

    @Synchronized private fun bundledFile(context: Context): File {
        cached?.takeIf(File::isFile)?.let { return it }
        val root = File(context.cacheDir, "tafsir-database")
        check(root.isDirectory || root.mkdirs()) { "Cannot open the tafsir cache" }
        return ZipFile(context.applicationInfo.sourceDir).use { apk ->
            val entry = checkNotNull(apk.getEntry("assets/${QuranPackLayout.TAFSIR_ASSET}")) { "Bundled tafsir is missing" }
            val target = File(root, "ashur-${entry.crc}-${entry.size}.db")
            if (!target.isFile || target.length() != entry.size) {
                val temp = File.createTempFile("ashur-", ".tmp", root)
                try {
                    val checksum = CRC32()
                    val size = CheckedInputStream(apk.getInputStream(entry), checksum).use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    check(size == entry.size && checksum.value == entry.crc) { "Incomplete bundled tafsir" }
                    check(temp.renameTo(target)) { "Cannot open the bundled tafsir" }
                } finally {
                    temp.delete()
                }
            }
            root.listFiles()?.filter { it != target && it.name.endsWith(".db") }?.forEach { it.delete() }
            cached = target
            target
        }
    }

    fun available(context: Context): Boolean = QuranAssets.resolve(context, QuranPackLayout.TAFSIR_ASSET) != null

    fun revision(context: Context): String? = file(context)?.let { "${it.path}:${it.length()}:${it.lastModified()}" }

    fun metadata(context: Context): Map<String, String> {
        val path = file(context) ?: return emptyMap()
        return SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            check(db.version == 1) { "Unsupported tafsir pack format" }
            db.rawQuery("SELECT key, value FROM meta WHERE key IN ('baseId', 'source', 'edition')", null).use { rows ->
                buildMap { while (rows.moveToNext()) put(rows.getString(0), rows.getString(1)) }
            }
        }
    }

    /** IO thread. A scalar statement reads the full chapter even when it exceeds a CursorWindow. */
    fun read(context: Context, surah: Int): String? {
        val path = file(context) ?: return null
        return read(path, surah)
    }

    internal fun read(path: File, surah: Int): String? {
        require(surah in 1..114)
        return SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            check(db.version == 1) { "Unsupported tafsir pack format" }
            db.compileStatement("SELECT payload FROM surahs WHERE surah = ?").use { statement ->
                statement.bindLong(1, surah.toLong())
                try {
                    statement.simpleQueryForString()
                } catch (_: SQLiteDoneException) {
                    null
                }
            }
        }
    }
}
