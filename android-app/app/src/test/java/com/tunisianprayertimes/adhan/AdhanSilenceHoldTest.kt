package com.tunisianprayertimes.adhan

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.SilenceModeController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** The prayer silence waits while an adhan plays, and an adhan borrows the silence that began with it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AdhanSilenceHoldTest {
    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Shadows.shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    @After
    fun releaseTheHold() {
        // Off first, so the release clears the hold without reading any place's windows.
        PrefsManager.setEnabled(context, false)
        SilenceModeController.releaseAdhanHold(context)
    }

    @Test
    fun aSilenceStartingWhileTheAdhanPlaysWaits() {
        SilenceModeController.holdAutoSilenceForAdhan(context, System.currentTimeMillis() + 60_000L)

        assertTrue(SilenceModeController.enableAutoSilence(context, Prayer.DHUHR))

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, notificationManager.currentInterruptionFilter)
        assertFalse(PrefsManager.isAutoSilenceActive(context))
    }

    @Test
    fun theAdhanLiftsThePrayerSilenceAlreadyOn() {
        SilenceModeController.enableAutoSilence(context, Prayer.DHUHR)
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        assertTrue(SilenceModeController.holdAutoSilenceForAdhan(context, System.currentTimeMillis() + 60_000L))

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, notificationManager.currentInterruptionFilter)
        assertFalse(PrefsManager.isAutoSilenceActive(context))
    }

    @Test
    fun aManualSilenceIsNeverLifted() {
        SilenceModeController.setManualSilent(context)

        assertFalse(SilenceModeController.holdAutoSilenceForAdhan(context, System.currentTimeMillis() + 60_000L))

        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertTrue(PrefsManager.isManualSilenceActive(context))
    }

    @Test
    fun aHoldPastItsEndNoLongerKeepsTheSilenceAway() {
        SilenceModeController.holdAutoSilenceForAdhan(context, System.currentTimeMillis() - 1L)

        SilenceModeController.enableAutoSilence(context, Prayer.DHUHR)

        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertTrue(PrefsManager.isAutoSilenceActive(context))
    }
}
