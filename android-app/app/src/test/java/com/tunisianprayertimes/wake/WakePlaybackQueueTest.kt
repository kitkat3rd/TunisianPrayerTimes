package com.tunisianprayertimes.wake

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.RingtonePreset
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
import org.robolectric.shadows.ShadowMediaPlayer
import java.util.concurrent.TimeUnit

/** A plain extra must keep ringing when the main alarm with a challenge is delivered. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WakePlaybackQueueTest {
    private lateinit var context: Context
    private lateinit var controller: ServiceController<WakePlaybackService>
    private val players = mutableListOf<MediaPlayer>()
    private val queue get() = WakeAlarmQueueHolder.queue
    private var startId = 0

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        queue.clear()
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(10 * 60_000, 0) }
        ShadowMediaPlayer.setCreateListener { player, _ -> players += player }
        controller = Robolectric.buildService(WakePlaybackService::class.java).create()
    }

    @After
    fun cleanup() {
        controller.destroy()
        queue.clear()
    }

    @Test
    fun mainAlarmDoesNotCutOffUnchallengedExtraAndStartsOnlyAfterStop() {
        val extra = extraPayload()
        val main = mainPayload()
        deliver(extra)
        advance(2 * 60_000L)
        val firstPlayer = players.single()
        val firstNotification = shadowOf(controller.get()).lastForegroundNotificationId

        deliver(main)
        advance(3 * 60_000L)

        assertEquals(extra, queue.current)
        assertEquals(listOf(main.eventId), queue.pendingEventIds())
        assertEquals(firstNotification, shadowOf(controller.get()).lastForegroundNotificationId)
        assertEquals(1, players.size)
        assertTrue(firstPlayer.isPlaying)
        assertTrue(firstPlayer.isLooping)

        stopFromNotification(extra)

        assertEquals(main, queue.current)
        assertEquals(0, queue.pendingCount)
        assertEquals(2, players.size)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(firstPlayer).state)
        assertTrue(players.last().isPlaying)
        assertEquals(main.eventId.hashCode() and 0x7fffffff, shadowOf(controller.get()).lastForegroundNotificationId)

        // The alert activity advances the queue after the main alarm's challenge is solved.
        assertFalse(queue.handleDismiss(main.eventId))
        refreshPlayback()
        assertNull(queue.current)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(players.last()).state)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun mainDeliveredDuringExtrasInitialVibrationDoesNotRestartItsDelay() {
        val extra = extraPayload().copy(initialVibrationSeconds = 30)
        val main = mainPayload()
        deliver(extra)
        advance(20_000L)
        deliver(main)
        advance(10_000L)

        assertEquals(extra, queue.current)
        assertEquals(listOf(main.eventId), queue.pendingEventIds())
        assertEquals(1, players.size)
        assertTrue(players.single().isPlaying)
    }

    @Test
    fun duplicateDeliveriesDoNotRestartSoundOrDuplicatePendingAlarms() {
        val extra = extraPayload()
        val main = mainPayload()
        deliver(extra)
        deliver(extra)
        deliver(main)
        deliver(main)

        assertEquals(extra, queue.current)
        assertEquals(listOf(main.eventId), queue.pendingEventIds())
        assertEquals(1, players.size)
        assertTrue(players.single().isPlaying)
    }

    @Test
    fun laterAlarmWaitsBehindAnUnchallengedAlarmPromotedFromTheQueue() {
        val extra = extraPayload()
        val main = mainPayload().copy(wakeUpCheckEnabled = false)
        val later = main.copy(eventId = wakeMainEventId("other"))
        deliver(extra)
        deliver(main)
        stopFromNotification(extra)
        val promotedPlayer = players.last()
        deliver(later)

        assertEquals(main, queue.current)
        assertEquals(listOf(later.eventId), queue.pendingEventIds())
        assertEquals(2, players.size)
        assertTrue(promotedPlayer.isPlaying)

        stopFromNotification(main)
        assertEquals(later, queue.current)
        assertEquals(3, players.size)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(promotedPlayer).state)
        assertTrue(players.last().isPlaying)
    }

    private fun deliver(payload: WakeTriggerPayload) {
        controller.get().onStartCommand(WakePlaybackService.playbackIntent(context, payload), 0, ++startId)
    }

    private fun stopFromNotification(payload: WakeTriggerPayload) {
        WakeActionReceiver().onReceive(
            context,
            Intent().setAction(ACTION_STOP_WAKE_ALARM).putExtras(payload.toBundle()),
        )
        val refresh = shadowOf(context as Application).nextStartedService
        assertEquals(WakePlaybackService.ACTION_REFRESH_FOR_CURRENT, refresh.action)
        controller.get().onStartCommand(refresh, 0, ++startId)
    }

    private fun refreshPlayback() {
        controller.get().onStartCommand(
            Intent(context, WakePlaybackService::class.java).setAction(WakePlaybackService.ACTION_REFRESH_FOR_CURRENT),
            0,
            ++startId,
        )
    }

    private fun advance(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS)
    }

    private fun mainPayload() = WakeTriggerPayload(
        eventId = wakeMainEventId("fajr"),
        prayer = Prayer.FAJR,
        effectivePrayer = Prayer.FAJR,
        hour = 4,
        minute = 2,
        ringtone = RingtonePreset.ADHAN_ISLAM_SOBHI,
        vibrationOnly = false,
        wakeUpCheckEnabled = true,
        progressiveVolume = false,
        initialVibrationSeconds = 0,
        snoreTrackingEnabled = false,
        awakeCheckEnabled = false,
        isSubAlarm = false,
    )

    private fun extraPayload() = mainPayload().copy(
        eventId = wakeSubAlarmEventId("fajr", "before"),
        ringtone = RingtonePreset.ADHAN_OMAR_HISHAM_AL_ARABI,
        wakeUpCheckEnabled = false,
        isSubAlarm = true,
        subAlarmId = "before",
    )
}
