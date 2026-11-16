package com.tunisianprayertimes.quran.audio

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.tunisianprayertimes.quran.QuranVerseReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class QuranDrillPauseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val verse = QuranVerseReference(2, 1)
    private val passage = QuranRepeatRange(verse, verse, 1)
    private val drill = QuranPlaylist("تدريب", List(3) { passage }, List(3) { "2:1" }, requestId = "current")

    @Before fun startWithoutASavedPlace() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        context.getSharedPreferences(QuranPlaybackService.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        QuranAudioController.publish(QuranPlaybackState())
    }

    private fun field(name: String) = QuranPlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun pauseAt(service: QuranPlaybackService, index: Int) {
        field("playlist").set(service, drill)
        field("playlistIndex").setInt(service, index)
        field("playlistRound").setInt(service, 1)
        field("repeat").set(service, passage)
        field("gapUntil").setLong(service, QuranAudioController.elapsedNow() + 30_000L)
        field("playWhenReady").setBoolean(service, true)
        // No real media is needed: passage seeks remain pending, but the service owns the actual progression.
        field("recording").set(service, QuranSurahRecording(2, "quran/audio/test.mp3", 10_000L,
            listOf(QuranAyahTiming(1, 0L, 10_000L))))
        QuranAudioController.publish(QuranPlaybackState(
            surah = 2, ayah = 1, playing = true, positionMs = 9_999L, durationMs = 10_000L,
            playlist = drill, playlistIndex = index, playlistRound = 1, repeat = passage,
            gapAfter = 1, gapRemainingMs = 30_000L, gapTotalMs = 30_000L,
        ))
    }

    private fun command(index: Int, list: QuranPlaylist = drill) = Intent(context, QuranPlaybackService::class.java)
        .setAction(QuranPlaybackService.ACTION_SKIP_DRILL_PAUSE)
        .putExtra(QuranPlaybackService.EXTRA_PLAYLIST, list.encode())
        .putExtra(QuranPlaybackService.EXTRA_PLAYLIST_INDEX, index)
        .putExtra(QuranPlaybackService.EXTRA_PLAYLIST_ROUND, 1)

    @Test fun skippingThePauseAdvancesExactlyOneRepetition() {
        val lifecycle = Robolectric.buildService(QuranPlaybackService::class.java).create()
        try {
            val service = lifecycle.get()
            pauseAt(service, 0)
            service.onStartCommand(command(0), 0, 1)
            val current = QuranAudioController.state.value
            assertEquals(1, current.playlistIndex)
            assertEquals(drill, current.playlist)
            assertNull(current.gapAfter)
            assertEquals(0L, current.gapRemainingMs)
            assertEquals(0L, field("pendingSeekMs").get(service))
            service.onStartCommand(command(0), 0, 2)
            assertEquals(1, QuranAudioController.state.value.playlistIndex)
        } finally { lifecycle.destroy() }
    }

    @Test fun aLateTapCannotSkipTheFollowingReaderTurnOrADifferentDrill() {
        val lifecycle = Robolectric.buildService(QuranPlaybackService::class.java).create()
        try {
            val service = lifecycle.get()
            pauseAt(service, 1)
            service.onStartCommand(command(0), 0, 1)
            service.onStartCommand(command(1, drill.copy(requestId = "earlier")), 0, 2)
            assertEquals(1, QuranAudioController.state.value.playlistIndex)
            assertEquals(1, QuranAudioController.state.value.gapAfter)
        } finally { lifecycle.destroy() }
    }

    @Test fun finishingTheFinalPausePublishesNormalDrillCompletion() {
        val lifecycle = Robolectric.buildService(QuranPlaybackService::class.java).create()
        try {
            val service = lifecycle.get()
            pauseAt(service, 2)
            service.onStartCommand(command(2), 0, 1)
            val current = QuranAudioController.state.value
            assertEquals(drill, current.finishedPlaylist)
            assertNull(current.playlist)
            assertNull(current.repeat)
            assertNull(current.gapAfter)
            assertFalse(current.playing)
        } finally { lifecycle.destroy() }
    }

    @Test fun aPausedDrillCannotBeAdvancedByALateCommand() {
        val lifecycle = Robolectric.buildService(QuranPlaybackService::class.java).create()
        try {
            val service = lifecycle.get()
            pauseAt(service, 0)
            service.onStartCommand(Intent().setAction(QuranPlaybackService.ACTION_PAUSE), 0, 1)
            service.onStartCommand(command(0), 0, 2)
            assertEquals(0, QuranAudioController.state.value.playlistIndex)
            assertFalse(QuranAudioController.state.value.playing)
        } finally { lifecycle.destroy() }
    }
}
