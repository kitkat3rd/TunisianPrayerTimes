package com.tunisianprayertimes.mosque

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.AndroidAtomicFileShadow
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.SilenceReceiver
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.PresenceState
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import com.tunisianprayertimes.platform.SharedAndroid
import com.tunisianprayertimes.silence.EndResolution
import com.tunisianprayertimes.silence.SilenceWindow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AdaptiveMosqueIntegrationTest {
    private val c = ApplicationProvider.getApplicationContext<Context>()
    private val noon = Instant.parse("2026-06-01T12:00:00Z")
    private val originalClock = SilenceScheduler.clock
    @After fun resetClock() { SilenceScheduler.clock = originalClock }

    @Test fun onlyAPreciseArrivalCheckRequestsTwoSecondUpdates() {
        val plan = MosqueObservationPlan("session", 120_000, 120_000, true, PresenceState.ACQUIRING, emptyList())
        assertEquals(2_000L, plan.intervalMillis)
        assertEquals(60_000L, plan.copy(highAccuracy = false).intervalMillis)
        assertEquals(5_000L, plan.copy(state = PresenceState.EXITING).intervalMillis)
        assertEquals(5_000L, plan.copy(state = PresenceState.VISIT_CONFIRMED).intervalMillis)
    }

    @Test fun existingMosqueMethodObservesArrivalBeforePrayerWithoutTheOldOptIn() {
        assertPrePrayerObservation()
    }

    @Test fun existingMosqueMethodWithTheOldOptInHasTheSameObservationWindow() {
        c.getSharedPreferences("mosque_silence_settings", Context.MODE_PRIVATE).edit()
            .putInt("adaptive_disclosure_version", 1).commit()
        assertPrePrayerObservation()
    }

    private fun assertPrePrayerObservation() {
        shadowOf(c.getSystemService(NotificationManager::class.java)).setNotificationPolicyAccessGranted(true)
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        PrefsManager.markFirstLaunchDone(c)
        PrefsManager.setEnabled(c, true)
        assertTrue(MosqueSilenceSettings.saveMode(c, AutomaticSilenceMode.TIME_AND_MOSQUE))
        MosqueSilenceSettings.acceptBackgroundDisclosure(c)
        val w = SilenceScheduler.planner(c).windows(noon).first { it.start >= noon &&
            MosquePeriods.earlyStart(it) < it.start.toEpochMilli() }
        val early = MosquePeriods.earlyStart(w)
        SilenceScheduler.clock = { Instant.ofEpochMilli(early - 1_000) }
        val simulatedTime = Calendar.getInstance().apply { timeInMillis = early - 1_000 }
        SilenceScheduler.scheduleAllInternal(c, simulatedTime)
        val armed = earlyAlarms().single { it.triggerAtTime == early }
        val intent = shadowOf(armed.operation).savedIntent
        SilenceScheduler.clock = { Instant.ofEpochMilli(early) }
        SilenceReceiver().onReceive(c, Intent(intent))
        assertFalse(SilenceCoordinator.requested(c, SilenceOwner.MOSQUE))
        assertTrue(MosqueSilenceMonitor.canObserve(c))
        assertFalse(MosqueSilenceMonitor.hasWindow(c))
        val periods = AdaptiveMosqueMonitor.periods(c)
        assertTrue(periods.targets.any { it.prayer == w.prayer && it.prayerDay == w.prayerDay })
        MosqueSilenceMonitor.skip(c, setOf(MosquePrayerActions.key(w)))
        SilenceScheduler.clock = { w.start }
        assertTrue(SilenceScheduler.isDismissed(c, w.prayer, w.prayerDay))
        assertFalse(AdaptiveMosqueMonitor.periods(c).targets.any { it == w })
    }

    @Test fun timeOnlyNeverSchedulesMosqueObservationEvenWithTheOldOptIn() {
        PrefsManager.markFirstLaunchDone(c)
        PrefsManager.setEnabled(c, true)
        shadowOf(c.getSystemService(NotificationManager::class.java)).setNotificationPolicyAccessGranted(true)
        val simulatedTime = Calendar.getInstance().apply { timeInMillis = noon.toEpochMilli() }
        SilenceScheduler.clock = { noon }
        assertTrue(MosqueSilenceSettings.saveMode(c, AutomaticSilenceMode.TIME_ONLY))
        SilenceScheduler.scheduleAllInternal(c, simulatedTime)
        assertTrue(earlyAlarms().isEmpty())

        c.getSharedPreferences("mosque_silence_settings", Context.MODE_PRIVATE).edit()
            .putInt("adaptive_disclosure_version", 1).commit()
        MosqueSilenceSettings.acceptBackgroundDisclosure(c)
        assertTrue(MosqueSilenceSettings.saveMode(c, AutomaticSilenceMode.TIME_AND_MOSQUE))
        SilenceScheduler.scheduleAllInternal(c, simulatedTime)
        val previousAlarm = earlyAlarms().minBy { it.triggerAtTime }
        val previousIntent = Intent(shadowOf(previousAlarm.operation).savedIntent)

        assertTrue(MosqueSilenceSettings.saveMode(c, AutomaticSilenceMode.TIME_ONLY))
        SilenceScheduler.scheduleAllInternal(c, simulatedTime)
        assertTrue(earlyAlarms().isEmpty())
        SilenceScheduler.clock = { Instant.ofEpochMilli(previousAlarm.triggerAtTime) }
        SilenceReceiver().onReceive(c, previousIntent)
        assertFalse(MosqueSilenceMonitor.canObserve(c))
        assertFalse(SilenceCoordinator.requested(c, SilenceOwner.MOSQUE))
    }

    private fun earlyAlarms() = shadowOf(c.getSystemService(AlarmManager::class.java)).scheduledAlarms.filter {
        shadowOf(it.operation).savedIntent.action == SilenceScheduler.ACTION_MOSQUE_OBSERVE
    }

    @Test
    @Config(application = Application::class)
    fun coldRecoveryOfAnInterruptedBoundaryVisitBlocksOnlyItsPrayer() {
        SharedAndroid.init(c)
        PrefsManager.setEnabled(c, true)
        assertTrue(MosqueSilenceSettings.enroll(c))
        assertTrue(MosqueSilenceSettings.saveMode(c, AutomaticSilenceMode.TIME_AND_MOSQUE))
        MosqueSilenceSettings.acceptBackgroundDisclosure(c)
        val windows = SilenceScheduler.planner(c).windows(noon).sortedBy { it.start }
        val interrupted = windows.first { it.start > noon }
        val next = windows.first { it.start > interrupted.end }
        SilenceScheduler.clock = { interrupted.start }
        val start = requireNotNull(AdaptiveMosqueMonitor.periods(c).silence).start
        val journal = c.getSharedPreferences("mosque_presence", Context.MODE_PRIVATE)
        assertTrue(journal.edit().putLong("coverage_start", start).putBoolean("live", true)
            .putString("token", "previous-visit").commit())

        // Robolectric can reuse the SDK sandbox after another test has run recovery. A cold
        // Android process starts with this guard clear and no observation in memory.
        synchronized(SilenceCoordinator.serial) {
            val guard = MosqueSilenceMonitor::class.java.getDeclaredField("recoveryDone").apply { isAccessible = true }
            val previous = guard.getBoolean(null)
            try {
                AdaptiveMosqueMonitor.stop(c, barrier = false)
                guard.setBoolean(null, false)
                SilenceCoordinator.recover(c)
            } finally {
                guard.setBoolean(null, previous)
            }
        }

        assertEquals(start, journal.getLong("blocked_silence_start", -1))
        for (key in listOf("coverage_start", "live", "token")) assertFalse(key, journal.contains(key))
        assertNull(MosqueSilenceMonitor.plan(c, "previous-visit"))
        assertFalse(SilenceCoordinator.requested(c, SilenceOwner.MOSQUE))
        assertEquals(PresenceState.INACTIVE_NEEDS_REARM, MosqueSilenceMonitor.status(c))

        SilenceScheduler.clock = { interrupted.end }
        assertNotEquals(PresenceState.INACTIVE_NEEDS_REARM, MosqueSilenceMonitor.status(c))
        SilenceScheduler.clock = { next.start }
        assertTrue(MosqueSilenceMonitor.hasWindow(c))
        assertNotEquals(PresenceState.INACTIVE_NEEDS_REARM, MosqueSilenceMonitor.status(c))
    }

    @Test fun silenceAndObservationHaveIndependentGapsAndEnds() {
        fun w(p: Prayer, a: Long, start: Long, end: Long) = SilenceWindow(p,LocalDate.parse("2026-06-01"),
            noon.plusSeconds(a),noon.plusSeconds(start),noon.plusSeconds(end),EndResolution.AS_SET,false)
        val windows = listOf(w(Prayer.DHUHR,0,-300,300),w(Prayer.ASR,700,500,900))
        fun p(t: Long) = MosquePeriods.from(windows,noon.plusSeconds(t).toEpochMilli())
        assertNotNull(p(-600).observation); assertNull(p(-600).silence)
        assertNotNull(p(-300).silence); assertNull(p(300).silence)
        assertEquals(p(0).observation!!.start,p(400).observation!!.start)
        assertNotEquals(p(0).silence!!.start,p(500).silence!!.start)
        assertNull(p(900).observation)
        val later = listOf(w(Prayer.ISHA,0,7_200,8_000))
        assertNull(MosquePeriods.from(later,noon.plusSeconds(1_000).toEpochMilli()).observation)
    }

    @Test
    @Config(shadows = [AndroidAtomicFileShadow::class])
    fun strongerEvidenceDefaultsOffAndSurvivesAnAreaEdit() {
        val shape = SilencePolygon(listOf(GeoPoint(36.0,10.0),GeoPoint(36.001,10.0),GeoPoint(36.0,10.001)))
        val draft = AreaDraft("mosque", "Test", "", enabled = true).withPolygons(listOf(shape))
        PrivateSilenceAreaStore.save(c,draft,listOf(shape))
        assertFalse(PrivateSilenceAreaStore.read(c).areas.single().geometry.requireStrongerEvidence)
        PrivateSilenceAreaStore.setStricter(c,"mosque",true)
        PrivateSilenceAreaStore.save(c,draft.copy(name="Edited"),listOf(shape))
        assertTrue(PrivateSilenceAreaStore.read(c).areas.single().geometry.requireStrongerEvidence)
    }
}
