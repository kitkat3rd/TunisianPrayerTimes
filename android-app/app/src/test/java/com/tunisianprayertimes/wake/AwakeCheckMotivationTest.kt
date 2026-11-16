package com.tunisianprayertimes.wake

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import com.tunisianprayertimes.RingtonePreset
import com.tunisianprayertimes.WakeMainAlarmMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AwakeCheckMotivationTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var context: Context
    private var controller: ServiceController<AwakeCheckService>? = null
    private val eventId = wakeMainEventId("awake-motivation")
    private val occurrence = 1_793_541_600_000L

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        listOf("wake_motivation_history", "awake_check_occurrences", "prayer_silence_prefs",
            "wake_alarm_scheduler", "nap_prefs").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        PrefsManager.setEnabled(context, false)
        val notifications = context.getSystemService(NotificationManager::class.java)
        Shadows.shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    @After
    fun cleanup() {
        controller?.destroy()
        Dispatchers.resetMain()
    }

    private fun start(trigger: Long = occurrence): WakeMotivation {
        val intent = AwakeCheckService.intent(
            context, eventId, null, null,
            autoSilenceOverrideAllowed = true, scheduledTriggerAtMillis = trigger, occurrenceAtMillis = occurrence,
        )
        controller = Robolectric.buildService(AwakeCheckService::class.java, intent).create()
        requireNotNull(controller).get().onStartCommand(intent, 0, 1)
        return runBlocking { withTimeout(10_000L) { AwakeCheckService.motivation.filterNotNull().first() } }
    }

    private fun assertNotification(message: WakeMotivation, prompt: Int) {
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals(context.getString(prompt), notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val expanded = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(expanded.contains(message.text))
        assertTrue(expanded.contains(message.source))
        message.note?.let { assertTrue(expanded.contains(it)) }
        assertEquals(context.getString(R.string.awake_check_confirm), notification.actions.single().title.toString())
    }

    @Test
    fun followUpShowsANewMessageAndKeepsItThroughVibrationAndRinging() = runBlocking {
        val ring = resolveWakeMotivation(context, WakeTriggerPayload(
            eventId = eventId, prayer = Prayer.FAJR, effectivePrayer = Prayer.FAJR,
            mainAlarmMode = WakeMainAlarmMode.FIXED_TIME,
            hour = 15, minute = 0, ringtone = RingtonePreset.DEFAULT_ALARM, vibrationOnly = false,
            wakeUpCheckEnabled = false, progressiveVolume = false, snoreTrackingEnabled = false,
            awakeCheckEnabled = true, isSubAlarm = false,
            triggerAtMillis = occurrence, occurrenceAtMillis = occurrence,
        ))
        val message = start()
        assertNotEquals(ring, message)
        assertNotification(message, R.string.awake_check_prompt)

        dispatcher.scheduler.advanceTimeBy(60_000L)
        dispatcher.scheduler.runCurrent()
        assertNotification(message, R.string.awake_check_no_response)
        dispatcher.scheduler.advanceTimeBy(AlarmAudioController.STRONG_VIBRATION_MILLIS)
        dispatcher.scheduler.runCurrent()
        assertNotification(message, R.string.awake_check_ringing)
        assertEquals(message, AwakeCheckService.motivation.value)
    }

    @Test
    fun reopeningTheSameCheckKeepsItsMessageButANewCheckRotates() {
        val first = start()
        requireNotNull(controller).destroy()
        controller = null
        assertNull(AwakeCheckService.motivation.value)
        assertFalse(AwakeCheckService.isRunning.value)

        assertEquals(first, start())
        requireNotNull(controller).destroy()
        controller = null
        assertNotEquals(first, start(occurrence + 180_000L))
    }
}
