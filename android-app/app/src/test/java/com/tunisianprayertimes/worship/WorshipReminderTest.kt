package com.tunisianprayertimes.worship

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.MainTabNavigation
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.PrayerTimesRepository
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WorshipReminderTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    private val duha = WorshipReminder.DUHA
    private val qiyam = WorshipReminder.QIYAM
    private val date = LocalDate.of(2026, 11, 28)

    @Before fun setup() {
        PrefsManager.markFirstLaunchDone(app)
        PrefsManager.setDelegationId(app, 615)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
    }

    private fun window(date: LocalDate): VoluntaryPrayerWindow {
        val place = PrayerTimesRepository.place(app)
        return requireNotNull(duhaWindow(date, place.zone, PrayerTimesRepository.prayerDay(app, date, place),
            PrayerTimesRepository.solarNoon(app, date, place)))
    }

    private fun alarms(reminder: WorshipReminder = duha) = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms
        .filter { shadowOf(it.operation).savedIntent.let { intent ->
            intent.component?.className == WorshipReminderReceiver::class.java.name && intent.action == reminder.channelId
        } }
        .map { Instant.ofEpochMilli(it.triggerAtTime) }

    @Test fun dailyDeliveryOpensWorshipOnceAndRearmsTheFollowingDaysWindow() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val today = window(date)
        val due = requireNotNull(WorshipTiming.MIDDLE.at(today))
        assertNull(duha.timing(app))
        assertTrue(duha.setTiming(app, WorshipTiming.MIDDLE, today.start.minusSeconds(5)))
        assertEquals(2, alarms().size)
        assertTrue(alarms().all { it >= due && it < due.plusMillis(1) })
        duha.refresh(app, due.minusMillis(1))
        assertTrue(notifications.activeNotifications.isEmpty())
        duha.refresh(app, due)
        val posted = notifications.activeNotifications.single()
        assertEquals(duha.channelId, posted.notification.channelId)
        val open = shadowOf(posted.notification.contentIntent).savedIntent
        assertEquals(MainTabNavigation.DESTINATION_WORSHIP, open.getStringExtra(MainTabNavigation.EXTRA_DESTINATION))
        notifications.cancel(posted.id)
        duha.refresh(app, due.plusSeconds(1))
        assertTrue(notifications.activeNotifications.isEmpty())
        val next = requireNotNull(WorshipTiming.MIDDLE.at(window(date.plusDays(1))))
        assertTrue(alarms().all { it >= next && it < next.plusMillis(1) })
    }

    @Test fun changingAnElapsedChoiceStartsTomorrowAndDisablingClearsAllDelivery() {
        val today = window(date)
        assertTrue(duha.setTiming(app, WorshipTiming.MIDDLE, today.start.minusSeconds(1)))
        assertTrue(duha.setTiming(app, WorshipTiming.START, today.start.plusSeconds(30)))
        duha.refresh(app, requireNotNull(WorshipTiming.MIDDLE.at(today)))
        assertEquals(window(date.plusDays(1)).start, alarms().single())
        assertTrue(notifications.activeNotifications.isEmpty())
        assertTrue(duha.setTiming(app, null, today.end))
        assertTrue(alarms().isEmpty())
        duha.refresh(app, window(date.plusDays(1)).start)
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test fun blockedPermissionCanRecoverOnlyInsideTheWindow() {
        val today = window(date)
        duha.setTiming(app, WorshipTiming.START, today.start.minusSeconds(10))
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        duha.refresh(app, today.start)
        assertTrue(notifications.activeNotifications.isEmpty())
        assertTrue(alarms().single() > today.start)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        duha.refresh(app, today.end)
        assertTrue(notifications.activeNotifications.isEmpty())
        val tomorrow = window(date.plusDays(1))
        duha.refresh(app, tomorrow.start.plusSeconds(60))
        assertEquals(1, notifications.activeNotifications.size)
        duha.setTiming(app, null, tomorrow.start.plusSeconds(90))
        assertTrue(notifications.activeNotifications.isEmpty())
    }

    @Test fun activeSilenceDefersDeliveryUntilItsEnd() {
        val due = window(date).start
        val release = due.plusSeconds(240)
        duha.setTiming(app, WorshipTiming.START, due.minusSeconds(60))
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        PrefsManager.markManualSilenceActive(app, AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_ALL)
        PrefsManager.setManualSilenceEndsAtMillis(app, release.toEpochMilli())
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        duha.refresh(app, due)
        assertTrue(notifications.activeNotifications.isEmpty())
        assertEquals(release, alarms().single())
        PrefsManager.clearManualSilenceState(app)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        duha.refresh(app, release)
        assertEquals(1, notifications.activeNotifications.size)
    }

    private fun night(date: LocalDate) = requireNotNull(nightWindow(date,
        PrayerTimesRepository.prayerDay(app, date), PrayerTimesRepository.prayerDay(app, date.plusDays(1))))

    @Test fun qiyamAtTheLastThirdSurvivesMidnightAndKeepsTheDuhaAlarm() {
        val tonight = night(date)
        val due = tonight.lastThirdPrayerStart
        val before = tonight.isha.minusSeconds(60)
        assertTrue(duha.setTiming(app, WorshipTiming.START, before))
        val dayAlarms = alarms()
        assertTrue(qiyam.setTiming(app, WorshipTiming.LAST_THIRD, before))
        assertEquals(dayAlarms, alarms())
        assertEquals(due.atZone(PrayerTimesRepository.place(app).zone).toLocalDate(), date.plusDays(1))
        assertTrue(alarms(qiyam).single() in due..due.plusMillis(1))
        qiyam.refresh(app, due.minusSeconds(1))
        assertTrue(notifications.activeNotifications.isEmpty())
        qiyam.refresh(app, due)
        val posted = notifications.activeNotifications.single()
        assertEquals(qiyam.channelId, posted.notification.channelId)
        notifications.cancel(posted.id)
        qiyam.refresh(app, due.plusSeconds(15))
        assertTrue(notifications.activeNotifications.isEmpty())
        val next = night(date.plusDays(1)).lastThirdPrayerStart
        assertTrue(alarms(qiyam).single() in next..next.plusMillis(1))
        qiyam.setTiming(app, null, due.plusSeconds(30))
        assertTrue(alarms(qiyam).isEmpty())
        assertEquals(dayAlarms, alarms())
    }

    @Test fun afterIshaSettingIsIndependentOfLastThirdAndExpiresAtFajr() {
        val tonight = night(date)
        val before = tonight.isha.minusSeconds(60)
        qiyam.setTiming(app, WorshipTiming.AFTER_ISHA, before)
        assertEquals(tonight.isha.plusSeconds(1800), alarms(qiyam).single())
        qiyam.refresh(app, tonight.fajr)
        assertTrue(notifications.activeNotifications.isEmpty())
        val next = night(date.plusDays(1))
        assertEquals(next.isha.plusSeconds(1800), alarms(qiyam).single())
        qiyam.setTiming(app, WorshipTiming.LAST_THIRD, next.lastThirdPrayerStart.plusSeconds(60))
        assertTrue(notifications.activeNotifications.isEmpty())
        assertTrue(alarms(qiyam).single() > next.fajr)
        val shortNight = VoluntaryPrayerWindow(before, before.plusSeconds(1800), false)
        assertNull(WorshipTiming.AFTER_ISHA.at(shortNight))
    }
}
