package com.tunisianprayertimes.quran.tafsir

import android.content.Context
import com.tunisianprayertimes.quran.QuranRepository
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.assets.QuranPackLayout
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException

data class TafsirBook(
    val id: String,
    val title: String,
    val source: String,
    val edition: String,
    val shareable: Boolean,
    val surahs: Set<Int>,
    val introSurahs: Set<Int> = emptySet(),
    val pack: String? = null,
    val preview: Boolean = false,
    val baseId: String? = null,
)

/** Reviewed offsets in displayed commentary text, measured in UTF-16 code units. */
data class TafsirHighlight(val ayah: Int, val start: Int, val end: Int)

/** A published passage can cover several Madani verses, or share one with another passage. */
data class TafsirUnit(
    val id: String,
    val from: Int,
    val to: Int,
    val text: String,
    val headword: String? = null,
    val citation: String? = null,
    val kind: String = "passage",
    val quotedVerses: Set<QuranVerseReference> = emptySet(),
    val highlights: List<TafsirHighlight> = emptyList(),
    val fromWord: Int? = null,
    val toWord: Int? = null,
    val correctionReason: String? = null,
    val correctionDate: String? = null,
)

data class TafsirNote(
    val text: String,
    val spoken: String? = null,
    val targetBook: String? = null,
    val targetUnit: String? = null,
)

data class TafsirReading(
    val book: TafsirBook?,
    val verse: QuranVerseReference,
    val units: List<TafsirUnit> = emptyList(),
    val glossary: List<TafsirUnit> = emptyList(),
    val notes: List<TafsirNote> = emptyList(),
    val previous: QuranVerseReference? = null,
    val next: QuranVerseReference? = null,
    val previousUnit: String? = null,
    val nextUnit: String? = null,
    val exposed: Set<QuranVerseReference> = emptySet(),
    val unavailable: Boolean = false,
    val previousEnd: Int? = null,
    val nextEnd: Int? = null,
    val passageNotes: List<TafsirNote> = notes,
    val glossaryBook: TafsirBook? = null,
    val glossaryAvailable: Boolean = false,
)

private data class TafsirChapter(
    val units: List<TafsirUnit>,
    val notes: Map<Int, List<TafsirNote>>,
) {
    val passages = units.filter { it.kind == "passage" }
}

private data class TafsirTarget(val verse: QuranVerseReference, val id: String, val end: Int)

enum class TafsirCatalogStatus { EMPTY, AVAILABLE, PARTIAL, INVALID }

