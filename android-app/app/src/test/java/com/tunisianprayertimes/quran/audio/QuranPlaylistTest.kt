package com.tunisianprayertimes.quran.audio

import com.tunisianprayertimes.quran.QuranVerseReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuranPlaylistTest {
    private val verse = QuranVerseReference(2, 67)
    private val passage = QuranRepeatRange(verse, verse, times = 1)
    private val drill = QuranPlaylist("Drill", listOf(passage), listOf("2:67"), rounds = 3, answer = passage)

    @Test
    fun playbackRequestIdentitySurvivesTheServiceAndSavedDraftCodec() {
        val requested = drill.copy(requestId = "e3341772-24b9-4b48-8e6d-bb7a96aad50f")
        val startedByService = QuranPlaylist.decode(requested.encode())
        val restoredDraft = QuranPlaylist.decode(requested.encode())
        assertEquals(requested, startedByService)
        assertEquals(restoredDraft, startedByService)
    }

    @Test
    fun anEarlierIdenticalDrillCannotCompleteANewerRequest() {
        val earlier = drill.copy(requestId = "first")
        val requested = drill.copy(requestId = "second")
        assertNotEquals(QuranPlaylist.decode(earlier.encode()), QuranPlaylist.decode(requested.encode()))
        assertEquals(earlier.items, requested.items)
    }

    @Test
    fun oldPlaylistsDecodeWithoutRequestIdentityAndKeepTheirOldShape() {
        val old = """{"title":"Drill","rounds":3,"gap":1.0,"speed":0.0,"items":[[2,67,2,67,"2:67"]],"answer":[2,67,2,67]}"""
        val decoded = QuranPlaylist.decode(old)
        assertEquals(drill, decoded)
        assertNull(decoded?.requestId)
        assertFalse(JSONObject(decoded!!.encode()).has("requestId"))
        assertEquals(drill, QuranPlaylist.decode(JSONObject(old).put("requestId", JSONObject.NULL).toString()))
    }

    private fun readerTurn(list: QuranPlaylist = drill, index: Int = 0, round: Int = 1) = QuranPlaybackState(
        surah = verse.surah, ayah = verse.ayah, playing = true,
        playlist = list, playlistIndex = index, playlistRound = round,
        gapAfter = verse.ayah, gapRemainingMs = 20_000L, gapTotalMs = 30_000L,
    )

    @Test
    fun finishingTheReaderTurnMovesToTheNextPassageOrRound() {
        val passages = drill.copy(items = List(3) { passage }, labels = List(3) { "2:67" })
        assertEquals(QuranPlaylistStep.Item(1, 1), readerTurn(passages).drillPauseStep())
        assertEquals(QuranPlaylistStep.Item(0, 2), readerTurn(passages, index = 2).drillPauseStep())
        assertEquals(QuranPlaylistStep.Item(0, 2), readerTurn().drillPauseStep())
    }

    @Test
    fun finishingTheLastReaderTurnCompletesTheDrillInsteadOfRestartingIt() {
        assertEquals(QuranPlaylistStep.Finished, readerTurn(round = 3).drillPauseStep())
    }

    private val other = QuranRepeatRange(QuranVerseReference(2, 68), QuranVerseReference(2, 68), times = 1)
    private val points = QuranPlaylist("Points", listOf(passage, other), listOf("2:67", "2:68"), gapFactor = 0f, itemTimes = 3)

    @Test
    fun eachPassageIsHeardItsTimesInARowAndNextGoesToTheNextPassage() {
        assertEquals(QuranPlaylistStep.Item(0, 1, pass = 2), points.afterPass(0, 1, 1))
        assertEquals(QuranPlaylistStep.Item(0, 1, pass = 3), points.afterPass(0, 1, 2))
        assertEquals(QuranPlaylistStep.Item(1, 1), points.afterPass(0, 1, 3))
        assertEquals(QuranPlaylistStep.Finished, points.afterPass(1, 1, 3))
        // «التالي» leaves the passage whatever recitation of it is under way.
        assertEquals(QuranPlaylistStep.Item(1, 1), points.after(0, 1))
    }

    @Test
    fun anEndlessListStartsAgainAfterItsLastPassageUntilStopped() {
        val endless = points.copy(rounds = 0)
        assertTrue(endless.isValid)
        assertEquals(QuranPlaylistStep.Item(0, 2), endless.afterPass(1, 1, 3))
        assertEquals(QuranPlaylistStep.Item(0, 41), endless.after(1, 40))
        assertEquals(40, endless.roundWithin(40))
        assertEquals(1, points.roundWithin(40))
        assertEquals(1, endless.roundWithin(0))
    }

    @Test
    fun theTimesSurviveTheCodecAndOneTimeKeepsTheOldShape() {
        assertEquals(points, QuranPlaylist.decode(points.encode()))
        assertEquals(points.copy(rounds = 0), QuranPlaylist.decode(points.copy(rounds = 0).encode()))
        assertFalse(JSONObject(drill.encode()).has("times"))
        assertFalse(points.copy(itemTimes = 0).isValid)
    }

    @Test
    fun theReaderTurnAfterARecitationLeadsToTheNextRecitationOfTheSamePassage() {
        val state = readerTurn(points.copy(gapFactor = 1f)).copy(repeat = passage, repeatRound = 2)
        assertEquals(QuranPlaylistStep.Item(0, 1, pass = 3), state.drillPauseStep())
        assertEquals("المرة 2 من 3", state.playlistPass())
        assertNull(readerTurn().playlistPass())
    }

    @Test
    fun onlyAnActiveDrillPauseCanBeSkipped() {
        val active = readerTurn()
        val unavailable = listOf(
            active.copy(playlist = null),
            active.copy(gapAfter = null),
            active.copy(playing = false),
            active.copy(loading = true),
            active.copy(notDownloaded = true),
            active.copy(error = "Unavailable"),
            active.copy(interruption = QuranInterruption.Alarm),
            active.copy(playlist = drill.copy(gapFactor = 0f)),
            active.copy(playlistIndex = 2),
            active.copy(playlistRound = 0),
            active.copy(playlistRound = 4),
            active.copy(surah = 3),
            active.copy(gapAfter = 66),
        )
        unavailable.forEach { assertNull(it.toString(), it.drillPauseStep()) }
    }
}
