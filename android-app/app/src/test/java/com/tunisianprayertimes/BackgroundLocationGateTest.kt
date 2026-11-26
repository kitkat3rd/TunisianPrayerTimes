package com.tunisianprayertimes

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * 6.8's background fixes on Android 10+: Android refuses them to an app granted the location for its screens only, so
 * the pre-prayer check, which only wakes the phone for that fix, is armed only with the background-location permission.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundLocationGateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var alarms: ShadowAlarmManager

    @Before
    fun setup() {
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationPolicyAccessGranted(true)
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        PrefsManager.setEnabled(context, true)
    }

    private fun prePrayerChecks() = alarms.scheduledAlarms.count {
        shadowOf(it.operation).savedIntent.action == "com.tunisianprayertimes.ACTION_DELEGATION_CHECK"
    }

    @Test
    fun withoutBackgroundLocation_noPrePrayerCheckIsArmed() {
        PrefsManager.setBackgroundTravelEnabled(context, true)
        SilenceScheduler.scheduleAll(context)

        assertTrue(alarms.scheduledAlarms.isNotEmpty())
        assertEquals(0, prePrayerChecks())
        assertFalse(DelegationLocator.canUpdateTravelInBackground(context))
    }

    @Test
    fun withBackgroundTravelAndPermission_thePrePrayerChecksAreArmed() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        PrefsManager.setBackgroundTravelEnabled(context, true)

        SilenceScheduler.scheduleAll(context)

        assertTrue(prePrayerChecks() > 0)
        assertTrue(DelegationLocator.canUpdateTravelInBackground(context))
    }

    @Test
    fun withAutoLocationOff_nothingIsAskedOrArmed() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        PrefsManager.setBackgroundTravelEnabled(context, true)
        PrefsManager.setAutoLocationUpdateEnabled(context, false)

        SilenceScheduler.scheduleAll(context)

        assertEquals(0, prePrayerChecks())
        assertFalse(PrefsManager.isBackgroundTravelEnabled(context))
        assertFalse(DelegationLocator.canUpdateTravelInBackground(context))
    }

    @Test
    fun grantingLocationForAnotherFeatureCannotEnableTravelAfterUpgrade() {
        PrefsManager.markFirstLaunchDone(context)
        PrefsManager.initializeFirstRun(context)
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        SilenceScheduler.scheduleAll(context)

        assertFalse(PrefsManager.isBackgroundTravelEnabled(context))
        assertEquals(0, prePrayerChecks())
    }

    @Test
    fun legacyTravelWithExistingBackgroundAccessIsPreservedAtUpgrade() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        PrefsManager.markFirstLaunchDone(context)
        PrefsManager.initializeFirstRun(context)

        assertTrue(DelegationLocator.canUpdateTravelInBackground(context))
    }

    @Test
    @Config(sdk = [28])
    fun olderAndroidForegroundGrantStillRequiresAnExplicitTravelChoice() {
        assertTrue(DelegationLocator.hasBackgroundLocationPermission(context))
        assertFalse(DelegationLocator.canUpdateTravelInBackground(context))
        PrefsManager.setBackgroundTravelEnabled(context, true)
        assertTrue(DelegationLocator.canUpdateTravelInBackground(context))
    }
}
