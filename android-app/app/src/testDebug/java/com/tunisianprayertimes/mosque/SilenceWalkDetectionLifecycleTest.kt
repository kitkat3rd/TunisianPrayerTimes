package com.tunisianprayertimes.mosque

import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import com.google.android.gms.location.Geofence
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceReceiver
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.mosque.trace.SilenceTraceStore
import com.tunisianprayertimes.wake.displayName
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, shadows = [AvailablePlayServices::class],
    instrumentedPackages = ["com.google.android.gms.common"])
class SilenceWalkDetectionLifecycleTest : MosqueGraceFixture() {
    private fun active() = SilenceTraceStore.activeId(context)
    private fun events(id: Long) = SilenceTraceStore.entries(context, id)

    @Test fun earlyObservationAlarmStartsOneReportAndAnUnconfirmedWindowClosesIt() {
        window = requireNotNull(SilenceScheduler.windowOf(context, Prayer.ISHA, LocalDate.of(2026, 6, 1)))
        wall = Instant.ofEpochMilli(MosquePeriods.earlyStart(window))
        SilenceScheduler.clock = { wall }
        assertTrue(wall < window.start)
        val alarm = Intent(SilenceScheduler.ACTION_MOSQUE_OBSERVE)
            .putExtra(SilenceScheduler.EXTRA_PRAYER, window.prayer.name)
            .putExtra(SilenceScheduler.EXTRA_PRAYER_DAY, window.prayerDay.toEpochDay())

        MosqueSilenceMonitor.reconcile(context)
        assertNull(active())
        SilenceReceiver().onReceive(context, alarm)
        val id = requireNotNull(active())
        assertFalse(requested())
        assertEquals("recording_started", events(id).first().kind)
        assertTrue(events(id).any { it.kind == "mosque_sampling" })
        val openingAlarm = context.getString(R.string.trace_prayer_observe_alarm, window.prayer.displayName(context))
        assertTrue(events(id).any { it.kind == "prayer_alarm" && it.detail == openingAlarm })
        SilenceReceiver().onReceive(context, alarm)
        MosqueSilenceMonitor.reconcile(context)
        assertEquals(id, active())

        endAlarm()
        assertNull(active())
        assertEquals(listOf(id), SilenceTraceStore.sessions(context).map { it.id })
        assertEquals("recording_stopped", events(id).last().kind)
    }

    @Test fun aVisitStaysInOneReportAcrossGraceUntilTheDepartureAndReleaseAreSaved() {
        begin()
        val id = requireNotNull(active())
        confirm()
        endAlarm()
        fix(40)
        fix(80, inside = false)
        fix(90, inside = false)
        fix(99, inside = false)
        assertEquals(id, active())
        assertTrue(requested())

        fix(100, inside = false)
        assertFalse(requested())
        assertNull(active())
        val rows = events(id)
        val departure = rows.single { it.kind == "mosque_visit_ended" }
        assertEquals(0.0016, requireNotNull(departure.longitude), 0.000001)
        val end = rows.last()
        assertEquals("recording_stopped", end.kind)
        val endAlarm = context.getString(R.string.trace_prayer_end_alarm, window.prayer.displayName(context))
        assertTrue(rows.single { it.kind == "prayer_alarm" && it.detail == endAlarm }.id < end.id)
        assertTrue(rows.single { it.kind == "rule_released" }.id < end.id)
        assertTrue(departure.id < end.id)
        assertEquals(1, rows.count { it.kind == "recording_started" })
        assertEquals(listOf(id), SilenceTraceStore.sessions(context).map { it.id })
    }

    @Test fun leavingByAnotherDoorEndsGraceAndRecordsTheSustainedExteriorFixes() {
        begin()
        val id = requireNotNull(active())
        confirm()
        endAlarm()
        // The east wall is at 0.001°. Every 4 m accuracy circle clears it by about 5 m.
        val pavement = 0.001081
        for (second in 30L..70L step 5) {
            fixAtLongitude(second, pavement)
            assertTrue(requested())
        }
        fixAtLongitude(75, pavement)
        assertFalse(requested())
        assertNull(plan())
        assertNull(active())
        val departed = events(id).single { it.kind == "mosque_visit_ended" }
        assertEquals(context.getString(R.string.trace_mosque_stayed_outside, "Hall"), departed.detail)
        assertEquals(pavement, requireNotNull(departed.longitude), 0.000001)
    }

