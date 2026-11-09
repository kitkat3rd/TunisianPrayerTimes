package com.tunisianprayertimes.wake

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.OffsetDirection
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.PrayerWakeStore
import com.tunisianprayertimes.PrayerWakeSubAlarm
import com.tunisianprayertimes.RingtonePreset
import com.tunisianprayertimes.WakePlaybackOptions
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WakePlaybackSettingsTest {
    private fun payload() = WakeTriggerPayload(
        eventId = wakeMainEventId("settings"), prayer = Prayer.FAJR, effectivePrayer = Prayer.FAJR,
        hour = 5, minute = 0, ringtone = RingtonePreset.DEFAULT_ALARM, vibrationOnly = false,
        wakeUpCheckEnabled = true, progressiveVolume = true, snoreTrackingEnabled = false,
        awakeCheckEnabled = false, isSubAlarm = false,
    )

    @Test
    fun olderSavedAlarmsReceiveTheNewDefaultsIncludingExtraAlarms() {
        val alarm = decodePrayerWakeStore("""
            {"alarms":[{"id":"old","prayer":"FAJR","playback":{},
              "subAlarms":[{"id":"extra","minutesOffset":5,"direction":"AFTER"}]}]}
        """.trimIndent()).alarms.single()
        for (playback in listOf(alarm.playback, alarm.subAlarms.single().playback)) {
            assertEquals(30, playback.initialVibrationSeconds)
            assertEquals(60, playback.volumeStepIntervalSeconds)
        }
    }

    @Test
    fun savingPreservesSeparateMainAndExtraAlarmTiming() {
        val store = PrayerWakeStore(listOf(PrayerWakeConfig(
            id = "custom", prayer = Prayer.FAJR,
            playback = WakePlaybackOptions(initialVibrationSeconds = 0, volumeStepIntervalSeconds = 30),
            subAlarms = listOf(PrayerWakeSubAlarm(
                id = "extra", minutesOffset = 5, direction = OffsetDirection.AFTER,
                playback = WakePlaybackOptions(initialVibrationSeconds = 120, volumeStepIntervalSeconds = 300),
            )),
        )))
        assertEquals(store, decodePrayerWakeStore(encodePrayerWakeStore(store)))
    }

    @Test
    fun scheduledIntentAndRestoredQueueRetainCustomTiming() {
        val main = payload().copy(initialVibrationSeconds = 15, volumeStepIntervalSeconds = 120)
        val extra = payload().copy(eventId = "extra", initialVibrationSeconds = 0, volumeStepIntervalSeconds = 30)
        val queue = WakeAlarmQueue()
        queue.handleIncoming(WakePlaybackService.playbackIntent(ApplicationProvider.getApplicationContext(), main).toWakeTriggerPayload()!!)
        queue.handleIncoming(extra)
        val bundle = Bundle()
        queue.saveToBundle(bundle)
        val restored = WakeAlarmQueue()
        restored.restoreFromBundle(bundle)
        assertEquals(main, restored.current)
        restored.advance()
        assertEquals(extra, restored.current)
    }

    @Test
    fun olderScheduledIntentsReceiveTheNewDefaults() {
        val intent = Intent().putExtras(payload().toBundle()).apply {
            removeExtra(EXTRA_INITIAL_VIBRATION_SECONDS)
            removeExtra(EXTRA_VOLUME_STEP_INTERVAL_SECONDS)
        }
        val restored = intent.toWakeTriggerPayload()!!
        assertEquals(30, restored.initialVibrationSeconds)
        assertEquals(60, restored.volumeStepIntervalSeconds)
    }

    @Test
    fun invalidTimingIsBoundedWhenLoadingSettingsOrIntents() {
        val store = PrayerWakeStore(listOf(PrayerWakeConfig(
            id = "invalid", prayer = Prayer.FAJR,
            playback = WakePlaybackOptions(initialVibrationSeconds = -1, volumeStepIntervalSeconds = 0),
        )))
        val saved = decodePrayerWakeStore(encodePrayerWakeStore(store)).alarms.single().playback
        assertEquals(0, saved.initialVibrationSeconds)
        assertEquals(1, saved.volumeStepIntervalSeconds)
        val restored = Intent().putExtras(payload().toBundle()).apply {
            putExtra(EXTRA_INITIAL_VIBRATION_SECONDS, Int.MAX_VALUE)
            putExtra(EXTRA_VOLUME_STEP_INTERVAL_SECONDS, Int.MAX_VALUE)
        }.toWakeTriggerPayload()!!
        assertEquals(300, restored.initialVibrationSeconds)
        assertEquals(300, restored.volumeStepIntervalSeconds)
    }
}
