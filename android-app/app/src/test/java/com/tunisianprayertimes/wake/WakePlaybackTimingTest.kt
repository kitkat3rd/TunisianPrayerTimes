package com.tunisianprayertimes.wake

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.VibratorManager
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.RingtonePreset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WakePlaybackTimingTest {
    private val scheduler = TestCoroutineScheduler()
    private val scope = TestScope(StandardTestDispatcher(scheduler))
    private val players = mutableListOf<MediaPlayer>()
    private lateinit var audio: AlarmAudioController
    private lateinit var audioManager: AudioManager
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(AudioManager::class.java)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 2, 0)
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(60_000, 0) }
        ShadowMediaPlayer.setCreateListener { player, _ -> players += player }
        audio = AlarmAudioController(context, scope)
    }

    @After
    fun cleanup() {
        audio.stop()
        scope.cancel()
    }

    private fun payload() = WakeTriggerPayload(
        eventId = wakeMainEventId("timing"),
        prayer = Prayer.FAJR,
        effectivePrayer = Prayer.FAJR,
        hour = 5,
        minute = 0,
        ringtone = RingtonePreset.DEFAULT_ALARM,
        vibrationOnly = false,
        wakeUpCheckEnabled = false,
        progressiveVolume = true,
        snoreTrackingEnabled = false,
        awakeCheckEnabled = false,
        isSubAlarm = false,
    )

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    private fun isVibrating(): Boolean = shadowOf(
        context.getSystemService(VibratorManager::class.java).defaultVibrator,
    ).isVibrating

    private fun assertVolume(expected: Float) {
        val player = players.single()
        assertTrue(player.isPlaying)
        assertEquals(expected, shadowOf(player).leftVolume, 0.0001f)
        assertEquals(expected, shadowOf(player).rightVolume, 0.0001f)
    }

    @Test
    fun defaultAlarmVibratesForThirtySecondsBeforeStartingQuietSound() {
        audio.startWakeAlarm(payload())
        assertTrue(isVibrating())
        assertEquals(2, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
        advance(29_999L)
        assertTrue(players.isEmpty())
        advance(1L)
        assertFalse(isVibrating())
        assertVolume(0.05f)
        assertEquals(
            audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            audioManager.getStreamVolume(AudioManager.STREAM_ALARM),
        )
    }

    @Test
    fun defaultVolumeStaysConstantBetweenOneMinuteStepsAndStopsAtMaximum() {
        audio.startWakeAlarm(payload())
        advance(30_000L)
        advance(59_999L)
        assertVolume(0.05f)
        advance(1L)
        assertVolume(0.1f)
        advance(59_999L)
        assertVolume(0.1f)
        advance(1L)
        assertVolume(0.2f)
        advance(8 * 60_000L)
        assertVolume(1f)
        advance(5 * 60_000L)
        assertVolume(1f)
    }

    @Test
    fun configuredVibrationAndStepIntervalsAreIndependent() {
        audio.startWakeAlarm(payload().copy(initialVibrationSeconds = 15, volumeStepIntervalSeconds = 30))
        advance(14_999L)
        assertTrue(players.isEmpty())
        advance(1L)
        assertVolume(0.05f)
        advance(29_999L)
        assertVolume(0.05f)
        advance(1L)
        assertVolume(0.1f)
        advance(30_000L)
        assertVolume(0.2f)
    }

    @Test
    fun vibrationCanBeDisabledAndNonProgressiveSoundStartsAtFullVolume() {
        audio.startWakeAlarm(payload().copy(initialVibrationSeconds = 0, progressiveVolume = false))
        assertFalse(isVibrating())
        assertVolume(1f)
        advance(120_000L)
        assertVolume(1f)
    }

    @Test
    fun stoppingDuringVibrationNeverStartsDelayedSound() {
        audio.startWakeAlarm(payload())
        advance(20_000L)
        audio.stop()
        advance(120_000L)
        assertFalse(isVibrating())
        assertTrue(players.isEmpty())
        assertEquals(2, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    @Test
    fun stoppingSoundRestoresSystemVolumeAndCancelsFutureIncreases() {
        audio.startWakeAlarm(payload())
        advance(30_000L)
        audio.stop()
        advance(120_000L)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.single()).state)
        assertEquals(0.05f, shadowOf(players.single()).leftVolume, 0.0001f)
        assertEquals(2, audioManager.getStreamVolume(AudioManager.STREAM_ALARM))
    }

    @Test
    fun advancingTheQueueCancelsThePreviousAlarmsDelayedSound() {
        audio.startWakeAlarm(payload())
        advance(20_000L)
        audio.startWakeAlarm(payload().copy(eventId = "next", initialVibrationSeconds = 15))
        advance(10_000L)
        assertTrue(players.isEmpty())
        advance(5_000L)
        assertVolume(0.05f)
    }

    @Test
    fun vibrationOnlyNeverEscalatesToSound() {
        audio.startWakeAlarm(payload().copy(vibrationOnly = true))
        advance(30 * 60_000L)
        assertTrue(isVibrating())
        assertTrue(players.isEmpty())
    }

    @Test
    fun alarmAllowedDuringPrayerSilenceUsesItsConfiguredTiming() {
        audio.startWakeAlarm(payload().copy(
            useAutoSilenceConflictPlayback = true,
            initialVibrationSeconds = 15,
            volumeStepIntervalSeconds = 30,
        ))
        advance(15_000L)
        assertFalse(isVibrating())
        assertVolume(0.05f)
        advance(30_000L)
        assertVolume(0.1f)
    }
}