/** Construct once per reader. Asset and pack reads belong on an IO dispatcher. */
class TafsirRepository private constructor(
    private val context: Context?,
    private val assetText: (String) -> String?,
    private val packs: TafsirPackAccess,
    private val verseCount: (Int) -> Int?,
) {
    constructor(context: Context) : this(
        context.applicationContext, assetReader(context.applicationContext), TafsirPackData(context.applicationContext),
        { surah -> QuranRepository.loaded()?.surahs?.getOrNull(surah - 1)?.verseCount },
    )
    internal constructor(
        assetText: (String) -> String?, packs: TafsirPackAccess, verseCount: (Int) -> Int? = { null },
    ) : this(null, assetText, packs, verseCount)

    private val indexText by lazy { decode { assetText("quran/tafsir/catalog.json") } }
    private val index by lazy { decode { indexText?.let(::JSONObject) }?.takeIf { it.optInt("version") == 1 } }
    private val catalog by lazy {
        val root = index
        val preview = root?.optBoolean("preview") == true
        val entries = root?.optJSONArray("books")
        val parsed = (0 until (entries?.length() ?: 0)).mapNotNull { i ->
            decode { parseBook(requireNotNull(entries).getJSONObject(i), preview) }
        }
        // An ambiguous ID could select the wrong source or rights. Other valid books remain usable.
        parsed.groupBy { it.id }.values.mapNotNull { it.singleOrNull() }
            .sortedBy { titles.keys.indexOf(it.id) }
    }
    // Bounded to the working set: commonly the open book, its neighbours and the glossary.
    private val cachedChapters = object : LinkedHashMap<Triple<String, Int, String>, TafsirChapter>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<String, Int, String>, TafsirChapter>) =
            size > 6
    }
    private val packedEditions = mutableMapOf<String, Pair<String, TafsirBook>>()

    /** Published catalog metadata, including works available to download before any are installed. */
    fun downloadableBooks(): List<TafsirBook> = catalog.filter { !it.preview && it.pack != null }
    fun declaredPack(bookId: String): String? = downloadableBooks().firstOrNull { it.id == bookId }?.pack

    fun books(): List<TafsirBook> {
        val app = context
        val metadata = index
        if (app != null && (metadata != null || indexText == null) && metadata?.optBoolean("preview") != true) {
            val installed = catalog.mapNotNull { book ->
                val revision = packRevision(book) ?: return@mapNotNull null
                val base = installedEdition(book, revision)?.baseId ?: return@mapNotNull null
                book.id to setOf(base)
            }.toMap()
            TafsirCorrections.scheduleEditions(app, metadata ?: JSONObject(), installed)
        }
        return catalog
    }

    fun catalogStatus(): TafsirCatalogStatus {
        if (indexText == null) return TafsirCatalogStatus.EMPTY
        val entries = index?.optJSONArray("books") ?: return TafsirCatalogStatus.INVALID
        return when {
            entries.length() == 0 -> TafsirCatalogStatus.EMPTY
            catalog.isEmpty() -> TafsirCatalogStatus.INVALID
            catalog.size < entries.length() -> TafsirCatalogStatus.PARTIAL
            else -> TafsirCatalogStatus.AVAILABLE
        }
    }

    fun isInstalled(book: TafsirBook): Boolean = packRevision(book) != null

    fun introductionReading(surah: Int): Pair<TafsirBook, TafsirUnit>? {
        val listed = catalog.find { it.id == "ashur" } ?: return null
        val revision = packRevision(listed) ?: return null
        val book = installedEdition(listed, revision) ?: return null
        if (surah !in book.introSurahs) return null
        val intro = chapter(book, surah, revision)?.units?.firstOrNull { it.kind == "intro" } ?: return null
        return book to corrected(book, listOf(intro)).first()
    }

    fun introduction(surah: Int): TafsirUnit? = introductionReading(surah)?.second

    /** [targetUnit] distinguishes overlapping passages and the exact target of a reading note. */
    fun read(bookId: String, verse: QuranVerseReference, targetUnit: String? = null): TafsirReading {
        val listed = catalog.find { it.id == bookId }
        if (listed == null || verse.surah !in 1..114 || verse.ayah !in 0..286) {
            return TafsirReading(listed, verse, unavailable = true)
        }
        val revision = packRevision(listed)
        val book = revision?.let { installedEdition(listed, it) }
        val chapter = if (book != null) chapter(book, verse.surah, revision) else null
        val concise = bookId == "muyassar" || bookId == "mukhtasar"
        if (book == null || (chapter == null && verse.surah in book.surahs)) {
            val missing = missingChapter(book ?: listed, verse)
            return if (concise) withGlossary(missing) else missing
        }
        val candidates = chapter?.passages.orEmpty()
        val intersecting = candidates.filter { verse.ayah in it.from..it.to }
        val requested = targetUnit?.let { id -> intersecting.firstOrNull { it.id == id } }
        // A concise book can explain both parts of a joined verse in separate paragraphs.
        val selected = when {
            targetUnit != null && requested == null -> emptyList()
            requested != null && concise -> intersecting.filter { it.from == requested.from && it.to == requested.to }
            concise || book.id == "gharib" -> intersecting
            requested != null -> listOf(requested)
            else -> intersecting.take(1)
        }
        val visible = corrected(book, selected)
        val previous = neighbour(book, verse, candidates, selected, -1, revision)
        val next = neighbour(book, verse, candidates, selected, 1, revision)
        val reading = TafsirReading(
            book, verse, units = visible,
            notes = chapter?.notes?.get(verse.ayah).orEmpty(),
            previous = previous?.verse, next = next?.verse,
            previousUnit = previous?.id, nextUnit = next?.id,
            exposed = exposed(verse, visible),
            previousEnd = previous?.end, nextEnd = next?.end,
            passageNotes = visible.flatMap { it.from..it.to }.distinct().sorted()
                .flatMap { chapter?.notes?.get(it).orEmpty() }.distinct(),
        )
        return if (concise) withGlossary(reading) else reading
    }

    /** Glosses are an independent work; the reader can download them before either concise book. */
    private fun withGlossary(reading: TafsirReading): TafsirReading {
        val listed = catalog.find { it.id == "gharib" } ?: return reading
        val revision = packRevision(listed) ?: return reading.copy(glossaryBook = listed)
        val book = installedEdition(listed, revision) ?: return reading.copy(glossaryBook = listed)
        val rows = chapter(book, reading.verse.surah, revision)?.passages
        val available = rows != null || reading.verse.surah !in book.surahs
        val selected = rows.orEmpty().filter { gloss ->
            if (reading.units.isEmpty()) reading.verse.ayah in gloss.from..gloss.to
            else reading.units.any { it.from <= gloss.to && gloss.from <= it.to }
        }
        val visible = corrected(book, selected)
        val exposed = if (visible.isEmpty()) reading.exposed else reading.exposed + exposed(reading.verse, visible)
        return reading.copy(glossary = visible, glossaryBook = book, glossaryAvailable = available, exposed = exposed)
    }

    /** An uninstalled commentary has no passage index. Its download card can still follow Quran verses. */
    private fun missingChapter(book: TafsirBook, verse: QuranVerseReference): TafsirReading {
        if (book.pack == null) return TafsirReading(book, verse, unavailable = true)
        val last = verseCount(verse.surah) ?: return TafsirReading(book, verse, unavailable = true)
        val first = if (verse.surah == 1) 0 else 1
        val previous = when {
            verse.ayah > first -> verse.copy(ayah = verse.ayah - 1)
            verse.surah > 1 -> verseCount(verse.surah - 1)?.let { QuranVerseReference(verse.surah - 1, it) }
            else -> null
        }
        val next = when {
            verse.ayah < last -> verse.copy(ayah = verse.ayah + 1)
            verse.surah < 114 -> QuranVerseReference(verse.surah + 1, 1)
            else -> null
        }
        return TafsirReading(book, verse, previous = previous, next = next, unavailable = true)
    }

    private fun exposed(verse: QuranVerseReference, units: List<TafsirUnit>) = buildSet {
        add(verse)
        for (entry in units) {
            for (ayah in entry.from..entry.to) add(QuranVerseReference(verse.surah, ayah))
            addAll(entry.quotedVerses)
        }
    }

    private fun neighbour(
        book: TafsirBook,
        verse: QuranVerseReference,
        rows: List<TafsirUnit>,
        selection: List<TafsirUnit>,
        direction: Int,
        revision: String?,
    ): TafsirTarget? {
        val forward = direction > 0
        val edge = if (forward) selection.lastOrNull() else selection.firstOrNull()
        val here = when {
            edge != null -> rows.getOrNull(rows.indexOf(edge) + direction)
            forward -> rows.firstOrNull { it.from > verse.ayah }
            else -> rows.lastOrNull { it.to < verse.ayah }
        }
        if (here != null) return TafsirTarget(QuranVerseReference(verse.surah, here.from), here.id, here.to)
        val elsewhere = if (forward) book.surahs.sorted() else book.surahs.sortedDescending()
        for (surah in elsewhere) {
            if ((surah - verse.surah) * direction <= 0) continue
            val passages = chapter(book, surah, revision)?.passages ?: continue
            val row = if (forward) passages.firstOrNull() else passages.lastOrNull()
            if (row != null) return TafsirTarget(QuranVerseReference(surah, row.from), row.id, row.to)
        }
        return null
    }

    private fun packRevision(book: TafsirBook): String? =
        decode { packs.revision(book.id) }

    @Synchronized
    private fun installedEdition(book: TafsirBook, revision: String): TafsirBook? {
        packedEditions[book.id]?.takeIf { it.first == revision }?.let { return it.second }
        val meta = decode { packs.metadata(book.id) } ?: return null
        if (packRevision(book) != revision) return null
        // Older verified Ashur packs may have only the three original metadata fields.
        if (meta["id"] != null && meta["id"] != book.id) return null
        val base = meta["baseId"]?.takeIf(hash::matches) ?: return null
        val edition = meta["edition"]?.takeIf(String::isNotBlank) ?: return null
        val source = meta["source"]?.takeIf(String::isNotBlank) ?: return null
        val surahs = meta["surahs"]?.let { decode { JSONArray(it).surahs() } ?: return null } ?: book.surahs
        val intros = meta["introSurahs"]?.let { decode { JSONArray(it).surahs() } ?: return null }
            ?: book.introSurahs.intersect(surahs)
        if (surahs.isEmpty() || !surahs.containsAll(intros)) return null
        val actual = book.copy(
            source = source, edition = edition, baseId = base,
            surahs = surahs, introSurahs = intros,
            shareable = book.shareable && (meta["shareable"]?.toBooleanStrictOrNull() ?: (book.baseId == base)),
        )
        packedEditions[book.id] = revision to actual
        return actual
    }

    private fun chapter(book: TafsirBook, surah: Int, revision: String?): TafsirChapter? {
        if (surah !in book.surahs || revision == null) return null
        return cached(book, surah, revision) {
            val text = packs.read(book.id, surah)
            text?.takeIf { packRevision(book) == revision }
        }
    }

    private fun cached(book: TafsirBook, surah: Int, storage: String, source: () -> String?): TafsirChapter? {
        val key = Triple(book.id, surah, storage)
        synchronized(cachedChapters) { cachedChapters[key]?.let { return it } }
        val parsed = decode { source()?.let { parseChapter(JSONObject(it), surah) } } ?: return null
        synchronized(cachedChapters) { cachedChapters[key] = parsed }
        return parsed
    }

    private fun corrected(book: TafsirBook, rows: List<TafsirUnit>): List<TafsirUnit> {
        val app = context ?: return rows
        val base = book.baseId ?: return rows
        if (book.preview || rows.isEmpty()) return rows
        val changes = TafsirCorrections.forBase(app, book.id, base)
        if (changes.isEmpty()) return rows
        return rows.map { row ->
            changes[row.id]?.let { row.copy(text = it.text, highlights = emptyList(), correctionReason = it.reason, correctionDate = it.date) } ?: row
        }
    }

    companion object {
        private val titles get() = QuranPackLayout.tafsirTitles
        private val hash = Regex("[a-f0-9]{64}")

        private fun assetReader(context: Context): (String) -> String? = { path ->
            try {
                context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: FileNotFoundException) {
                null
            }
        }

        private fun parseBook(row: JSONObject, preview: Boolean): TafsirBook {
            val id = row.getString("id")
            val title = requireNotNull(titles[id])
            val surahs = row.getJSONArray("surahs").surahs()
            val intros = row.optJSONArray("introSurahs")?.surahs().orEmpty()
            val pack = row.requiredText("pack")
            require(pack == "quran_tafsir_" + id)
            require(surahs.isNotEmpty() && surahs.containsAll(intros))
            return TafsirBook(
                id, title, row.requiredText("source"), row.requiredText("edition"),
                !preview && row.optBoolean("shareable", false), surahs, intros,
                pack, preview, row.nonempty("baseId")?.takeIf(hash::matches),
            )
        }

        private fun parseChapter(json: JSONObject, surah: Int): TafsirChapter {
            val array = json.getJSONArray("units")
            val units = List(array.length()) { parseUnit(array.getJSONObject(it), surah) }
                .sortedBy { it.from } // stable for units beginning in the same verse
            require(units.map { it.id }.distinct().size == units.size)
            val notes = json.optJSONArray("notes").objects().groupBy({ it.getInt("ayah") }, { row ->
                val targetBook = row.nonempty("targetBook")
                val targetUnit = row.nonempty("targetUnit")
                require((targetBook == null && targetUnit == null) || (targetBook in titles && targetUnit != null))
                TafsirNote(row.requiredText("text"), row.nonempty("spoken"), targetBook, targetUnit)
            })
            require(notes.keys.all { it in 0..286 && (it > 0 || surah == 1) })
            return TafsirChapter(units, notes)
        }

        private fun parseUnit(row: JSONObject, surah: Int): TafsirUnit {
            val from = row.getInt("from")
            val to = row.getInt("to")
            val kind = row.nonempty("kind") ?: "passage"
            require(kind in setOf("intro", "passage", "excerpt"))
            require(from in 0..286 && to in from..286)
            require(if (kind == "intro") from == 0 && to == 0 else from > 0 || surah == 1)
            val text = row.requiredText("text")
            val quotes = row.optJSONArray("quotedVerses")
            val quoted = buildSet {
                if (quotes != null) for (i in 0 until quotes.length()) {
                    val parts = quotes.getString(i).split(':')
                    require(parts.size == 2)
                    val s = parts[0].toInt()
                    val a = parts[1].toInt()
                    require(s in 1..114 && a in 0..286 && (a > 0 || s == 1))
                    add(QuranVerseReference(s, a))
                }
            }
            val highlights = row.optJSONArray("highlights").objects().map {
                val start = it.getInt("start")
                val end = it.getInt("end")
                val ayah = it.getInt("ayah")
                require(ayah in from..to && start >= 0 && start < end && end <= text.length)
                TafsirHighlight(ayah, start, end)
            }
            return TafsirUnit(
                row.requiredText("id"), from, to, text, row.nonempty("headword"), row.nonempty("citation"),
                kind, quoted, highlights, row.optionalPositive("fromWord"), row.optionalPositive("toWord"),
            )
        }
    }
}

private fun JSONObject.nonempty(key: String) =
    if (isNull(key)) null else getString(key).takeIf { it.isNotBlank() }

private fun JSONObject.requiredText(key: String): String = requireNotNull(nonempty(key))
private fun JSONObject.optionalPositive(key: String) = if (isNull(key)) null else getInt(key).also { require(it > 0) }
private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }
private fun JSONArray.surahs(): Set<Int> = List(length()) { getInt(it).also { s -> require(s in 1..114) } }.toSet()

private inline fun <T> decode(read: () -> T): T? = try {
    read()
} catch (_: JSONException) {
    null
} catch (_: IllegalArgumentException) {
    null
} catch (_: IllegalStateException) {
    null
} catch (_: IOException) {
    null
} catch (_: android.database.sqlite.SQLiteException) {
    null
}
