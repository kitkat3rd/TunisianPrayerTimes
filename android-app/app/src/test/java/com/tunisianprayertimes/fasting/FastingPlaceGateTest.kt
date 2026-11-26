package com.tunisianprayertimes.fasting

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.FastingVerdict
import com.tunisianprayertimes.FirstRunPlaceChoice
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.place.PrayerPlace
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FastingPlaceGateTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val now = Instant.parse("2026-11-24T09:00:00Z")

    @Before fun browseSuggestedTunis() {
        PrefsManager.initializeFirstRun(context)
        PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Browse(PrayerPlace.Inm(615), null))
    }

    @Test fun enablingAndSuhoorNotificationActionsRequireAConfirmedPlace() {
        assertFalse(FastingReminder.setEnabled(context, true))
        assertFalse(FastingReminder.isEnabled(context))
        val eve = FastingReminder.Eve(
            fastDay = LocalDate.parse("2026-11-25"), remindAt = now,
            fajr = now.plusSeconds(12 * 3600L), verdict = FastingVerdict(null), paused = false,
        )
        assertFalse(FastingReminder.setSuhoor(context, eve, now))
        assertFalse(context.getSharedPreferences("fasting_reminder", Context.MODE_PRIVATE).contains("suhoor_fast_day"))
    }

    @Test fun restoredReminderIsHeldUntilTheSuggestionIsConfirmed() {
        context.getSharedPreferences("fasting_reminder", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        fun hasReminder() = alarms.scheduledAlarms.any { shadowOf(it.operation).savedIntent.data?.scheme == "fasting" }

        FastingReminder.refresh(context, now)
        assertTrue(FastingReminder.isEnabled(context))
        assertFalse(hasReminder())
        PrefsManager.setPlaceProvisional(context, false)
        FastingReminder.refresh(context, now)
        assertTrue(hasReminder())
    }
}
