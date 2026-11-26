package com.tunisianprayertimes.adhkar

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.FirstRunPlaceChoice
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.place.PrayerPlace
import java.time.LocalDate
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DhikrReminderPlaceGateTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val date = LocalDate.of(2026, 11, 24)
    private val now = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val fixed = DhikrReminder(dhikrId = DhikrCatalog.SALAWAT_ID,
        start = DhikrTime(minuteOfDay = 600), end = DhikrTime(minuteOfDay = 660))
    private val relative = fixed.copy(id = "mixed", extraIntervals = listOf(
        DhikrInterval(DhikrTime(DhikrTimeKind.MAGHRIB), DhikrTime(DhikrTimeKind.ISHA))))

    @After fun clearStore() = DhikrRepository.clearMemoryCache()

    @Test fun fixedTimesAndDisabledDraftsRemainAvailableBeforeChoosingACity() {
        PrefsManager.initializeFirstRun(context)
        assertNull(DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.FAJR), date))
        assertNotNull(DhikrReminderScheduler.resolveTime(context, fixed.start, date))
        val repo = DhikrRepository(context)
        repo.save(relative.copy(enabled = false), now)
        repo.setEnabled(relative.id, true, now)
        assertFalse(repo.state.value.reminders.single { it.id == relative.id }.enabled)
        assertNull(DhikrReminderScheduler.validate(context, fixed, date))
        assertNotNull(DhikrReminderScheduler.validate(context, relative, date))
    }

    @Test fun staleEarlyDeliveryCannotRearmAHeldRuleAndConfirmationRestoresIt() {
        val repo = DhikrRepository(context)
        repo.restore(fixed)
        repo.restore(relative)
        val schedule = context.getSharedPreferences("adhkar_schedule_v2", Context.MODE_PRIVATE)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = now)
        val event = requireNotNull(schedule.getString("event:" + relative.id, null))
        PrefsManager.initializeFirstRun(context)
        PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Browse(PrayerPlace.Inm(615), null))
        DhikrReminderScheduler.receive(context, Intent(DhikrReminderScheduler.ACTION_REMIND).putExtra("event", event), now)
        assertFalse(schedule.contains("event:" + relative.id))
        assertTrue(schedule.contains("event:" + fixed.id))
        assertTrue(repo.state.value.reminders.single { it.id == relative.id }.enabled)
        PrefsManager.setPlaceProvisional(context, false)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = now)
        assertTrue(schedule.contains("event:" + relative.id))
    }
}
