package com.tunisianprayertimes.ui

import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranHighlightRect
import com.tunisianprayertimes.quran.QuranHighlights
import com.tunisianprayertimes.quran.QuranPage
import com.tunisianprayertimes.quran.QuranSearchEntry
import com.tunisianprayertimes.quran.QuranSurah
import com.tunisianprayertimes.quran.QuranVerseReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The bands of the mushaf the hifz sheets show, cut from the bundled index and verse places. */
class QuranExcerptTest {
    private val catalog: QuranCatalog by lazy {
        val source = JSONObject(File("src/main/assets/quran/index.json").readText())
        val chapters = source.getJSONArray("surahs")
        val surahs = List(chapters.length()) { i ->
            val chapter = chapters.getJSONObject(i)
            QuranSurah(chapter.getInt("number"), chapter.getString("name"), chapter.getInt("page"), chapter.optInt("verseCount"))
        }
        val rows = source.getJSONArray("entries")
        val entries = List(rows.length()) { i ->
            val entry = rows.getJSONObject(i)
            QuranSearchEntry(
                surah = entry.getInt("surah"), ayah = entry.getInt("ayah"), text = entry.getString("text"), page = entry.getInt("page"),
                isFirstFragment = entry.getBoolean("isFirstFragment"), isLastFragment = entry.getBoolean("isLastFragment"),
            )
        }
        QuranCatalog((1..603).map { QuranPage(it, "", emptyList()) }, surahs, entries)
    }

    private val highlights: QuranHighlights by lazy {
        val pages = JSONObject(File("src/main/assets/quran/highlights.json").readText()).getJSONArray("pages")
        QuranHighlights(List(pages.length()) { i -> pages.getJSONObject(i) }.associate { page ->
            val verses = page.getJSONArray("verses")
            page.getInt("page") to List(verses.length()) { j -> verses.getJSONObject(j) }.associate { verse ->
                val boxes = verse.getJSONArray("rects")
                QuranVerseReference(verse.getInt("surah"), verse.getInt("ayah")) to List(boxes.length()) { k ->
                    val r = boxes.getJSONArray(k)
                    QuranHighlightRect(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat())
                }
            }
        })
    }

    private fun verse(surah: Int, ayah: Int) = QuranVerseReference(surah, ayah)
    private fun rects(page: Int, v: QuranVerseReference) = highlights.rectangles(page, v.surah, v.ayah)

    @Test
    fun aVerseWithTheEndOfTheOneBefore_isOneBandOnItsPage() {
        // Fussilat 36, the verse of the report: the end of 35 is on its page, just above it.
        val asked = verse(41, 36)
        val page = catalog.pagesForVerse(41, 36).single()
        val bands = quranExcerpts(highlights, catalog, asked, linesBefore = 1)
        val band = bands.single()
        assertEquals(page, band.page)
        assertEquals(rects(page, asked), band.marked)
        assertTrue("no grey here", band.muted.isEmpty())
        // Only the neighbours sharing its lines are covered: never 35 or 36, every other verse the band crosses.
        val shown = setOf(verse(41, 35), asked)
        val neighbours = highlights.covers(page, catalog.versesOnPage(page).filter { it.ayah > 0 && it.reference !in shown }
            .flatMap { rects(page, it.reference) }).filter { it.bottom > band.top && it.top < band.bottom }
        assertEquals(neighbours.toSet(), band.covered.toSet())
        val endOfBefore = rects(page, verse(41, 35)).maxBy { it.top }
        assertTrue("the band shows the last line of 35", band.top <= endOfBefore.top && band.bottom >= rects(page, asked).maxOf { it.bottom })
    }

    @Test
    fun aVerseThatOpensItsPage_hasTheEndOfTheOneBeforeOnThePageBefore() {
        val asked = catalog.verses.first { v ->
            v.ayah > 1 && v.pages.size == 1 && catalog.pagesForVerse(v.surah, v.ayah - 1).lastOrNull() == v.pages.first() - 1
        }.reference
        val page = catalog.pagesForVerse(asked.surah, asked.ayah).single()
        val bands = quranExcerpts(highlights, catalog, asked, linesBefore = 1)
        assertEquals(listOf(page - 1, page), bands.map { it.page })
        assertTrue("the verse before is only context", bands[0].marked.isEmpty())
        assertEquals(rects(page, asked), bands[1].marked)
    }

    @Test
    fun aQuestionCoversTheVerseAndWhatFollowsOnItsLines_neverTheVerseBefore() {
        val asked = verse(41, 36)
        val page = catalog.pagesForVerse(41, 36).single()
        val band = quranExcerpts(highlights, catalog, asked, linesBefore = 1, cover = true).single()
        assertTrue(band.covered.containsAll(highlights.covers(page, rects(page, asked))))
        assertTrue(rects(page, verse(41, 35)).none { it in band.covered })
        assertTrue("only what the band shows", band.covered.all { it.bottom > band.top && it.top < band.bottom })
    }

