package com.tunisianprayertimes

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.Manifest
import android.content.Context
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.util.concurrent.TimeUnit

/**
 * Tests for SilenceVerifyWorker using WorkManager's test infrastructure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class SilenceVerifyWorkerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()

        // Initialize WorkManager for testing
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun doWork_whenEnabled_returnsSuccess() = runBlocking {
        PrefsManager.setEnabled(context, true)
        val worker = TestListenableWorkerBuilder<SilenceVerifyWorker>(context).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun doWork_whenDisabled_returnsSuccess() = runBlocking {
        PrefsManager.setEnabled(context, false)
        val worker = TestListenableWorkerBuilder<SilenceVerifyWorker>(context).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun queuedTravelWorkerAfterOptOutDoesNotStartALocationLookup() = runBlocking {
        Shadows.shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        PrefsManager.setAutoLocationUpdateEnabled(context, true)
        PrefsManager.setBackgroundTravelEnabled(context, true)
        val worker = TestListenableWorkerBuilder<TravelPlaceWorker>(context).build()
        var lookups = 0
        val provider = DelegationLocator.locationProvider
        DelegationLocator.locationProvider = object : DelegationLocationProvider by provider {
            override suspend fun findFreshLocation(context: Context, permissionState: LocationPermissionState): android.location.Location? {
                lookups++
                return null
            }
        }
        try {
            PrefsManager.setBackgroundTravelEnabled(context, false)
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(0, lookups)
        } finally {
            DelegationLocator.locationProvider = provider
        }
    }

    @Test
    fun enqueue_doesNotThrow() {
        // Should not throw even when called multiple times
        SilenceVerifyWorker.enqueue(context)
        SilenceVerifyWorker.enqueue(context)
    }

    @Test
    fun cancel_doesNotThrow() {
        SilenceVerifyWorker.cancel(context)
    }

    @Test
    fun enqueueAndCancel_cycle() {
        SilenceVerifyWorker.enqueue(context)
        SilenceVerifyWorker.cancel(context)
        SilenceVerifyWorker.enqueue(context)
    }

    @Test
    fun doWork_whenEnabled_actuallySchedulesAlarms() = runBlocking {
        Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationPolicyAccessGranted(true)
        PrefsManager.setEnabled(context, true)

        val worker = TestListenableWorkerBuilder<SilenceVerifyWorker>(context).build()
        worker.doWork()

        // doWork calls scheduleAll on applicationContext — get the AlarmManager from it
        val alarmManager = worker.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAM: ShadowAlarmManager = Shadows.shadowOf(alarmManager)

        assertTrue(
            "doWork with enabled=true should schedule alarms",
            shadowAM.scheduledAlarms.isNotEmpty()
        )
    }

    @Test
    fun doWork_whenDisabled_doesNotScheduleAlarms() = runBlocking {
        PrefsManager.setEnabled(context, false)

        // Clear any pre-existing alarms
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAM: ShadowAlarmManager = Shadows.shadowOf(alarmManager)
        val alarmsBefore = shadowAM.scheduledAlarms.size

        val worker = TestListenableWorkerBuilder<SilenceVerifyWorker>(context).build()
        worker.doWork()

        // No new alarms should be added
        assertEquals(
            "doWork with enabled=false should NOT add new alarms",
            alarmsBefore,
            shadowAM.scheduledAlarms.size
        )
    }

    private fun activePeriodicJobs() = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(SilenceVerifyWorker.WORK_NAME).get(5, TimeUnit.SECONDS)
        .filter { !it.state.isFinished }

    @Test
    @Config(sdk = [34])
    fun backgroundTravelAloneSchedulesOneBatteryConstrainedJobAndFindsASameZoneMove() = runBlocking {
        val app = context as Application
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationPolicyAccessGranted(false)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        PrefsManager.initializeFirstRun(context)
        PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Local(Locality("sfax", "صفاقس", "", 1, 386, "صفاقس")))
        PrefsManager.setAutoLocationUpdateEnabled(context, true)
        assertTrue(activePeriodicJobs().isEmpty())
        PrefsManager.setBackgroundTravelEnabled(context, true)
        val single = activePeriodicJobs().single()
        assertEquals(Constraints.Builder().setRequiresBatteryNotLow(true).build(), single.constraints)
        ScheduleRefreshCoordinator.syncSilence(context)
        assertEquals(single.id, activePeriodicJobs().single().id)
        assertFalse(PrefsManager.isEnabled(context))

        val fix = Location("test").apply {
            latitude = 36.8428
            longitude = 10.1465
            time = System.currentTimeMillis()
            accuracy = 30f
        }
        val expected = GouvernoratRepository.findNearestDelegation(context, fix.latitude, fix.longitude)!!.id
        assertNotEquals(386, expected)
        val provider = DelegationLocator.locationProvider
        DelegationLocator.locationProvider = object : DelegationLocationProvider by provider {
            override suspend fun findFreshLocation(context: Context, permissionState: LocationPermissionState) = fix
        }
        try {
            val worker = TestListenableWorkerBuilder<SilenceVerifyWorker>(context).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals(expected, PrefsManager.getDelegationId(context))
            assertFalse(PrefsManager.isEnabled(context))
            PrefsManager.setBackgroundTravelEnabled(context, false)
            assertTrue(activePeriodicJobs().isEmpty())
        } finally {
            DelegationLocator.locationProvider = provider
        }
    }

    @Test
    @Config(sdk = [34])
    fun queuedJobAfterBackgroundLocationRevocationDoesNotReadLocationAndCanBeRearmed() = runBlocking {
        val device = Shadows.shadowOf(context as Application)
        device.grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        PrefsManager.setEnabled(context, false)
        PrefsManager.setAutoLocationUpdateEnabled(context, true)
        PrefsManager.setBackgroundTravelEnabled(context, true)
        assertEquals(1, activePeriodicJobs().size)
        device.denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val provider = DelegationLocator.locationProvider
        DelegationLocator.locationProvider = object : DelegationLocationProvider by provider {
            override suspend fun findFreshLocation(context: Context, permissionState: LocationPermissionState): Location? =
                throw AssertionError("A revoked travel permission reached the location provider")
        }
        try {
            val worker = TestListenableWorkerBuilder<SilenceVerifyWorker>(context).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertTrue(activePeriodicJobs().isEmpty())
            device.grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            SilenceVerifyWorker.reconcileSchedule(context)
            assertEquals(1, activePeriodicJobs().size)
        } finally {
            DelegationLocator.locationProvider = provider
        }
    }

    @Test fun turningOffTravelKeepsTheJobForEstablishedPrayerSilence() {
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationPolicyAccessGranted(true)
        PrefsManager.setEnabled(context, true)
        PrefsManager.setAutoLocationUpdateEnabled(context, true)
        PrefsManager.setBackgroundTravelEnabled(context, true)
        val job = activePeriodicJobs().single().id
        PrefsManager.setAutoLocationUpdateEnabled(context, false)
        assertEquals(job, activePeriodicJobs().single().id)
        assertFalse(PrefsManager.isBackgroundTravelEnabled(context))
    }
}
