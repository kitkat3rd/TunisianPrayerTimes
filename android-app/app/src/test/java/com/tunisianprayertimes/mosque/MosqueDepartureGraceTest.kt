package com.tunisianprayertimes.mosque

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.SilenceReceiver
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.SilenceVerifyWorker
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import com.tunisianprayertimes.platform.SharedAndroid
import com.tunisianprayertimes.silence.SilenceWindow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSystemClock

/** The JVM has no installed Play Services APK. Fixes enter through the real monitor callback. */
@Implements(value = GoogleApiAvailability::class, isInAndroidSdk = false)
class AvailablePlayServices {
    @Implementation
    @Suppress("UNUSED_PARAMETER")
    protected fun isGooglePlayServicesAvailable(context: Context): Int = ConnectionResult.SUCCESS
}

abstract class MosqueGraceFixture {
    protected val context: Application get() = ApplicationProvider.getApplicationContext()
    protected lateinit var window: SilenceWindow
    protected lateinit var wall: Instant
    protected lateinit var token: String
    private val originalClock = SilenceScheduler.clock
    private var acceptedGeneration: Long? = null

    @Before fun prepareMosque() {
        SharedAndroid.init(context)
        PrefsManager.markFirstLaunchDone(context)
        PrefsManager.setEnabled(context, true)
        val notifications = context.getSystemService(NotificationManager::class.java)
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        shadowOf(notifications).setNotificationsEnabled(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        shadowOf(context.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
        assertTrue(SilenceCoordinator.enrollOrRepair(context))
        assertTrue(MosqueSilenceSettings.saveMode(context, AutomaticSilenceMode.TIME_AND_MOSQUE,
            backgroundUseExplained = true))
        val outline = SilencePolygon(listOf(GeoPoint(0.0, 0.0), GeoPoint(0.001, 0.0),
            GeoPoint(0.001, 0.001), GeoPoint(0.0, 0.001)))
        PrivateSilenceAreaStore.save(context, AreaDraft("hall", "Hall", ""), listOf(outline))
        val issues = MosqueReadiness.issues(context)
        assertTrue("Unmet prerequisites: ${issues.map(context::getString)}", issues.isEmpty())
        SilenceCoordinator.recover(context)
    }

    @After fun resetMonitor() {
        try {
            MosqueSilenceMonitor.environmentChanged(context)
        } finally {
            SilenceScheduler.clock = originalClock
            closeDetectionReportsAfterTest()
        }
    }

    protected fun begin(prayer: Prayer = Prayer.ISHA, secondsBeforeEnd: Long = 20) {
        window = requireNotNull(SilenceScheduler.windowOf(context, prayer, LocalDate.of(2026, 6, 1)))
        wall = window.end.minusSeconds(secondsBeforeEnd)
        SilenceScheduler.clock = { wall }
        token = requireNotNull(AdaptiveMosqueMonitor.reconcile(context, canStart = true)).token
    }

    protected fun at(offsetSeconds: Long) {
        val next = window.end.plusSeconds(offsetSeconds)
        require(next >= wall)
        ShadowSystemClock.advanceBy(Duration.between(wall, next))
        wall = next
    }

    protected fun plan() = MosqueSilenceMonitor.plan(context, token)
    protected fun requested() = SilenceCoordinator.requested(context, SilenceOwner.MOSQUE)

    protected fun fix(offsetSeconds: Long, inside: Boolean = true) {
        at(offsetSeconds)
        val plan = requireNotNull(plan())
        if (acceptedGeneration != plan.generation) {
            AdaptiveMosqueMonitor.accepted(context, token, plan.generation)
            acceptedGeneration = plan.generation
        }
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 0.0005
            longitude = if (inside) 0.0005 else 0.0016
            accuracy = 4f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            time = wall.toEpochMilli()
        }
        MosqueSilenceMonitor.onLocation(context, token, location, plan.generation)
    }

    protected fun confirm() {
        fix(-20)
        fix(-12)
        assertFalse(requested())
        fix(-4)
        assertTrue(requested())
    }

    protected fun endAlarm(offsetSeconds: Long = 0) {
        at(offsetSeconds)
        SilenceReceiver().onReceive(context, Intent("com.tunisianprayertimes.ACTION_UNSILENCE")
            .putExtra(SilenceScheduler.EXTRA_PRAYER, window.prayer.name)
            .putExtra(SilenceScheduler.EXTRA_PRAYER_DAY, window.prayerDay.toEpochDay()))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, shadows = [AvailablePlayServices::class],
    instrumentedPackages = ["com.google.android.gms.common"])
class MosqueDepartureGraceTest : MosqueGraceFixture() {
    @Test fun confirmedVisitSurvivesEndAlarmThenReleasesOnMeasuredDeparture() {
        begin()
        confirm()
        val beforeEnd = requireNotNull(plan())
        assertEquals(SystemClock.elapsedRealtime() + 4_000L, beforeEnd.deadlineElapsed)
        assertEquals(SystemClock.elapsedRealtime() + 904_000L, beforeEnd.providerEnd)
        endAlarm()
        assertTrue(requested())
        assertTrue(MosqueSilenceMonitor.canActivateLocked(context, alreadyRequested = true))
        assertFalse(MosqueSilenceMonitor.canActivateLocked(context, alreadyRequested = false))
        assertFalse(MosqueSilenceMonitor.hasWindow(context))
        assertEquals(window.end.plusSeconds(900).toEpochMilli(), MosqueSilenceMonitor.endWall(context))
        assertEquals(SystemClock.elapsedRealtime() + 900_000L, requireNotNull(plan()).endElapsed)

        endAlarm(5)
        val worker = TestListenableWorkerBuilder<SilenceVerifyWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
        assertTrue(requested())
        assertEquals(window.end.plusSeconds(900).toEpochMilli(), MosqueSilenceMonitor.endWall(context))
        fix(40)
        fix(80, inside = false)
        fix(99, inside = false)
        assertTrue(requested())
        fix(100, inside = false)
        assertFalse(requested())
        assertNull(plan())
        assertNull(AdaptiveMosqueMonitor.reconcile(context, canStart = true, deliberate = true))
        assertFalse(MosqueSilenceMonitor.canActivateLocked(context))
    }

    @Test fun graceDeadlineStopsEvenAFreshConfirmedVisitAndReleasesOnlyTheMosqueOwner() {
        begin(Prayer.ASR)
        confirm()
        assertTrue(SilenceCoordinator.setManual(context, true))
        endAlarm()
        for (second in 60L..840L step 60L) fix(second)
        fix(899)
        assertTrue(requested())
        val deadline = requireNotNull(plan()).deadlineElapsed
        assertEquals(SystemClock.elapsedRealtime() + 1_000L, deadline)
        at(900)
        MosqueControlReceiver().onReceive(context, Intent(MosqueControlReceiver.DEADLINE))
        assertFalse(requested())
        assertNull(plan())
        assertTrue(SilenceCoordinator.requested(context, SilenceOwner.MANUAL))
    }

    @Test fun anAlreadySilentVisitSurvivesTheCutoffDuringRuleValidation() {
        begin()
        confirm()
        at(-1)
        // The monitor reads the nominal period. The coordinator then checks the rule just after it ends.
        SilenceScheduler.clock = {
            val sampled = wall
            if (wall < window.end) {
                ShadowSystemClock.advanceBy(Duration.between(wall, window.end))
                wall = window.end
            }
            sampled
        }
        MosqueSilenceMonitor.reconcile(context)
        assertEquals(window.end, wall)
        assertTrue(requested())
        assertNotNull(plan())
        assertEquals(window.end.plusSeconds(900).toEpochMilli(), MosqueSilenceMonitor.endWall(context))
    }

    @Test fun scheduleFallbackCannotContinueIntoGraceOrActivateOnLateLocationRecovery() {
        begin()
        val locations = shadowOf(context.getSystemService(LocationManager::class.java))
        locations.setLocationEnabled(false)
        MosqueSilenceMonitor.reconcile(context)
        assertTrue(AdaptiveMosqueMonitor.fallbackRequested(context))
        endAlarm()
        assertFalse(requested())
        locations.setLocationEnabled(true)
        assertNull(AdaptiveMosqueMonitor.reconcile(context, canStart = true, deliberate = true))
        assertFalse(requested())
    }

    @Test fun lossOfLocationDuringGraceCannotBecomeScheduleFallback() {
        begin()
        confirm()
        endAlarm()
        assertTrue(requested())
        val locations = shadowOf(context.getSystemService(LocationManager::class.java))
        locations.setLocationEnabled(false)
        MosqueSilenceMonitor.reconcile(context)
        assertFalse(requested())
        assertFalse(AdaptiveMosqueMonitor.fallbackRequested(context))
        locations.setLocationEnabled(true)
        assertNull(AdaptiveMosqueMonitor.reconcile(context, canStart = true, deliberate = true))
    }

    @Test fun aVisitWithoutFreshPositionEvidenceExpiresBeforeTheGraceCap() {
        begin()
        confirm()
        endAlarm()
        fix(10)
        at(309)
        MosqueSilenceMonitor.reconcile(context)
        assertTrue(requested())
        at(310)
        MosqueControlReceiver().onReceive(context, Intent(MosqueControlReceiver.DEADLINE))
        assertFalse(requested())
        assertNull(plan())
    }

    @Test fun anUnconfirmedVisitCannotFinishArrivalAfterTheNominalCutoff() {
        begin(Prayer.ASR)
        fix(-8)
        fix(-2)
        assertFalse(requested())
        endAlarm()
        assertNull(plan())
        assertFalse(requested())
        assertNull(AdaptiveMosqueMonitor.reconcile(context, canStart = true))
    }

    @Test fun aPersistedRuleWithoutItsLiveVisitCannotBeExtended() {
        begin()
        confirm()
        AdaptiveMosqueMonitor.stop(context, barrier = false) // Simulate loss of process-local evidence.
        assertTrue(requested())
        endAlarm(10)
        assertFalse(requested())
        assertNull(AdaptiveMosqueMonitor.reconcile(context, canStart = true))
    }

    @Test fun skipStillAddressesThePrayerWhoseVisitIsContinuing() {
        begin()
        confirm()
        endAlarm(5)
        assertTrue(requested())
        val key = MosquePrayerActions.key(window)
        MosqueSilenceMonitor.skip(context, setOf(key))
        assertTrue(MosqueSilenceMonitor.isDismissed(context, key))
        assertFalse(requested())
        assertNull(plan())
    }
}
