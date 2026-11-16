package com.tunisianprayertimes.ui

import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.hifz.HifzKind
import com.tunisianprayertimes.quran.hifz.HifzMistake
import com.tunisianprayertimes.quran.hifz.HifzPoint
import com.tunisianprayertimes.quran.hifz.epochDay
import org.junit.Assert.assertEquals
import org.junit.Test

class HifzListenTest {
    private val now = 1_800_000_000_000L
    private val today = epochDay(now)

    private fun point(ayah: Int, kind: HifzKind = HifzKind.Word, fresh: Int = 0, slipAgoMs: Long = 3 * 24 * 3_600_000L, due: Long = today + 5) =
        HifzPoint(id = ayah.toLong(), verse = QuranVerseReference(2, ayah), mistakes = listOf(HifzMistake(kind, null)),
            dueDay = due, createdAt = now - slipAgoMs, lastSlipAt = now - slipAgoMs, fresh = fresh)

    @Test
    fun aQuestionWaitingNowIsAskedBeforeItsAnswerIsHeard_everyOtherPointIsHeard() {
        val freshAsked = point(10, fresh = 2, slipAgoMs = 2 * 3_600_000L, due = today)
        val freshWaitingItsGap = point(11, fresh = 2, slipAgoMs = 10 * 60_000L, due = today)
        val checkToday = point(12, due = today)
        val checkLater = point(13)
        val points = listOf(freshAsked, freshWaitingItsGap, checkToday, checkLater)
        val due = setOf(freshAsked.id, checkToday.id)
        // A fresh point between its questions is heard: its next question then waits its while after the hearing.
        assertEquals(listOf(freshWaitingItsGap, checkLater), points.listenable(points, today, now, due))
    }

    @Test
    fun aPointWhoseRecitationWouldAnswerAWaitingQuestionWaitsWithIt() {
        // A forgotten opening is heard with the verse before it: that verse's own question waits now.
        val before = point(29, due = today)
        val opening = point(30, kind = HifzKind.Start)
        val points = listOf(before, opening)
        assertEquals(emptyList<HifzPoint>(), points.listenable(points, today, now, setOf(before.id)))
        // Once that question is no longer waiting (answered, or put off by a look), both are heard.
        assertEquals(points, points.listenable(points, today, now, emptySet()))
    }
}
