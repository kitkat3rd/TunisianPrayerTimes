package com.tunisianprayertimes.remembrance

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.FirstRunPlaceChoice
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.place.PrayerPlace
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class LifeReminderPlaceGateTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val now = Instant.parse("2026-12-01T05:00:00Z")

    private fun browse() {
        PrefsManager.initializeFirstRun(app)
        PrefsManager.completeFirstRun(app, FirstRunPlaceChoice.Browse(PrayerPlace.Inm(615), null))
    }

    private fun restoredReminder() {
        app.getSharedPreferences("death_remembrance", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true).putString("frequency", "daily").commit()
    }

    private fun hasDaylightAlarm() = shadowOf(app.getSystemService(AlarmManager::class.java))
        .scheduledAlarms.any { shadowOf(it.operation).savedIntent.data?.scheme == "remembrance" }

    @Test fun unconfirmedCityAllowsReadingAndTurningOffButCannotStartDaylightReminders() {
        browse()
        assertNotNull(DeathReminder.remembranceFor(DeathReminder.today(app, now)))
        assertFalse(DeathReminder.setEnabled(app, true))
        assertFalse(DeathReminder.isEnabled(app))
        restoredReminder()
        assertTrue(DeathReminder.setEnabled(app, false))
        assertFalse(DeathReminder.isEnabled(app))
    }

    @Test fun restoredChoiceWaitsForPlaceConfirmationBeforePreparingTheDaylightAlarm() {
        restoredReminder()
        browse()
        DeathReminder.refresh(app, now)
        assertTrue(DeathReminder.isEnabled(app))
        assertNull(DeathReminder.next(app, now))
        assertFalse(hasDaylightAlarm())
        assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())

        PrefsManager.setPlaceProvisional(app, false)
        DeathReminder.refresh(app, now)
        assertTrue(DeathReminder.isEnabled(app))
        assertNotNull(DeathReminder.next(app, now))
        assertTrue(hasDaylightAlarm())
    }

    @Test fun completedLegacyUsersRetainTheirExistingDaylightSchedule() {
        PrefsManager.markFirstLaunchDone(app)
        PrefsManager.setPlaceProvisional(app, true)
        PrefsManager.initializeFirstRun(app)
        restoredReminder()
        DeathReminder.refresh(app, now)
        assertTrue(hasDaylightAlarm())
    }
}