    @Test
    fun aReviewCueShowsTheEarlierVerseOnItsLine_asOnThePage() {
        // The reported card asks what follows 39:30. Its cue is at the foot of printed page 461,
        // on the same line as the end of 39:29.
        val cue = verse(39, 30)
        val earlier = verse(39, 29)
        val page = catalog.pagesForVerse(cue.surah, cue.ayah).single()
        val band = quranExcerpts(highlights, catalog, cue, showPrevious = true).single()
        val earlierCovers = highlights.covers(page, rects(page, earlier)).filter { it.bottom > band.top && it.top < band.bottom }
        assertTrue("the earlier verse shares the cue's band", earlierCovers.isNotEmpty())
        assertTrue("the visible context counts as seen", earlier in band.context)
        assertTrue("the earlier verse is not covered", earlierCovers.none { it in band.covered })
        assertEquals(rects(page, cue), band.marked)

        // Other excerpts keep their answer-safe default.
        val ordinary = quranExcerpts(highlights, catalog, cue).single()
        assertTrue(ordinary.covered.containsAll(earlierCovers))
        assertTrue(ordinary.context.isEmpty())
    }

    @Test
    fun anEarlierUnansweredVerseStillStaysCoveredInTheCue() {
        val cue = verse(39, 30)
        val earlier = verse(39, 29)
        val page = catalog.pagesForVerse(cue.surah, cue.ayah).single()
        val band = quranExcerpts(highlights, catalog, cue, showPrevious = true, protectedVerses = setOf(earlier)).single()
        val earlierCovers = highlights.covers(page, rects(page, earlier)).filter { it.bottom > band.top && it.top < band.bottom }
        assertTrue(earlierCovers.isNotEmpty())
        assertTrue(band.covered.containsAll(earlierCovers))
        assertFalse(earlier in band.context)
    }

    @Test
    fun aQuestionShowsEarlierNeighbours_butNotItsAnswerOrFollowingVerses() {
        val refs = (1..4).map { verse(1, it) }
        val boxes = listOf(
            QuranHighlightRect(0.78f, 0.4f, 0.94f, 0.5f),
            QuranHighlightRect(0.54f, 0.4f, 0.78f, 0.5f),
            QuranHighlightRect(0.30f, 0.4f, 0.54f, 0.5f),
            QuranHighlightRect(0.06f, 0.4f, 0.30f, 0.5f),
        )
        val index = QuranCatalog(
            listOf(QuranPage(1, "", emptyList())), listOf(QuranSurah(1, "test", 1)),
            refs.map { QuranSearchEntry(it.surah, it.ayah, "verse", 1) },
        )
        val places = QuranHighlights(mapOf(1 to refs.zip(boxes).associate { it.first to listOf(it.second) }))
        val band = quranExcerpts(places, index, refs[2], linesBefore = 1, cover = true, showPrevious = true).single()
        assertEquals(setOf(refs[0]), band.context)
        assertEquals(places.covers(1, boxes.drop(2)).toSet(), band.covered.toSet())

        val protected = quranExcerpts(
            places, index, refs[2], linesBefore = 1, cover = true, showPrevious = true, protectedVerses = setOf(refs[0]),
        ).single()
        assertTrue(protected.context.isEmpty())
        assertEquals(places.covers(1, listOf(boxes[0], boxes[2], boxes[3])).toSet(), protected.covered.toSet())
    }

    @Test
    fun aReviewQuestionAcrossPages_keepsTheAnswerCoveredAndTheEarlierContextVisible() {
        val asked = verse(39, 31)
        val earlier = verse(39, 29)
        val bands = quranExcerpts(highlights, catalog, asked, linesBefore = 1, cover = true, showPrevious = true)
        assertEquals(listOf(460, 461), bands.map { it.page })
        assertTrue(earlier in bands.first().context)
        assertTrue(bands.last().covered.containsAll(highlights.covers(461, rects(461, asked))))
        assertFalse(bands.any { asked in it.context })
    }

    @Test
    fun earlierContextMayBelongToThePreviousSurah() {
        val refs = listOf(verse(1, 7), verse(2, 1), verse(2, 2))
        val boxes = listOf(
            QuranHighlightRect(0.70f, 0.4f, 0.94f, 0.5f),
            QuranHighlightRect(0.40f, 0.4f, 0.70f, 0.5f),
            QuranHighlightRect(0.06f, 0.4f, 0.40f, 0.5f),
        )
        val index = QuranCatalog(
            listOf(QuranPage(1, "", emptyList())), listOf(QuranSurah(1, "one", 1), QuranSurah(2, "two", 1)),
            refs.map { QuranSearchEntry(it.surah, it.ayah, "verse", 1) },
        )
        val places = QuranHighlights(mapOf(1 to refs.zip(boxes).associate { it.first to listOf(it.second) }))
        val band = quranExcerpts(places, index, refs[1], showPrevious = true).single()
        assertEquals(setOf(refs[0]), band.context)
        assertEquals(places.covers(1, listOf(boxes[2])), band.covered)
    }

    @Test
    fun aLookAlikeIsGreyNotViolet() {
        val other = verse(2, 58)
        val band = quranExcerpts(highlights, catalog, other, mark = QuranExcerptMark.Other).first()
        assertTrue(band.marked.isEmpty())
        assertEquals(rects(band.page, other), band.muted)
    }

    @Test
    fun theUnknownVerseHasNoBand() {
        assertTrue(quranExcerpts(highlights, catalog, verse(41, 999)).isEmpty())
    }

    @Test
    fun placeOnThePage() {
        assertEquals("أعلى", quranExcerptPlace(QuranExcerpt(1, 0.05f, 0.2f)))
        assertEquals("وسط", quranExcerptPlace(QuranExcerpt(1, 0.4f, 0.55f)))
        assertEquals("أسفل", quranExcerptPlace(QuranExcerpt(1, 0.8f, 0.95f)))
        assertNotNull(quranExcerptPlace(QuranExcerpt(1, 0f, 1f)))
    }
}
