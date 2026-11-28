package com.tunisianprayertimes.mosque

import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.provider.Settings
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, shadows = [AvailablePlayServices::class],
    instrumentedPackages = ["com.google.android.gms.common"])
class MosqueObservationInterruptionTest : MosqueGraceFixture() {
    private fun coldStart() = synchronized(SilenceCoordinator.serial) {
        // A killed process loses these fields without receiving a service stop callback.
        AdaptiveMosqueMonitor::class.java.getDeclaredField("current").apply {
            isAccessible = true
            set(null, null)
        }
        MosqueSilenceMonitor::class.java.getDeclaredField("recoveryDone").apply {
            isAccessible = true
            setBoolean(null, false)
        }
        SilenceCoordinator.recover(context)
    }

    private fun accept() = AdaptiveMosqueMonitor.accepted(context, token, requireNotNull(plan()).generation)
    private fun pending() = MosqueObservationInterruptions.pending(context)

    @Test fun aScheduledWindowAndStaleRequestCallbacksDoNotCreateAnInterruption() {
        begin()
        val generation = requireNotNull(plan()).generation
        AdaptiveMosqueMonitor.accepted(context, "other", generation)
        AdaptiveMosqueMonitor.accepted(context, token, generation + 1)
        coldStart()
        assertNull(pending())
        assertFalse(requested())
    }

    @Test fun activeMonitoringBeforeConfirmationSurvivesRecoveryUntilThatNoticeIsAcknowledged() {
        begin()
        accept()
        coldStart()
        val first = requireNotNull(pending())
        assertEquals(Prayer.ISHA, first.prayer)
        assertEquals(window.prayerDay, first.prayerDay)
        assertFalse(first.confirmedVisit)
        coldStart()
        assertEquals(first, pending())

        begin(Prayer.MAGHRIB)
        accept()
        coldStart()
        val second = requireNotNull(pending())
        assertEquals(Prayer.MAGHRIB, second.prayer)
        assertNotEquals(first.id, second.id)
        MosqueObservationInterruptions.acknowledge(context, first.id)
        assertEquals(second, pending())
        MosqueObservationInterruptions.acknowledge(context, second.id)
        coldStart()
        assertNull(pending())
    }

    @Test fun aConfirmedContinuationSurvivesAsANoticeAfterRebootWithoutRestoringSilence() {
        begin()
        confirm()
        endAlarm(3)
        assertNotNull(AdaptiveMosqueMonitor.periods(context).continuationEnd)
        at(1_000)
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, boot + 1)
        coldStart()
        val notice = requireNotNull(pending())
        assertEquals(Prayer.ISHA, notice.prayer)
        assertEquals(window.prayerDay, notice.prayerDay)
        assertTrue(notice.confirmedVisit)
        assertFalse(requested())
        assertNull(AdaptiveMosqueMonitor.periods(context).continuationEnd)
    }

    @Test fun aParkedMonitorAndAnIntentionalModeChangeLeaveNoIncident() {
        begin()
        accept()
        AdaptiveMosqueMonitor.setWakeCoverage(token, requireNotNull(plan()).endElapsed)
        val far = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 0.02
            longitude = 0.01
            accuracy = 5f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        MosqueSilenceMonitor.onLocation(context, token, far, requireNotNull(plan()).generation)
        assertNull(plan())
        coldStart()
        assertNull(pending())

        begin()
        accept()
        assertTrue(MosqueSilenceSettings.saveMode(context, AutomaticSilenceMode.TIME_ONLY))
        MosqueSilenceMonitor.environmentChanged(context)
        coldStart()
        assertNull(pending())
    }

    @Test fun losingTheRegisteredWakeCirclesKeepsAnOngoingVisitAndItsFusedProvider() {
        begin()
        confirm()
        val before = requireNotNull(plan())
        context.getSharedPreferences("mosque_presence", Application.MODE_PRIVATE).edit()
            .putString("geofence_token", token).commit()
        AdaptiveMosqueMonitor.setWakeCoverage(token, before.endElapsed)

        MosqueObservationService.wakeCirclesLost(context, token)

        val after = requireNotNull(plan())
        assertEquals(before.token, after.token)
        assertEquals(before.generation, after.generation)
        assertEquals(before.state, after.state)
        assertTrue(requested())
        assertNull(pending())
    }

    @Test fun anUnexpectedServiceLossOnlyReportsARequestThatActuallyStarted() {
        begin()
        AdaptiveMosqueMonitor.serviceGone(context, token)
        assertNull(pending())
        begin()
        accept()
        AdaptiveMosqueMonitor.serviceGone(context, token)
        assertFalse(requireNotNull(pending()).confirmedVisit)
        coldStart()
        assertNotNull(pending())
    }

    @Test fun aNativeFixBeforeRequestAcceptanceStillIdentifiesARealObservation() {
        begin()
        val near = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 0.0005
            longitude = 0.0011
            accuracy = 5f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        MosqueSilenceMonitor.onLocation(context, token, near, requireNotNull(plan()).generation)
        coldStart()
        assertFalse(requireNotNull(pending()).confirmedVisit)
    }

    @Test fun aCleanPrayerWindowEndDoesNotWarnOnTheNextLaunch() {
        begin()
        accept()
        at(1)
        assertNull(plan())
        coldStart()
        assertNull(pending())
    }

    @Test fun aPreviouslyConfirmedDepartureIsNotReportedAsAnInterruptedVisit() {
        begin(secondsBeforeEnd = 100)
        for (at in listOf(-95L, -87L, -79L)) fix(at)
        assertTrue(requested())
        fix(-50, inside = false)
        fix(-39, inside = false)
        fix(-28, inside = false)
        assertFalse(requested())
        assertNotNull(plan()) // Still checking nearby locations in this prayer window.
        coldStart()
        assertFalse(requireNotNull(pending()).confirmedVisit)
    }
}
