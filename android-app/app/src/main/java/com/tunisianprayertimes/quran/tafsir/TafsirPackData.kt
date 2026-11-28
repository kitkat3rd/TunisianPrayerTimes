package com.tunisianprayertimes.quran.tafsir

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDoneException
import com.tunisianprayertimes.quran.assets.QuranAssetSource
import com.tunisianprayertimes.quran.assets.QuranAssets
import com.tunisianprayertimes.quran.assets.QuranPackLayout
import java.io.File

/** The pack source owns verification, retained editions and removal. Each work has its own database. */
internal interface TafsirPackAccess {
    fun revision(bookId: String): String?
    fun metadata(bookId: String): Map<String, String>
    fun read(bookId: String, surah: Int): String?
}

internal class TafsirPackData(
    private val file: (String) -> File?,
    private val editionRevision: (String) -> String?,
) : TafsirPackAccess {
    constructor(file: (String) -> File?) : this(file, { bookId ->
        file(bookId)?.let { "${it.path}:${it.length()}:${it.lastModified()}" }
    })

    constructor(context: Context) : this(
        { bookId -> (QuranAssets.resolve(context, QuranPackLayout.tafsirAsset(bookId)) as? QuranAssetSource.Downloaded)?.file },
        { bookId -> QuranAssets.revision(context, QuranPackLayout.tafsirAsset(bookId)) },
    )

    override fun revision(bookId: String): String? = editionRevision(bookId)

    override fun metadata(bookId: String): Map<String, String> {
        val path = file(bookId) ?: return emptyMap()
        return SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            check(db.version == 1) { "Unsupported tafsir pack format" }
            db.rawQuery("SELECT key, value FROM meta WHERE key IN ('id', 'baseId', 'source', 'edition', 'surahs', 'introSurahs', 'shareable')", null).use { rows ->
                buildMap { while (rows.moveToNext()) put(rows.getString(0), rows.getString(1)) }
            }
        }
    }

    override fun read(bookId: String, surah: Int): String? = file(bookId)?.let { read(it, surah) }

    companion object {
        /** A scalar statement reads the full chapter even when it exceeds a CursorWindow. */
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
}
