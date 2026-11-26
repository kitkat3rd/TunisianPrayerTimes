package com.tunisianprayertimes

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.wake.PrayerWakeRepository
import com.tunisianprayertimes.wake.WakeAlarmReceiver
import com.tunisianprayertimes.wake.toWakeTriggerPayload
import com.tunisianprayertimes.wake.wakeMainEventId
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WakeShortcutActionsTest {
    private lateinit var context: Context
    private lateinit var repository: PrayerWakeRepository

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        repository = PrayerWakeRepository(context)
        repository.clearAllWakeConfigs()
        PrefsManager.setEnabled(context, false)
        PrefsManager.markFirstLaunchDone(context)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun cleanup() = runBlocking { repository.clearAllWakeConfigs() }

    private val today get() = LocalDate.now(PrayerTimesRepository.place(context).zone)

    @Test fun provisionalPlaceHoldsBeforeAdhanAndLeavesNapAvailable() = runBlocking {
        PrefsManager.setPlaceProvisional(context, true)
        val now = Instant.now()
        assertEquals(context.getString(R.string.prayer_feature_place_required), WakeShortcutActions.perform(context, WakeShortcutActions.BEFORE_ADHAN, now))
        assertTrue(repository.getCurrentStore().alarms.isEmpty())
        WakeShortcutActions.perform(context, WakeShortcutActions.NAP, now)
        assertEquals(WakeMainAlarmMode.FROM_NOW, repository.getCurrentStore().alarms.single().mainAlarm.mode)
    }

    private fun scheduledRing(config: PrayerWakeConfig) =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.single {
            val intent = shadowOf(it.operation).savedIntent
            intent.component?.className == WakeAlarmReceiver::class.java.name && intent.action == wakeMainEventId(config.id)
        }

    @Test fun afterIshaSetsOneAlarmForTomorrowBeforeFajr() = runBlocking {
        val afterIsha = PrayerTimesRepository.prayerOccurrences(context, today).single { it.prayer == Prayer.ISHA }.at.plusSeconds(1)
        val fajr = PrayerTimesRepository.prayerOccurrences(context, today.plusDays(1)).single { it.prayer == Prayer.FAJR }
        repeat(2) { WakeShortcutActions.perform(context, WakeShortcutActions.BEFORE_ADHAN, afterIsha) }
        val config = repository.getCurrentStore().alarms.single()
        assertEquals(Prayer.FAJR, config.prayer)
        assertEquals(WakeRepeatMode.ONCE, config.repeatMode)
        assertEquals(-10, config.mainAlarm.prayerOffset.minutes)
        assertEquals(fajr.at.minusSeconds(600).toEpochMilli(), scheduledRing(config).triggerAtTime)
    }

    @Test fun fridayUsesTheResolvedJumuaTimeAndName() = runBlocking {
        val friday = today.plusDays(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
        val jumua = PrayerTimesRepository.prayerOccurrences(context, friday).single { it.prayer == Prayer.JOMOAA }
        val message = WakeShortcutActions.perform(context, WakeShortcutActions.BEFORE_ADHAN, jumua.at.minusSeconds(900))
        val config = repository.getCurrentStore().alarms.single()
        val ring = scheduledRing(config)
        assertTrue(message.contains(context.getString(R.string.prayer_jomoaa)))
        assertEquals(jumua.at.minusSeconds(600).toEpochMilli(), ring.triggerAtTime)
        assertEquals(Prayer.JOMOAA, shadowOf(ring.operation).savedIntent.toWakeTriggerPayload()?.effectivePrayer)
    }

    @Test fun atOrAfterTheTenMinuteMarkDoesNotScheduleForAnotherPrayer() = runBlocking {
        val asr = PrayerTimesRepository.prayerOccurrences(context, today).single { it.prayer == Prayer.ASR }
        for (remainingSeconds in listOf(600L, 120L)) {
            val message = WakeShortcutActions.perform(context, WakeShortcutActions.BEFORE_ADHAN, asr.at.minusSeconds(remainingSeconds))
            assertEquals(context.getString(R.string.shortcut_wake_too_late, context.getString(R.string.prayer_asr)), message)
        }
        assertTrue(repository.getCurrentStore().alarms.isEmpty())
    }

    @Test fun napResetsFromTheTapAndCoexistsWithAnExistingAlarm() = runBlocking {
        val regular = PrayerWakeConfig(id = "my-fajr", prayer = Prayer.FAJR)
        repository.saveWakeConfig(regular)
        val tap = Instant.now()
        WakeShortcutActions.perform(context, WakeShortcutActions.NAP, tap)
        val secondTap = tap.plusSeconds(70)
        WakeShortcutActions.perform(context, WakeShortcutActions.NAP, secondTap)
        val alarms = repository.getCurrentStore().alarms
        assertEquals(regular, alarms.single { it.id == regular.id })
        val nap = alarms.single { it.id != regular.id }
        assertEquals(WakeRepeatMode.ONCE, nap.repeatMode)
        assertEquals(WakeMainAlarmMode.FROM_NOW, nap.mainAlarm.mode)
        assertFalse(nap.playback.awakeCheckEnabled)
        assertEquals(secondTap.plusSeconds(20 * 60L).toEpochMilli(), scheduledRing(nap).triggerAtTime)
    }

    @Test fun missingPermissionOpensExactAlarmSettingsWithoutSavingAnAlarm() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val intent = Intent(context, WakeShortcutActivity::class.java).setAction(WakeShortcutActions.NAP)
        val activity = Robolectric.buildActivity(WakeShortcutActivity::class.java, intent).create().get()
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, shadowOf(activity).nextStartedActivity.action)
        assertTrue(repository.getCurrentStore().alarms.isEmpty())
    }

    @Test fun aNapEditedIntoARecurringAlarmSurvivesTheNextShortcutTap() = runBlocking {
        val tap = Instant.now()
        WakeShortcutActions.perform(context, WakeShortcutActions.NAP, tap)
        val edited = repository.getCurrentStore().alarms.single().copy(
            repeatMode = WakeRepeatMode.RECURRING,
            mainAlarm = WakeMainAlarmConfig(mode = WakeMainAlarmMode.FIXED_TIME, fixedTime = ClockTime(7, 30)),
        )
        repository.saveWakeConfig(edited)
        WakeShortcutActions.perform(context, WakeShortcutActions.NAP, tap.plusSeconds(60))
        val alarms = repository.getCurrentStore().alarms
        assertEquals(edited, alarms.single { it.id == edited.id })
        assertEquals(WakeRepeatMode.ONCE, alarms.single { it.id != edited.id }.repeatMode)
    }
}
