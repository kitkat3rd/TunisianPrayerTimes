package com.tunisianprayertimes

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowAudioManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ManualSilenceSchedulerTest {
    @Implements(AudioManager::class)
    class DeniedSilentRinger : ShadowAudioManager() {
        @Implementation
        override fun setRingerMode(mode: Int) {
            if (mode == AudioManager.RINGER_MODE_SILENT) throw SecurityException("Ringer change denied")
            super.setRingerMode(mode)
        }
    }

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var shadowAlarmManager: ShadowAlarmManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowAlarmManager = Shadows.shadowOf(alarmManager)
        Shadows.shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)

        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()

        PrefsManager.setEnabled(context, false)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    @Test
    fun schedule_storesEndTimeAndAlarm() {
        val endsAtMillis = ManualSilenceScheduler.schedule(context, 20)

        assertTrue(endsAtMillis > System.currentTimeMillis())
        assertEquals(endsAtMillis, PrefsManager.getManualSilenceEndsAtMillis(context))
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
    }

    @Test
    @Config(sdk = [33], application = android.app.Application::class)
    fun failedOwnedRuleActivationKeepsExistingAlarmAndSettings() {
        // DND access is granted, but the user has removed the app's manual Android mode.
        com.tunisianprayertimes.mosque.MosqueSilenceSettings.enroll(context)
        PrefsManager.setManualSilenceMode(context, ManualSilenceMode.UNTIL_PRAYER)
        PrefsManager.markCallReceivedDuringSilence(context)
        val oldDeadline = ManualSilenceScheduler.schedule(context, 45)

        assertNull(ManualSilenceScheduler.start(context, ManualSilenceMode.DURATION, durationMinutes = 30))

        assertEquals(ManualSilenceMode.UNTIL_PRAYER, PrefsManager.getManualSilenceMode(context))
        assertEquals(oldDeadline, PrefsManager.getManualSilenceEndsAtMillis(context))
        assertTrue(shadowAlarmManager.scheduledAlarms.isNotEmpty())
        assertTrue(shadowAlarmManager.scheduledAlarms.all { it.triggerAtTime == oldDeadline })
        assertTrue(PrefsManager.consumeCallReceivedDuringSilence(context))
    }

    @Test
    @Config(sdk = [28], shadows = [DeniedSilentRinger::class], application = android.app.Application::class)
    fun ringerRefusalRestoresFilterAndPreservesPreviousDeadlineAndOwnership() {
        val previousDeadline = ManualSilenceScheduler.schedule(context, 15)
        PrefsManager.setManualSilenceMode(context, ManualSilenceMode.UNTIL_PRAYER)
        PrefsManager.markCallReceivedDuringSilence(context)

        assertNull(ManualSilenceScheduler.start(context, ManualSilenceMode.DURATION, durationMinutes = 30))

        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, notificationManager.currentInterruptionFilter)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertFalse(PrefsManager.isManualSilenceActive(context))
        assertEquals(previousDeadline, PrefsManager.getManualSilenceEndsAtMillis(context))
        assertEquals(ManualSilenceMode.UNTIL_PRAYER, PrefsManager.getManualSilenceMode(context))
        assertTrue(PrefsManager.consumeCallReceivedDuringSilence(context))
    }

    @Test
    fun lateBroadcastCannotEndAnExtendedOrIndefiniteSessionOrConsumeItsMissedCall() {
        ManualSilenceScheduler.start(context, ManualSilenceMode.DURATION, durationMinutes = 10)
        val oldBroadcast = Shadows.shadowOf(shadowAlarmManager.scheduledAlarms.single().operation).savedIntent
        val newDeadline = ManualSilenceScheduler.start(context, ManualSilenceMode.DURATION, durationMinutes = 60)
        PrefsManager.markCallReceivedDuringSilence(context)
        SilenceReceiver().onReceive(context, oldBroadcast)
        assertTrue(PrefsManager.isManualSilenceActive(context))
        assertEquals(requireNotNull(newDeadline), PrefsManager.getManualSilenceEndsAtMillis(context))
        assertTrue(PrefsManager.consumeCallReceivedDuringSilence(context))

        ManualSilenceScheduler.start(context, ManualSilenceMode.UNTIL_STOPPED)
        PrefsManager.markCallReceivedDuringSilence(context)
        SilenceReceiver().onReceive(context, oldBroadcast)
        assertTrue(PrefsManager.isManualSilenceActive(context))
        assertEquals(-1L, PrefsManager.getManualSilenceEndsAtMillis(context))
        assertTrue(PrefsManager.consumeCallReceivedDuringSilence(context))
    }

    @Test
    @Config(sdk = [33], application = android.app.Application::class)
    fun revokingExactAccessLeavesAnIndependentFallbackAtTheSameDeadline() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val end = requireNotNull(ManualSilenceScheduler.start(context, ManualSilenceMode.DURATION, durationMinutes = 20))
        val scheduled = shadowAlarmManager.scheduledAlarms
        assertEquals(2, scheduled.size)
        assertTrue(scheduled.all { it.triggerAtMs == end })
        val exact = scheduled.single { it.windowLengthMs == 0L }

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        alarmManager.cancel(requireNotNull(exact.operation)) // Android removes exact alarms when access is revoked.
        assertEquals(end, shadowAlarmManager.scheduledAlarms.single().triggerAtMs)
        ManualSilenceScheduler.restoreTimer(context)
        assertEquals(end, PrefsManager.getManualSilenceEndsAtMillis(context))
        assertEquals(end, shadowAlarmManager.scheduledAlarms.single().triggerAtMs)

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ManualSilenceScheduler.restoreTimer(context)
        assertEquals(2, shadowAlarmManager.scheduledAlarms.size)
        assertTrue(shadowAlarmManager.scheduledAlarms.all { it.triggerAtMs == end })
        ManualSilenceScheduler.cancel(context)
        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
    }

    @Test
    fun cancel_clearsStoredEndTime() {
        ManualSilenceScheduler.schedule(context, 20)

        ManualSilenceScheduler.cancel(context)

        assertEquals(-1L, PrefsManager.getManualSilenceEndsAtMillis(context))
    }

    @Test
    fun manualTimerAlarm_restoresPreviousState() {
        SilenceModeController.setManualSilent(context)
        ManualSilenceScheduler.scheduleAt(context, System.currentTimeMillis() - 1)

        val receiver = SilenceReceiver()
        receiver.onReceive(context, android.content.Intent(ManualSilenceScheduler.ACTION_MANUAL_UNSILENCE))

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertFalse(PrefsManager.isManualSilenceActive(context))
        assertEquals(-1L, PrefsManager.getManualSilenceEndsAtMillis(context))
    }

    @Test
    fun manualTimerAlarm_duringActiveAutoWindow_handsBackToAutoSilence() {
        val now = Calendar.getInstance()

        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelayMode(context, Prayer.FAJR, DelayMode.FIXED_TIME)
        PrefsManager.setDelayFixedTime(
            context,
            Prayer.FAJR,
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE)
        )
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.DURATION)
        PrefsManager.setAfterMinutes(context, Prayer.FAJR, 5)

        SilenceScheduler.scheduleAll(context)
        assertTrue(PrefsManager.isAutoSilenceActive(context))
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        SilenceModeController.setManualSilent(context)
        ManualSilenceScheduler.scheduleAt(context, System.currentTimeMillis() - 1)
        assertTrue(PrefsManager.isManualSilenceActive(context))
        assertFalse(PrefsManager.isAutoSilenceActive(context))

        val receiver = SilenceReceiver()
        receiver.onReceive(context, android.content.Intent(ManualSilenceScheduler.ACTION_MANUAL_UNSILENCE))

        assertFalse(PrefsManager.isManualSilenceActive(context))
        assertTrue(PrefsManager.isAutoSilenceActive(context))
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, notificationManager.currentInterruptionFilter)
    }
}
