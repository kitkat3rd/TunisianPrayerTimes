package com.tunisianprayertimes

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ShortcutManager
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import java.util.Calendar
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LauncherShortcutsTest {
    private lateinit var context: Context
    private lateinit var notifications: NotificationManager
    private lateinit var audio: AudioManager
    private lateinit var shortcuts: ShortcutManager

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        for (store in listOf("prayer_silence_prefs", "wake_alarm_scheduler", "silence_ownership", "mosque_silence_settings")) {
            context.getSharedPreferences(store, Context.MODE_PRIVATE).edit().clear().commit()
        }
        notifications = context.getSystemService(NotificationManager::class.java)
        audio = context.getSystemService(AudioManager::class.java)
        shortcuts = context.getSystemService(ShortcutManager::class.java)
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        PrefsManager.setEnabled(context, false)
    }

    private fun action(action: String) = SilenceShortcutActions.perform(context, action)
    private fun shortcut(id: String) = shortcuts.dynamicShortcuts.single { it.id == id }

    @Test fun menuReplacesRecordWithTwoSilenceActionsAndKeepsReview() {
        LauncherShortcuts.refresh(context)

        assertEquals(setOf("silence_toggle", "silence_30_minutes", "hifz_review"), shortcuts.dynamicShortcuts.map { it.id }.toSet())
        assertEquals(SilenceShortcutActions.SILENCE, shortcut("silence_toggle").intent?.action)
        assertEquals(MainTabNavigation.QURAN_ACTION_HIFZ_REVIEW,
            shortcut("hifz_review").intent?.getStringExtra(MainTabNavigation.EXTRA_QURAN_ACTION))
        assertEquals(MainActivity::class.java.name, shortcut("silence_toggle").activity?.className)
        assertEquals(SilenceShortcutActivity::class.java.name, shortcut("silence_toggle").intent?.component?.className)
    }

    @Test fun backgroundChangesUpdateBothLabelsAndTheExplicitAction() {
        val unsubscribe = LauncherShortcuts.observe(context)
        try {
            action(SilenceShortcutActions.SILENCE)
            ShadowLooper.idleMainLooper()
            assertEquals(context.getString(R.string.shortcut_unsilence), shortcut("silence_toggle").shortLabel.toString())
            assertEquals(SilenceShortcutActions.UNSILENCE, shortcut("silence_toggle").intent?.action)
            assertEquals(context.getString(R.string.shortcut_silence_reset_30), shortcut("silence_30_minutes").shortLabel.toString())

            action(SilenceShortcutActions.UNSILENCE)
            ShadowLooper.idleMainLooper()
            assertEquals(SilenceShortcutActions.SILENCE, shortcut("silence_toggle").intent?.action)
            assertEquals(context.getString(R.string.shortcut_silence_30), shortcut("silence_30_minutes").shortLabel.toString())
        } finally {
            unsubscribe()
        }
    }

    @Test fun resetUsesThirtyMinutesFromNowAndPreservesOriginalSoundSettings() {
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        SilenceModeController.setManualSilent(context)
        ManualSilenceScheduler.schedule(context, 90)
        val before = System.currentTimeMillis()

        assertEquals(R.string.shortcut_silence_30_confirmed, action(SilenceShortcutActions.SILENCE_30_MINUTES))

        val after = System.currentTimeMillis()
        val end = PrefsManager.getManualSilenceEndsAtMillis(context)
        assertTrue(end >= before + 30 * 60_000L)
        assertTrue(end <= after + 30 * 60_000L)
        assertEquals(ManualSilenceMode.DURATION, PrefsManager.getManualSilenceMode(context))
        assertEquals(30, PrefsManager.getManualSilenceDurationMinutes(context))
        ManualSilenceScheduler.onTimerExpired(context)
        assertFalse(PrefsManager.isManualSilenceActive(context))
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, notifications.currentInterruptionFilter)
    }

    @Test fun timedActionConvertsIndefiniteSilenceWithoutUnmutingInBetween() {
        action(SilenceShortcutActions.SILENCE)
        assertEquals(-1L, PrefsManager.getManualSilenceEndsAtMillis(context))
        action(SilenceShortcutActions.SILENCE_30_MINUTES)
        assertTrue(PrefsManager.isManualSilenceActive(context))
        assertTrue(PrefsManager.getManualSilenceEndsAtMillis(context) > System.currentTimeMillis())
        assertEquals(NotificationManager.INTERRUPTION_FILTER_NONE, notifications.currentInterruptionFilter)
    }

    @Test fun staleSilenceAndUnsilenceActionsNeverToggleTheOppositeWay() {
        action(SilenceShortcutActions.SILENCE_30_MINUTES)
        val end = PrefsManager.getManualSilenceEndsAtMillis(context)
        action(SilenceShortcutActions.SILENCE)
        assertEquals(end, PrefsManager.getManualSilenceEndsAtMillis(context))
        action(SilenceShortcutActions.UNSILENCE)
        action(SilenceShortcutActions.UNSILENCE)
        assertFalse(PrefsManager.isManualSilenceActive(context))
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
    }

    @Test fun unsilenceDismissesTheCurrentAutomaticWindowThroughAReplan() {
        val now = Calendar.getInstance()
        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelayMode(context, Prayer.FAJR, DelayMode.FIXED_TIME)
        PrefsManager.setDelayFixedTime(context, Prayer.FAJR, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.DURATION)
        PrefsManager.setAfterMinutes(context, Prayer.FAJR, 5)
        SilenceScheduler.scheduleAll(context)
        assertTrue(PrefsManager.isAutoSilenceActive(context))

        action(SilenceShortcutActions.UNSILENCE)
        SilenceScheduler.scheduleAll(context)

        assertFalse(PrefsManager.isAutoSilenceActive(context))
        assertFalse(PrefsManager.isManualSilenceActive(context))
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
    }

    @Test fun unsilenceReleasesWakeAndManualContributionsTogether() {
        WakeAlarmScheduler.activateSilenceUntilAlarm(context, "alarm-one")
        WakeAlarmScheduler.activateSilenceUntilAlarm(context, "alarm-two")
        action(SilenceShortcutActions.SILENCE_30_MINUTES)

        action(SilenceShortcutActions.UNSILENCE)

        assertFalse(WakeAlarmScheduler.isSilenceUntilAlarmActive(context))
        assertFalse(PrefsManager.isManualSilenceActive(context))
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, notifications.currentInterruptionFilter)
    }

    @Test fun unsilenceDoesNotClearAnExternalDndSession() {
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        LauncherShortcuts.refresh(context)
        assertEquals(SilenceShortcutActions.SILENCE, shortcut("silence_toggle").intent?.action)
        assertEquals(R.string.shortcut_silence_already_off, action(SilenceShortcutActions.UNSILENCE))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, notifications.currentInterruptionFilter)
        assertTrue(PrefsManager.getAutoSilenceDismissals(context).isEmpty())
    }

    @Test @Config(sdk = [35])
    fun ownedSilenceRuleCanBeResetAndStoppedFromTheLauncher() {
        assertTrue(com.tunisianprayertimes.mosque.SilenceCoordinator.enrollOrRepair(context))
        action(SilenceShortcutActions.SILENCE)
        // Robolectric does not aggregate automatic Zen rules into the global interruption filter.
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        assertTrue(PrefsManager.isManualSilenceActive(context))
        action(SilenceShortcutActions.SILENCE_30_MINUTES)
        assertEquals(ManualSilenceMode.DURATION, PrefsManager.getManualSilenceMode(context))
        assertTrue(PrefsManager.getManualSilenceEndsAtMillis(context) > System.currentTimeMillis())
        action(SilenceShortcutActions.UNSILENCE)
        assertFalse(PrefsManager.isManualSilenceActive(context))
        assertFalse(PrefsManager.isAutoSilenceActive(context))
        assertEquals(-1L, PrefsManager.getManualSilenceEndsAtMillis(context))
    }

    @Test fun missingDndPermissionLeavesSilenceAndTimerOff() {
        shadowOf(notifications).setNotificationPolicyAccessGranted(false)
        assertEquals(R.string.toast_dnd_permission, action(SilenceShortcutActions.SILENCE_30_MINUTES))
        assertFalse(PrefsManager.isManualSilenceActive(context))
        assertEquals(-1L, PrefsManager.getManualSilenceEndsAtMillis(context))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, notifications.currentInterruptionFilter)
    }
}
