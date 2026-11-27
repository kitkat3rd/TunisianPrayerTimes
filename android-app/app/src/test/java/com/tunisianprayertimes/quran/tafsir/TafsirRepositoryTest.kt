package com.tunisianprayertimes.quran.tafsir

import com.tunisianprayertimes.quran.QuranVerseReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TafsirRepositoryTest {
    private val files = mutableMapOf<String, String>()
    private val repo = TafsirRepository(files::get)

    private fun book(id: String, vararg surahs: Int) = JSONObject()
        .put("id", id).put("source", "مصدر $id").put("edition", "طبعة الاختبار")
        .put("surahs", JSONArray(surahs.toList())).put("shareable", true)

    private fun catalog(vararg books: JSONObject, preview: Boolean = false) {
        files["quran/tafsir/catalog.json"] = JSONObject().put("version", 1).put("preview", preview)
            .put("books", JSONArray(books.toList())).toString()
    }

    private fun unit(id: String, from: Int, to: Int, text: String = id) =
        JSONObject().put("id", id).put("from", from).put("to", to).put("text", text)

    private fun chapter(book: String, surah: Int, vararg units: JSONObject, notes: JSONArray = JSONArray()) {
        files["quran/tafsir/$book/$surah.json"] = JSONObject().put("units", JSONArray(units.toList()))
            .put("notes", notes).toString()
    }

    @Test fun readerEntryRequiresANamedUsableEdition() {
        assertEquals(TafsirCatalogStatus.EMPTY, repo.catalogStatus())
        assertTrue(repo.books().isEmpty())
        files["quran/tafsir/catalog.json"] = "{bad json"
        val invalid = TafsirRepository(files::get)
        assertEquals(TafsirCatalogStatus.INVALID, invalid.catalogStatus())
        assertTrue(invalid.books().isEmpty())

        catalog(book("ashur", 1), book("qurtubi", 1), book("mukhtasar", 1), preview = true)
        val valid = TafsirRepository(files::get)
        assertEquals(TafsirCatalogStatus.PARTIAL, valid.catalogStatus())
        assertEquals(listOf("mukhtasar", "ashur"), valid.books().map { it.id })
        assertTrue(valid.books().all { it.preview && !it.shareable })
    }

    @Test fun conciseBooksUseIndependentPassagesAndNotesWithOneGlossary() {
        catalog(book("muyassar", 11), book("mukhtasar", 11), book("gharib", 11))
        val note = JSONObject().put("ayah", 82).put("text", "قراءة").put("spoken", "شرح مسموع")
        chapter("muyassar", 11, unit("joined", 81, 82), notes = JSONArray().put(note))
        chapter("mukhtasar", 11, unit("first", 81, 82), unit("second", 82, 82))
        chapter("gharib", 11, unit("word", 82, 82, "معنى").put("headword", "كلمة"))
        val ref = QuranVerseReference(11, 82)
        val joined = repo.read("muyassar", ref)
        val split = repo.read("mukhtasar", ref)
        assertEquals(listOf("joined"), joined.units.map { it.id })
        assertEquals(listOf("first", "second"), split.units.map { it.id })
        assertEquals("شرح مسموع", joined.notes.single().spoken)
        assertTrue(split.notes.isEmpty())
        assertEquals("كلمة", joined.glossary.single().headword)
        assertEquals(joined.glossary, split.glossary)
        assertEquals(setOf(QuranVerseReference(11, 81), ref), joined.exposed)
        assertEquals("مصدر mukhtasar", split.book?.source)
    }

    @Test fun notesFollowTheFocusedVerseOrTheEntireSharedPassage() {
        catalog(book("mukhtasar", 11))
        fun note(ayah: Int, text: String) = JSONObject().put("ayah", ayah).put("text", text)
        chapter("mukhtasar", 11, unit("a", 81, 82), unit("b", 82, 83), notes = JSONArray()
            .put(note(81, "قبل")).put(note(82, "هنا")).put(note(83, "بعد")).put(note(84, "خارج المقطع")))
        val reading = repo.read("mukhtasar", QuranVerseReference(11, 82))
        assertEquals(listOf("هنا"), reading.notes.map { it.text })
        assertEquals(listOf("قبل", "هنا", "بعد"), reading.passageNotes.map { it.text })
    }

    @Test fun passageNavigationResolvesOverlapsGapsAndSurahBoundaries() {
        catalog(book("juzayy", 65, 67, 69))
        chapter("juzayy", 65, unit("previous", 12, 12))
        chapter("juzayy", 67, unit("first", 1, 1), unit("mid-a", 8, 8), unit("mid-b", 8, 10),
            unit("cross-page", 13, 14).put("quotedVerses", JSONArray().put("112:1")))
        chapter("juzayy", 69, unit("next-surah", 1, 3))
        val first = repo.read("juzayy", QuranVerseReference(67, 1))
        assertEquals(QuranVerseReference(65, 12), first.previous)
        val mid = repo.read("juzayy", QuranVerseReference(67, 8))
        assertEquals("mid-a", mid.units.single().id)
        assertEquals("mid-b", mid.nextUnit)
        val next = repo.read("juzayy", mid.next!!, mid.nextUnit)
        assertEquals("mid-b", next.units.single().id)
        assertEquals("mid-a", next.previousUnit)
        val gap = repo.read("juzayy", QuranVerseReference(67, 11))
        assertTrue(gap.units.isEmpty())
        assertFalse(gap.unavailable)
        assertEquals("mid-b", gap.previousUnit)
        assertEquals("cross-page", gap.nextUnit)
        val crossPage = repo.read("juzayy", gap.next!!, gap.nextUnit)
        assertEquals(setOf(QuranVerseReference(67, 13), QuranVerseReference(67, 14), QuranVerseReference(112, 1)), crossPage.exposed)
        assertEquals(QuranVerseReference(69, 1), crossPage.next)
    }

    @Test fun unnumberedBasmalaAndSurahIntroductionRemainSeparate() {
        catalog(book("ashur", 1, 2).put("introSurahs", JSONArray().put(1)))
        chapter("ashur", 1, unit("intro", 0, 0).put("kind", "intro"), unit("basmala", 0, 0),
            unit("first-fatiha", 1, 1), unit("end-fatiha", 6, 7))
        chapter("ashur", 2, unit("first-baqarah", 1, 1))
        val first = repo.read("ashur", QuranVerseReference(1, 1))
        val basmala = repo.read("ashur", first.previous!!, first.previousUnit)
        assertEquals("basmala", basmala.units.single().id)
        assertNull(basmala.previous)
        assertEquals("intro", repo.introduction(1)?.id)
        assertNull(repo.introduction(2))
        assertEquals("end-fatiha", repo.read("ashur", QuranVerseReference(2, 1)).previousUnit)
    }

    @Test fun bundledExcerptRemainsReachableWithoutTheFullCommentary() {
        catalog(book("ashur", 67).put("pack", "quran_tafsir_ashur").put("introSurahs", JSONArray().put(67)))
        chapter("ashur", 67, unit("intro", 0, 0).put("kind", "intro"), unit("reading", 1, 2).put("kind", "excerpt"))
        val verse = QuranVerseReference(67, 1)
        assertTrue(repo.read("ashur", verse).unavailable)
        val excerpt = repo.read("ashur", verse, "reading")
        assertFalse(excerpt.unavailable)
        assertEquals(listOf("reading"), excerpt.units.map { it.id })
        assertNull(excerpt.next)
        assertEquals("intro", repo.introduction(67)?.id)
        assertTrue(repo.read("ashur", QuranVerseReference(67, 13), "reading").unavailable)
    }

    @Test fun corruptCoverageNeverMasqueradesAsAnUnexplainedVerse() {
        catalog(book("juzayy", 67))
        chapter("juzayy", 67, unit("repeated-id", 13, 14), unit("repeated-id", 15, 16))
        assertTrue(repo.read("juzayy", QuranVerseReference(67, 13)).unavailable)
    }

    @Test fun missingPackStepsUseTheMushafsChapterCounts() {
        catalog(book("ashur", 1, 2).put("pack", "quran_tafsir_ashur"))
        val reader = TafsirRepository(files::get) { surah -> mapOf(1 to 7, 2 to 285)[surah] }
        val fatihaEnd = reader.read("ashur", QuranVerseReference(1, 7))
        assertTrue(fatihaEnd.unavailable)
        assertEquals(QuranVerseReference(2, 1), fatihaEnd.next)
        assertEquals(fatihaEnd.verse, reader.read("ashur", fatihaEnd.next!!).previous)
        assertEquals(QuranVerseReference(1, 0), reader.read("ashur", QuranVerseReference(1, 1)).previous)
        assertEquals(QuranVerseReference(3, 1), reader.read("ashur", QuranVerseReference(2, 285)).next)
    }
}
