package com.tunisianprayertimes.quran.hifz

import android.app.Application
import com.tunisianprayertimes.quran.QuranVerseReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A forgotten passage (owner, 12 November): one point from its first verse to its last, asked as one long verse. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HifzPassageTest {
    private fun verse(ayah: Int) = QuranVerseReference(67, ayah)

    private fun passage(start: Int, last: Int, id: Long = 1L, fresh: Int = 5, due: Long = 100L) = HifzPoint(
        id = id, verse = verse(start), mistakes = listOf(HifzMistake(HifzKind.Passage, at = 10L)),
        dueDay = due, createdAt = 10L, fresh = fresh, last = last,
    )

    private fun single(ayah: Int, id: Long = 2L, fresh: Int = 5, due: Long = 100L) = HifzPoint(
        id = id, verse = verse(ayah), mistakes = listOf(HifzMistake(HifzKind.Word, slipWords = setOf(1), at = 10L)),
        dueDay = due, createdAt = 10L, fresh = fresh,
    )

    @Test
    fun aPassage_asksItsVersesLinkedToTheVerseBefore() {
        val point = passage(3, 5)
        assertTrue(point.passage)
        assertEquals(listOf(verse(3), verse(4), verse(5)), point.span)
        assertEquals(verse(5), point.end)
        assertTrue(point.asks(verse(4)))
        assertFalse(point.asks(verse(6)))
        assertTrue(point.linked)
        assertEquals(setOf(verse(2), verse(3), verse(4), verse(5)), point.answerVerses())
        // Drilled and heard whole, from the end of the verse before it.
        val drilled = hifzPassage(point)
        assertEquals(verse(2), drilled.from)
        assertEquals(verse(5), drilled.to)
    }

    @Test
    fun aPassage_saysHowMuchToReciteNeverItsWords() {
        assertEquals("اقرأ آيتين من حفظك", hifzPassageLength(passage(3, 4)))
        assertEquals("اقرأ 3${HifzReminder.NB}آيات من حفظك", hifzPassageLength(passage(3, 5)))
        assertEquals("اقرأ 12${HifzReminder.NB}آية من حفظك", hifzPassageLength(passage(3, 14)))
        assertNull(hifzPassageLength(single(3)))
    }

    @Test
    fun anyVerseOfAPassageSeen_putsItsQuestionOff() {
        val now = 10_000_000L
        val point = passage(3, 5, due = 0L)
        assertTrue(point.isDue(today = 0L, now = now))
        // Its middle verse seen a moment ago: a recall now would be none.
        assertFalse(point.isDue(today = 0L, now = now, seen = mapOf(verse(4) to now - 60_000L)))
        val check = passage(3, 5, fresh = 0, due = 0L)
        assertFalse(check.isDue(today = 0L, now = now, seenToday = setOf(verse(5))))
    }

    @Test
    fun aVersesOwnPoint_isNotThePassageThatHoldsIt() {
        val points = listOf(passage(3, 5, id = 1L), single(3, id = 2L))
        assertEquals(2L, points.pointOf(verse(3))?.id)
        assertEquals(1L, points.passageOf(verse(3), 5)?.id)
        assertNull(points.passageOf(verse(3), 6))
        assertEquals(setOf(1L, 2L), points.pointsAsking(verse(3)).map { it.id }.toSet())
    }

    @Test
    fun aPassageAndAVerseInIt_areNeverAskedInOneSitting() {
        // Each one's answer shows the other's: they take turns, as any loop does.
        val points = listOf(passage(3, 5, id = 1L, due = 0L), single(4, id = 2L, due = 0L))
        val batch = points.reviewBatch(today = 0L, now = 10_000_000L, limit = 5)
        assertEquals(1, batch.size)
        // A verse after the passage comes after it: its question shows the passage's last verse.
        val after = listOf(single(6, id = 3L, due = 0L), passage(3, 5, id = 1L, due = 0L)).answerSafeOrder()
        assertEquals(listOf(1L, 3L), after.map { it.id })
    }

    @Test
    fun aPassage_isStoredWithItsLastVerse() {
        val point = passage(3, 5)
        val back = decodeHifzPoints(encodeHifzPoints(listOf(point, single(4)))).associateBy { it.id }
        assertEquals(5, back.getValue(1L).last)
        assertEquals(HifzKind.Passage, back.getValue(1L).mistakes.single().kind)
        assertNull(back.getValue(2L).last)
    }

    @Test
    fun recordingAPassage_neverMergesAVersesOwnPoint_andTheSamePassageCountsAgain() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        try {
            val own = HifzRepository.record(app, verse(4), HifzKind.Word, slipWords = setOf(2), now = 1_000L)
            val first = HifzRepository.recordPassageWithUndo(app, verse(3), 5, now = 1_000L)
            val saved = first.saved.single()
            assertNull(first.originals.single())
            // Its own id, though recorded in the same millisecond as the verse's point.
            assertTrue(saved.id != own.id)
            assertEquals(5, saved.last)
            assertEquals(own, HifzRepository.points.value.pointOf(verse(4)))
            val again = HifzRepository.recordPassageWithUndo(app, verse(3), 5, now = 2_000L)
            assertEquals(saved.id, again.saved.single().id)
            assertEquals(2, again.saved.single().slips)
            assertNotNull(again.originals.single())
            // Undone, the passage is as it was before it was forgotten again.
            assertTrue(HifzRepository.restoreIfUnchanged(app, again.saved, again.originals))
            assertEquals(1, HifzRepository.points.value.passageOf(verse(3), 5)?.slips)
        } finally {
            HifzRepository.points.value.filter { it.verse.surah == 67 }.forEach { HifzRepository.delete(app, it.id) }
        }
    }
}
