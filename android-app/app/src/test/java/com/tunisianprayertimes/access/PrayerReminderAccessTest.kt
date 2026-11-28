package com.tunisianprayertimes.access

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhan.AdhanPlaybackService
import com.tunisianprayertimes.adhkar.DhikrRepository
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.hifz.HifzKind
import com.tunisianprayertimes.quran.hifz.HifzRepository
import com.tunisianprayertimes.quran.hifz.HifzSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PrayerReminderAccessTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun initializeFirstRun() {
        PrefsManager.initializeFirstRun(app)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test fun disabledFeaturesAndAnEmptyReviewNotebookDoNotPromptOnFirstUse() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_PHONE_STATE)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertTrue(PrayerReminderAccess.issues(app).isEmpty())
    }

    @Test fun timelyAdhanNeedsExactAccessWhileAdvanceWarningsOnlyApplyWhenSelected() {
        val manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val channel = AdhanPlaybackService.CHANNEL_SOON
        manager.createNotificationChannel(NotificationChannel(channel, "advance", NotificationManager.IMPORTANCE_NONE))
        val settings = app.getSharedPreferences("adhan_settings", Context.MODE_PRIVATE)
        settings.edit().putBoolean("enabled_FAJR", true).putBoolean("warn_before", false).commit()
        // A restored switch is inactive while a fresh device awaits place confirmation.
        assertTrue(PrayerReminderAccess.issues(app).isEmpty())

        PrefsManager.markFirstLaunchDone(app)
        assertEquals(listOf(R.string.adhan_exact_alarms_off), PrayerReminderAccess.issues(app).map { it.message })
        settings.edit().putBoolean("warn_before", true).commit()
        val issues = PrayerReminderAccess.issues(app)
        assertEquals(setOf(R.string.adhan_exact_alarms_off, R.string.access_adhan_warning_exact),
            issues.filter { it.action == AccessAction.EXACT_ALARMS }.map { it.message }.toSet())
        assertEquals(channel, issues.single { it.action == AccessAction.NOTIFICATIONS }.channelId)
    }

    @Test fun anAppWideNotificationBlockIsReportedOnceForTheEnabledFeature() {
        PrefsManager.markFirstLaunchDone(app)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.getSharedPreferences("adhan_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled_FAJR", true).putBoolean("warn_before", false).commit()
        val issues = PrayerReminderAccess.issues(app)
        assertEquals(listOf(R.string.adhan_settings_title), issues.map { it.feature })
        assertFalse(issues.any { it.channelId != null })
    }

    @Test fun deliberateCallFeedbackChoiceStillAppliesToFutureManualSilence() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        PrefsManager.setCallEndVibrationEnabled(app, true)
        assertTrue(PrayerReminderAccess.issues(app).any { it.feature == R.string.call_end_vibration_title })
        PrefsManager.setCallEndVibrationEnabled(app, false)
        assertFalse(PrayerReminderAccess.issues(app).any { it.feature == R.string.call_end_vibration_title })
    }

    @Test fun unreadableReminderStatePreservesOtherWarningsAndOriginalData() {
        PrefsManager.markFirstLaunchDone(app)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.getSharedPreferences("adhan_settings", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled_FAJR", true).putBoolean("warn_before", false).commit()
        val adhkar = app.getSharedPreferences("adhkar_reminders", Context.MODE_PRIVATE)
        adhkar.edit().putString("state_v2", "{bad").commit()
        DhikrRepository.clearMemoryCache()
        val notebook = File(app.filesDir, "hifz/hifz.json")
        notebook.parentFile!!.mkdirs()
        notebook.writeText("{bad")
        val hifz = app.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE)
        hifz.edit().putString("reminder", "incompatible").commit()

        val issues = PrayerReminderAccess.issues(app)
        assertEquals(listOf(R.string.adhan_settings_title), issues.map { it.feature })
        assertEquals("{bad", adhkar.getString("state_v2", null))
        assertEquals("{bad", notebook.readText())
        assertFalse(hifz.contains("strategy_version"))
    }

    @Test fun revisionTracksReviewEligibilityWithoutRefreshingOnEachNotebookEdit() {
        HifzRepository.reload(app)
        val revision = ReminderAccessChanges.revision
        val initial = revision.value
        val verse = QuranVerseReference(2, 1)
        val point = HifzRepository.record(app, verse, HifzKind.Word)
        assertEquals(initial + 1, revision.value)
        HifzRepository.record(app, verse, HifzKind.Word)
        assertEquals(initial + 1, revision.value)
        HifzSettings.write(app, HifzSettings(reminder = false))
        assertEquals(initial + 2, revision.value)
        HifzSettings.write(app, HifzSettings(reminder = false, dailyGoal = 6))
        assertEquals(initial + 2, revision.value)
        HifzSettings.write(app, HifzSettings(reminder = true))
        assertEquals(initial + 3, revision.value)
        HifzRepository.delete(app, point.id)
        assertEquals(initial + 4, revision.value)
    }
}
