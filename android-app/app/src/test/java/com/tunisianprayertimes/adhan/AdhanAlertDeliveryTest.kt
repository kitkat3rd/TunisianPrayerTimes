package com.tunisianprayertimes.adhan

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhan.AdhanScheduler.putOccurrence
import com.tunisianprayertimes.mosque.MosqueSilenceSettings
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AdhanAlertDeliveryTest {
    private lateinit var context: Context
    private lateinit var audio: AudioManager
    private lateinit var notifications: NotificationManager
    private lateinit var controller: ServiceController<AdhanPlaybackService>
    private val players = mutableListOf<MediaPlayer>()
    private var startId = 0

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("mosque_silence_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("adhan_settings", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("enabled_FAJR", true).commit()
        PrefsManager.setEnabled(context, false)
        audio = context.getSystemService(AudioManager::class.java)
        notifications = context.getSystemService(NotificationManager::class.java)
        notifications.cancelAll()
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AdhanPlaybackService.ADHAN_STREAM, 5, 0)
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(4 * 60_000, 0) }
        ShadowMediaPlayer.setCreateListener { player, _ -> players += player }
        controller = Robolectric.buildService(AdhanPlaybackService::class.java).create()
    }

    @After
    fun cleanup() {
        controller.destroy()
    }

    @Test
    fun aSilentPhoneStillGetsTheFullScreenPrayerWithoutAudio() {
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        deliver(occurrence())
        awaitDecision()

        assertQuietPage(R.string.adhan_quiet_silent_phone)
        assertNotNull(notification().fullScreenIntent)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audio.ringerMode)
    }

    @Test
    fun mosqueSilenceStillGetsTheFullScreenPrayerWithoutLiftingSilence() {
        MosqueSilenceSettings.saveMode(context, AutomaticSilenceMode.TIME_AND_MOSQUE)
        PrefsManager.markAutoSilenceActive(context, AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_ALL)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        deliver(occurrence())
        awaitDecision()

        assertQuietPage(R.string.adhan_quiet_prayer_silence)
        assertNotNull(notification().fullScreenIntent)
        assertTrue(PrefsManager.isAutoSilenceActive(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, notifications.currentInterruptionFilter)
    }

    @Test
    fun zeroVolumeStillGetsTheFullScreenPrayerWithoutStartingAPlayer() {
        audio.setStreamVolume(AdhanPlaybackService.ADHAN_STREAM, 0, 0)
        deliver(occurrence())
        awaitDecision()

        assertQuietPage(R.string.adhan_quiet_zero_volume)
        assertNotNull(notification().fullScreenIntent)
        assertEquals(0, audio.getStreamVolume(AdhanPlaybackService.ADHAN_STREAM))
    }

    @Test
    fun aQuietCountdownReachesTheQuietPrayerPageInsteadOfClosing() {
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        val occurrence = occurrence(15_000L)
        AdhanPlaybackService.warnBeforeAdhan(context, occurrence)
        val warning = shadowOf(context as Application).nextStartedService
        assertNotNull(warning)
        controller.get().onStartCommand(warning, 0, ++startId)

        assertTrue(AdhanPlaybackService.alert.value!!.countingDown)
        assertNotNull(notification().fullScreenIntent)
        assertSoundlessChannel()

        shadowOf(Looper.getMainLooper()).idleFor(15_000L, TimeUnit.MILLISECONDS)
        deliver(occurrence)
        awaitDecision()

        assertQuietPage(R.string.adhan_quiet_silent_phone)
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun theQuietPageTimesOutAndLeavesASilentTrayNotice() {
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        deliver(occurrence())
        awaitDecision()
        shadowOf(Looper.getMainLooper()).idleFor(AdhanPlaybackService.QUIET_ALERT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)

        assertNull(AdhanPlaybackService.alert.value)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        val remaining = shadowOf(notifications).allNotifications.single()
        assertNull(remaining.fullScreenIntent)
        assertEquals(context.getString(R.string.adhan_quiet_silent_phone), remaining.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test
    fun closingTheQuietPageDismissesItAndCancelsItsTimeout() {
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        deliver(occurrence())
        awaitDecision()
        AdhanPlaybackService.cancelFromPage(context)
        controller.get().onStartCommand(shadowOf(context as Application).nextStartedService, 0, ++startId)
        shadowOf(Looper.getMainLooper()).idleFor(AdhanPlaybackService.QUIET_ALERT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)

        assertNull(AdhanPlaybackService.alert.value)
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
        assertTrue(players.isEmpty())
    }

    @Test
    fun aDuplicateDeliveryKeepsTheQuietReasonAndCloseButton() {
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        val occurrence = occurrence()
        deliver(occurrence)
        awaitDecision()
        deliver(occurrence)

        assertQuietPage(R.string.adhan_quiet_silent_phone)
        assertEquals(context.getString(R.string.adhan_close), notification().actions.single().title)
        assertEquals(context.getString(R.string.adhan_quiet_silent_phone), notification().extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test
    fun cancelledAndDisabledAdhansDoNotReopenTheFullScreenPage() {
        val occurrence = occurrence()
        AdhanSettings.skip(context, occurrence.prayer, occurrence.prayerDay)
        AdhanPlaybackService.warnBeforeAdhan(context, occurrence)
        assertNull(shadowOf(context as Application).nextStartedService)
        deliver(occurrence)
        awaitDecision()
        assertNull(AdhanPlaybackService.alert.value)
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())

        context.getSharedPreferences("adhan_settings", Context.MODE_PRIVATE).edit().clear().commit()
        AdhanPlaybackService.warnBeforeAdhan(context, occurrence)
        assertNull(shadowOf(context as Application).nextStartedService)
        assertTrue(players.isEmpty())
    }

    @Test
    fun aLateDeliveryKeepsItsTrayNoticeWithoutOpeningThePage() {
        deliver(occurrence(-AdhanDecision.LATE_SOUND_MILLIS - 1))
        awaitDecision()

        assertNull(AdhanPlaybackService.alert.value)
        assertNull(shadowOf(notifications).allNotifications.single().fullScreenIntent)
        assertTrue(players.isEmpty())
    }

    @Test
    fun anAudibleAdhanOpensThePageEvenWhenTheAdvanceWarningIsOff() {
        context.getSharedPreferences("adhan_settings", Context.MODE_PRIVATE).edit().putBoolean("warn_before", false).commit()
        deliver(occurrence())
        awaitDecision()

        assertTrue(AdhanPlaybackService.alert.value!!.sounding)
        assertNotNull(notification().fullScreenIntent)
        assertTrue(players.single().isPlaying)
    }

    private fun occurrence(offset: Long = 0) = AdhanScheduler.Occurrence(Prayer.FAJR, LocalDate.now(), System.currentTimeMillis() + offset)

    private fun deliver(occurrence: AdhanScheduler.Occurrence) {
        controller.get().onStartCommand(
            Intent(context, AdhanPlaybackService::class.java).setAction(AdhanPlaybackService.ACTION_PLAY).putOccurrence(occurrence),
            0, ++startId,
        )
    }

    private fun awaitDecision() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val alert = AdhanPlaybackService.alert.value
            if (alert?.countingDown == false || shadowOf(controller.get()).isStoppedBySelf) return
            Thread.sleep(5)
        }
        fail("The adhan decision did not complete")
    }

    private fun notification() = shadowOf(controller.get()).lastForegroundNotification

    private fun assertQuietPage(reason: Int) {
        val alert = AdhanPlaybackService.alert.value!!
        assertFalse(alert.sounding)
        assertFalse(alert.countingDown)
        assertEquals(reason, alert.quietReasonResId)
        assertTrue(players.isEmpty())
        assertSoundlessChannel()
    }

    private fun assertSoundlessChannel() {
        val channel = notifications.getNotificationChannel(notification().channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
    }
}
