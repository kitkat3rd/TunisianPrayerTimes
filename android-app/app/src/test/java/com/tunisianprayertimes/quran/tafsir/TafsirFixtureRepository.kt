package com.tunisianprayertimes.quran.tafsir

import org.json.JSONObject

/** Synthetic chapter files in these tests stand in for independently installed SQLite databases. */
internal fun tafsirFixtureRepository(
    files: (String) -> String?,
    verseCount: (Int) -> Int? = { null },
): TafsirRepository {
    val manifest = "quran/tafsir/catalog.json"
    val hash = "0".repeat(64)
    fun catalog(): String? = files(manifest)?.let { raw ->
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@let raw
        val books = json.optJSONArray("books") ?: return@let raw
        for (i in 0 until books.length()) {
            val row = books.getJSONObject(i)
            if (!row.has("pack")) row.put("pack", "quran_tafsir_" + row.getString("id"))
            if (!row.has("baseId")) row.put("baseId", hash)
        }
        json.toString()
    }
    fun listed(id: String): JSONObject? {
        val rows = catalog()?.let(::JSONObject)?.optJSONArray("books") ?: return null
        return (0 until rows.length()).map { rows.getJSONObject(it) }.find { it.getString("id") == id }
    }
    val packs = object : TafsirPackAccess {
        override fun revision(bookId: String): String? {
            val rows = listed(bookId)?.getJSONArray("surahs") ?: return null
            val installed = (0 until rows.length()).mapNotNull { read(bookId, rows.getInt(it)) }
            return if (installed.isEmpty()) null else "${bookId}-${installed.hashCode()}"
        }
        override fun metadata(bookId: String): Map<String, String> {
            val row = listed(bookId) ?: return emptyMap()
            return listOf("baseId", "edition", "source").associateWith(row::getString)
        }
        override fun read(bookId: String, surah: Int): String? = files("quran/tafsir/$bookId/$surah.json")
    }
    return TafsirRepository({ path -> require(path == manifest); catalog() }, packs, verseCount)
}