    private fun departWhileWindowIsOpen(): Long {
        begin(secondsBeforeEnd = 150)
        val first = requireNotNull(active())
        fix(-150)
        fix(-142)
        fix(-134)
        assertTrue(requested())
        fix(-100, inside = false)
        fix(-90, inside = false)
        fix(-80, inside = false)
        assertFalse(requested())
        assertNull(active())
        val finished = events(first)
        assertEquals("recording_stopped", finished.last().kind)
        assertTrue(finished.any { it.kind == "mosque_visit_ended" })
        assertTrue(finished.any { it.kind == "rule_released" })

        repeat(3) { assertNotNull(plan()) }
        fix(-75, inside = false)
        assertNull(active())
        return first
    }

    @Test fun nominalDepartureClosesTheWalkAndASeparateArrivalSearchStartsAnother() {
        val first = departWhileWindowIsOpen()
        val finished = events(first)
        at(-65)
        MosqueSilenceMonitor.reconcile(context, canStart = true, transition = Geofence.GEOFENCE_TRANSITION_ENTER)
        val second = requireNotNull(active())
        assertNotEquals(first, second)
        assertEquals(finished, events(first))
        assertEquals(listOf(second, first), SilenceTraceStore.sessions(context).map { it.id })
    }

    @Test fun returningAlongThePavementReleasesTheRuleAndFinishesOneReport() {
        begin(secondsBeforeEnd = 300)
        val id = requireNotNull(active())
        var offset = -300L
        var generation: Long? = null
        fun move(east: Int, north: Int, accuracy: Float = 8f) {
            offset += 5
            at(offset)
            val plan = requireNotNull(plan())
            if (generation != plan.generation) {
                AdaptiveMosqueMonitor.accepted(context, token, plan.generation)
                generation = plan.generation
            }
            val location = Location(LocationManager.GPS_PROVIDER).apply {
                longitude = east / 111_319.5
                latitude = north / 110_574.0
                this.accuracy = accuracy
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                time = wall.toEpochMilli()
            }
            MosqueSilenceMonitor.onLocation(context, token, location, plan.generation)
        }

        // The west wall is at east = 0. Follow its pavement to the door at north = 60 m.
        move(-36, 5)
        move(-26, 8)
        move(-18, 11)
        move(-10, 14)
        for (north in 20..60 step 8) {
            move(-5, north)
            assertFalse(requested())
        }
        for (east in listOf(3, 11, 19, 25)) move(east, 60, 4f)
        repeat(4) { move(25, 60, 4f) }
        assertTrue(requested())
        for (east in listOf(17, 10, 3)) move(east, 60, 4f)
        move(-5, 60)
        assertTrue(requested())
        for (north in 52 downTo 20 step 8) move(-5, north)

        assertTrue(wall < window.end)
        assertFalse(requested())
        assertNull(active())
        val rows = events(id)
        val exit = rows.single { it.kind == "mosque_visit_ended" }
        assertEquals(context.getString(R.string.trace_mosque_returned_to_path, "Hall"), exit.detail)
        assertEquals(-5 / 111_319.5, requireNotNull(exit.longitude), 0.000001)
        assertTrue(exit.id < rows.single { it.kind == "rule_released" }.id)
        assertEquals("recording_stopped", rows.last().kind)

        for (north in listOf(16, 12, 8)) move(-5, north)
        assertNull(active())
        assertEquals(listOf(id), SilenceTraceStore.sessions(context).map { it.id })
        assertEquals(rows, events(id))
    }

