package com.tunisianprayertimes.adhan

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Looper
import android.provider.Settings
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
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(RECORDING_DURATION_MILLIS, 0) }
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

        assertSilentWave(R.string.adhan_quiet_silent_phone)
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

        assertSilentWave(R.string.adhan_quiet_prayer_silence)
        assertNotNull(notification().fullScreenIntent)
        assertTrue(PrefsManager.isAutoSilenceActive(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, notifications.currentInterruptionFilter)
    }

    @Test
    fun zeroVolumeStillGetsTheFullScreenPrayerWithoutStartingAPlayer() {
        audio.setStreamVolume(AdhanPlaybackService.ADHAN_STREAM, 0, 0)
        deliver(occurrence())
        awaitDecision()

        assertSilentWave(R.string.adhan_quiet_zero_volume)
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
        // A quiet adhan's warning vibrates too (owner, 2026-11-16); Android keeps it still on a silent phone.
        val channel = notifications.getNotificationChannel(notification().channelId)
        assertNull(channel.sound)
        assertTrue(channel.shouldVibrate())

        shadowOf(Looper.getMainLooper()).idleFor(15_000L, TimeUnit.MILLISECONDS)
        deliver(occurrence)
        awaitDecision()

        assertSilentWave(R.string.adhan_quiet_silent_phone)
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test
    fun theSilentWaveRunsForTheSelectedRecordingThenLeavesASilentTrayNotice() {
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        val voice = AdhanVoice.FAJR_MARWAN_QASSAS
        AdhanSettings.setVoice(context, Prayer.FAJR, voice)
        deliver(occurrence())
        awaitDecision()
        assertSilentWave(R.string.adhan_quiet_silent_phone, voice)

        val main = shadowOf(Looper.getMainLooper())
        main.idleFor(waveDuration(voice) - 1L, TimeUnit.MILLISECONDS)
        assertSilentWave(R.string.adhan_quiet_silent_phone, voice)
        main.idleFor(1L, TimeUnit.MILLISECONDS)

        assertNull(AdhanPlaybackService.alert.value)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        val remaining = shadowOf(notifications).allNotifications.single()
        assertNull(remaining.fullScreenIntent)
        assertEquals(context.getString(R.string.adhan_quiet_silent_phone), remaining.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test
    fun silencingThePhoneDuringPlaybackKeepsTheWaveAtItsOriginalPosition() {
        deliver(occurrence())
        awaitDecision()
        val before = AdhanPlaybackService.alert.value!!
        val player = players.single()
        val main = shadowOf(Looper.getMainLooper())
        val elapsed = 45_000L
        main.idleFor(elapsed, TimeUnit.MILLISECONDS)

        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        context.sendBroadcast(Intent(AudioManager.RINGER_MODE_CHANGED_ACTION).setPackage(context.packageName))
        main.idle()

        val after = AdhanPlaybackService.alert.value!!
        assertEquals(before.voice, after.voice)
        assertEquals(before.startedAtUptimeMillis, after.startedAtUptimeMillis)
        assertFalse(after.sounding)
        assertNull(after.onVolumeChange)
        assertEquals(R.string.adhan_quiet_silent_phone, after.quietReasonResId)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)

        main.idleFor(RECORDING_DURATION_MILLIS - elapsed - 1L, TimeUnit.MILLISECONDS)
        assertEquals(after, AdhanPlaybackService.alert.value)
        main.idleFor(1L, TimeUnit.MILLISECONDS)
        assertNull(AdhanPlaybackService.alert.value)
    }

    @Test
    fun aCallKeepsTheBriefQuietReminder() {
        audio.mode = AudioManager.MODE_IN_CALL
        deliver(occurrence())
        awaitDecision()

        val alert = AdhanPlaybackService.alert.value!!
        assertEquals(R.string.adhan_quiet_in_call, alert.quietReasonResId)
        assertNull(alert.voice)
        assertTrue(players.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(AdhanPlaybackService.QUIET_ALERT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        assertNull(AdhanPlaybackService.alert.value)
    }

    @Test
    fun closingTheQuietPageDismissesItAndCancelsItsTimeout() {
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        deliver(occurrence())
        awaitDecision()
        AdhanPlaybackService.cancelFromPage(context)
        controller.get().onStartCommand(shadowOf(context as Application).nextStartedService, 0, ++startId)
        shadowOf(Looper.getMainLooper()).idleFor(waveDuration(AdhanSettings.defaultVoice(Prayer.FAJR)), TimeUnit.MILLISECONDS)

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

        assertSilentWave(R.string.adhan_quiet_silent_phone)
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

    @Test
    fun adjustingTheRecordingAndMutingWithTheKeysKeepsItsPlaceAndChosenVolume() {
        deliver(occurrence())
        awaitDecision()
        val adjust = AdhanPlaybackService.alert.value!!.onVolumeChange!!
        val player = players.single()
        val startedAt = AdhanPlaybackService.alert.value!!.startedAtUptimeMillis

        fun assertLevel(expected: Float) {
            val shown = AdhanPlaybackService.alert.value!!
            assertEquals(expected, shadowOf(player).leftVolume, 0f)
            assertEquals(expected, shadowOf(player).rightVolume, 0f)
            assertEquals(expected, shown.volume, 0f)
            assertEquals(expected == 0f, shown.muted)
            assertEquals(startedAt, shown.startedAtUptimeMillis)
            assertTrue(player.isPlaying)
            assertEquals(5, audio.getStreamVolume(AdhanPlaybackService.ADHAN_STREAM))
        }

        adjust(0.35f)
        assertLevel(0.35f)
        AdhanPlaybackService.muteFromPage(context, muted = true)
        controller.get().onStartCommand(shadowOf(context as Application).nextStartedService, 0, ++startId)
        assertLevel(0f)
        AdhanPlaybackService.muteFromPage(context, muted = false)
        controller.get().onStartCommand(shadowOf(context as Application).nextStartedService, 0, ++startId)
        assertLevel(0.35f)
        adjust(0f)
        assertLevel(0f)
        adjust(0.7f)
        assertLevel(0.7f)
    }

    @Test
    fun raisingTheSliderAfterAnOffscreenMuteRestoresTheRingStep() {
        deliver(occurrence())
        awaitDecision()
        val adjust = AdhanPlaybackService.alert.value!!.onVolumeChange!!
        adjust(0.25f)
        audio.setStreamVolume(AdhanPlaybackService.ADHAN_STREAM, 4, 0)
        context.contentResolver.notifyChange(Settings.System.CONTENT_URI, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(AdhanPlaybackService.alert.value!!.muted)

        adjust(0.6f)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(5, audio.getStreamVolume(AdhanPlaybackService.ADHAN_STREAM))
        assertEquals(0.6f, shadowOf(players.single()).leftVolume, 0f)
    }

    @Test
    fun aNewRecordingStartsAtNormalVolumeAndIgnoresThePreviousSlider() {
        val first = occurrence()
        deliver(first)
        awaitDecision()
        val previous = AdhanPlaybackService.alert.value!!.onVolumeChange!!
        previous(0.2f)

        val next = first.copy(atMillis = first.atMillis + 1_000)
        deliver(next)
        awaitDecision(next)
        previous(0f)
        assertEquals(2, players.size)
        assertEquals(1f, AdhanPlaybackService.alert.value!!.volume, 0f)
        assertTrue(players.last().isPlaying)
    }

    private fun occurrence(offset: Long = 0) = AdhanScheduler.Occurrence(Prayer.FAJR, LocalDate.now(), System.currentTimeMillis() + offset)

    private fun deliver(occurrence: AdhanScheduler.Occurrence) {
        controller.get().onStartCommand(
            Intent(context, AdhanPlaybackService::class.java).setAction(AdhanPlaybackService.ACTION_PLAY).putOccurrence(occurrence),
            0, ++startId,
        )
    }

    private fun awaitDecision(expected: AdhanScheduler.Occurrence? = null) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val alert = AdhanPlaybackService.alert.value
            if (alert?.countingDown == false && (expected == null || alert.occurrence == expected)) return
            if (shadowOf(controller.get()).isStoppedBySelf) return
            Thread.sleep(5)
        }
        fail("The adhan decision did not complete")
    }

    private fun notification() = shadowOf(controller.get()).lastForegroundNotification

    private fun waveDuration(voice: AdhanVoice): Long =
        context.resources.openRawResource(voice.levelResId).use { it.readBytes().size * 50L }

    private fun assertSilentWave(reason: Int, voice: AdhanVoice = AdhanSettings.defaultVoice(Prayer.FAJR)) {
        val alert = AdhanPlaybackService.alert.value!!
        assertFalse(alert.sounding)
        assertFalse(alert.countingDown)
        assertEquals(voice, alert.voice)
        assertEquals(reason, alert.quietReasonResId)
        assertNull(alert.onVolumeChange)
        assertTrue(players.isEmpty())
        assertSoundlessChannel()
    }

    private fun assertSoundlessChannel() {
        val channel = notifications.getNotificationChannel(notification().channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
    }

    private companion object {
        const val RECORDING_DURATION_MILLIS = 4 * 60_000
    }
}
