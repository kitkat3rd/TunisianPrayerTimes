package com.tunisianprayertimes.quran.audio

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class QuranVolumeReminderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audio by lazy { context.getSystemService(AudioManager::class.java) }
    private val notifications by lazy { context.getSystemService(NotificationManager::class.java) }
    private lateinit var lifecycle: ServiceController<QuranPlaybackService>
    private lateinit var service: QuranPlaybackService

    @Before fun setup() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        context.getSharedPreferences(QuranPlaybackService.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        QuranAudioController.publish(QuranPlaybackState())
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        ShadowToast.reset()
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(60_000, 0) }
        lifecycle = Robolectric.buildService(QuranPlaybackService::class.java).create()
        service = lifecycle.get()
        val media = MediaPlayer().apply {
            setDataSource("recitation.mp3")
            prepare()
        }
        setField("player", media)
        setField("prepared", true)
        setField("playWhenReady", true)
        QuranAudioController.publish(QuranPlaybackState(surah = 1, durationMs = 60_000L))
    }

    @After fun cleanup() {
        lifecycle.destroy()
    }

    @Test fun zeroMediaVolumeShowsReminderWithoutChangingVolumeOrBlockingPlayback() {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

        startPrepared()

        assertEquals(context.getString(R.string.quran_media_muted_reminder), ShadowToast.getTextOfLatestToast())
        assertTrue(QuranAudioController.state.value.playing)
        assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, notifications.currentInterruptionFilter)
    }

    @Test fun explicitlyMutedMediaAlsoShowsReminder() {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))

        startPrepared()

        assertEquals(context.getString(R.string.quran_media_muted_reminder), ShadowToast.getTextOfLatestToast())
        assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    @Test fun audibleMediaDoesNotShowReminderEvenWithSilentRinger() {
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT

        startPrepared()

        assertNull(ShadowToast.getTextOfLatestToast())
        assertTrue(QuranAudioController.state.value.playing)
    }

    @Test fun appControlledSilenceKeepsItsExistingExplanationInsteadOfVolumeReminder() {
        PrefsManager.markManualSilenceActive(context, AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_ALL)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

        startPrepared()

        assertNull(ShadowToast.getTextOfLatestToast())
        assertFalse(QuranAudioController.state.value.playing)
        assertEquals(QuranInterruption.Silence, QuranAudioController.state.value.interruption)
        assertEquals("أسكتنا الهاتف كما طلبت", QuranAudioController.state.value.silence?.title)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, notifications.currentInterruptionFilter)
    }

    @Test fun externalDoNotDisturbDoesNotSuggestRaisingVolumeEither() {
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

        startPrepared()

        assertNull(ShadowToast.getTextOfLatestToast())
        assertEquals(QuranInterruption.Silence, QuranAudioController.state.value.interruption)
    }

    @Test fun repeatedStartsDoNotRepeatReminderButExplicitResumeChecksAgain() {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)

        startPrepared()
        startPrepared()
        assertEquals(1, ShadowToast.shownToastCount())

        service.onStartCommand(Intent().setAction(QuranPlaybackService.ACTION_PAUSE), 0, 1)
        service.onStartCommand(Intent().setAction(QuranPlaybackService.ACTION_RESUME), 0, 2)

        assertEquals(2, ShadowToast.shownToastCount())
        assertTrue(QuranAudioController.state.value.playing)
    }

    @Test fun unmutingBeforeResumeDoesNotShowAnotherReminder() {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        startPrepared()
        service.onStartCommand(Intent().setAction(QuranPlaybackService.ACTION_PAUSE), 0, 1)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)

        service.onStartCommand(Intent().setAction(QuranPlaybackService.ACTION_RESUME), 0, 2)

        assertEquals(1, ShadowToast.shownToastCount())
        assertTrue(QuranAudioController.state.value.playing)
    }

    private fun startPrepared() {
        QuranPlaybackService::class.java.getDeclaredMethod("startPrepared").apply { isAccessible = true }.invoke(service)
    }

    private fun setField(name: String, value: Any) {
        QuranPlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }
}