    @Test fun aReturnFixCanStartASeparateWalkBeforeAnotherGeofenceIsDelivered() {
        val previous = departWhileWindowIsOpen()
        at(-35)
        val current = requireNotNull(plan())
        val returning = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 0.0005
            longitude = 0.0005
            accuracy = 4f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 3_000_000_000L
            time = wall.minusSeconds(3).toEpochMilli()
        }
        MosqueSilenceMonitor.onLocation(context, token, returning, current.generation)
        val next = requireNotNull(active())
        assertNotEquals(previous, next)
        val rows = events(next)
        val firstFix = rows.single { it.kind == "monitor_fix" }
        assertEquals(returning.longitude, requireNotNull(firstFix.longitude), 0.0)
        assertEquals(rows.first { it.kind == "recording_started" }.at, firstFix.at)
    }

    @Test fun theNominalPrayerAlarmStartsOneNewSearchAfterAnEarlyVisitHasEnded() {
        PrefsManager.setConfig(context, Prayer.ISHA, PrayerSilenceConfig(afterMinutes = 20))
        val nominalStart = -20L * 60
        begin(secondsBeforeEnd = -nominalStart + 150)
        val early = requireNotNull(active())
        fun visit(from: Long) {
            fix(from)
            fix(from + 8)
            fix(from + 16)
            fix(from + 50, inside = false)
            fix(from + 60, inside = false)
            fix(from + 70, inside = false)
        }
        visit(nominalStart - 150)
        assertTrue(events(early).any { it.kind == "mosque_visit_ended" })
        assertNull(active())
        val alarm = Intent("com.tunisianprayertimes.ACTION_SILENCE")
            .putExtra(SilenceScheduler.EXTRA_PRAYER, window.prayer.name)
            .putExtra(SilenceScheduler.EXTRA_PRAYER_DAY, window.prayerDay.toEpochDay())
        at(nominalStart)
        assertEquals(window.start, wall)
        SilenceReceiver().onReceive(context, alarm)
        val nominal = requireNotNull(active())
        assertNotEquals(early, nominal)
        visit(nominalStart + 1)
        assertNull(active())
        SilenceReceiver().onReceive(context, alarm)
        assertNull(active())
        assertEquals(listOf(nominal, early), SilenceTraceStore.sessions(context).map { it.id })
    }

    @Test fun anExplicitSkipClosesAfterTheSkipAndSoundRuleEvents() {
        begin()
        val id = requireNotNull(active())
        confirm()
        val targets = setOf(MosquePrayerActions.key(window))
        repeat(2) { MosqueSilenceMonitor.skip(context, targets) }
        MosqueSilenceMonitor.reconcile(context, canStart = true)
        assertNull(active())
        assertFalse(requested())
        val kinds = events(id).map { it.kind }
        assertEquals(1, kinds.count { it == "mosque_skipped" })
        assertEquals(1, kinds.count { it == "rule_released" })
        assertEquals(1, kinds.count { it == "recording_stopped" })
        assertEquals("recording_stopped", kinds.last())
    }

    @Test fun locationFallbackWithoutAnArrivalSearchCannotStartRecording() {
        window = requireNotNull(SilenceScheduler.windowOf(context, Prayer.ISHA, LocalDate.of(2026, 6, 1)))
        wall = window.start
        SilenceScheduler.clock = { wall }
        shadowOf(context.getSystemService(LocationManager::class.java)).setLocationEnabled(false)
        MosqueSilenceMonitor.reconcile(context, canStart = true)
        assertTrue(AdaptiveMosqueMonitor.fallbackRequested(context))
        assertNull(active())
        assertTrue(SilenceTraceStore.sessions(context).isEmpty())
    }

    @Test fun anUnsuccessfulSearchEndsWhenTheDetectorParks() {
        begin(secondsBeforeEnd = 120)
        val id = requireNotNull(active())
        AdaptiveMosqueMonitor.accepted(context, token, requireNotNull(plan()).generation)
        at(-30)
        assertNull(plan())
        assertTrue(AdaptiveMosqueMonitor.fallbackRequested(context))
        assertNull(active())
        assertEquals("recording_stopped", events(id).last().kind)
        MosqueSilenceMonitor.reconcile(context)
        assertEquals(listOf(id), SilenceTraceStore.sessions(context).map { it.id })
    }

    @Test fun anUpdatedInstallPreservesSavedAndInterruptedReportsWhenTheNextDetectionStarts() {
        val completed = SilenceTraceStore.start(context)
        SilenceTraceStore.stop(context, completed)
        val saved = events(completed)
        val interrupted = SilenceTraceStore.start(context)
        SilenceTraceStore.append(context, "route", latitude = 36.8, longitude = 10.2, sessionId = interrupted)
        SilenceTraceStore.closeForTest()
        context.getSharedPreferences("debug_silence_walk", Context.MODE_PRIVATE)
            .edit().putBoolean("paused_by_user", true).commit()

        begin()
        val next = requireNotNull(active())
        assertEquals(listOf(next, interrupted, completed), SilenceTraceStore.sessions(context).map { it.id })
        assertEquals(saved, events(completed))
        assertEquals(listOf("recording_started", "route", "recording_interrupted"), events(interrupted).map { it.kind })
    }
}
