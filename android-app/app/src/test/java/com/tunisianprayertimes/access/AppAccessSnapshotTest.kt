package com.tunisianprayertimes.access

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.common.GooglePlayServicesUtilLight
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.MosqueSilenceSettings
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppAccessSnapshotTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun optedOutSilenceDoesNotAskForDndAndTimeOnlyNeverAsksForGps() = runBlocking {
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationPolicyAccessGranted(false)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        PrefsManager.setEnabled(app, false)
        val off = readAppAccess(app)
        assertNull(off.silenceIssues)
        assertTrue(off.issues.none { it.feature == R.string.auto_silence })

        PrefsManager.markFirstLaunchDone(app)
        PrefsManager.setEnabled(app, true)
        MosqueSilenceSettings.saveMode(app, AutomaticSilenceMode.TIME_ONLY)
        val timed = readAppAccess(app)
        assertEquals(listOf(R.string.mosque_needs_dnd), timed.silenceIssues)
        assertEquals(false, timed.platform?.dnd)
        assertEquals(true, timed.platform?.exact)
        assertEquals(listOf(AccessAction.DND), timed.issues.filter { it.feature == R.string.auto_silence }.map { it.action })
    }

    @Test fun malformedSilencePreferencesDoNotHideOtherFeaturesOrTheirPlatformGrants() = runBlocking {
        PrefsManager.markFirstLaunchDone(app)
        PrefsManager.setEnabled(app, true)
        app.getSharedPreferences("mosque_silence_settings", Context.MODE_PRIVATE)
            .edit().putInt("mode", -1).commit()
        PrefsManager.setCallEndVibrationEnabled(app, true)
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)

        val result = readAppAccess(app, storedWakeAlarms = emptyList())
        assertNull(result.silenceIssues)
        assertEquals(false, result.platform?.phoneState)
        assertTrue(result.issues.any { it.feature == R.string.call_end_vibration_title })
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val repaired = readAppAccess(app, storedWakeAlarms = emptyList())
        assertEquals(true, repaired.platform?.phoneState)
        assertFalse(repaired.issues.any { it.feature == R.string.call_end_vibration_title })
    }

    @Test fun freshSnapshotsReflectLocationGrantsAndKeepAreaSetupOnPrayerWithoutStartingObservation() = runBlocking {
        // The JVM test host has no Google Play Services manifest metadata.
        val field = GooglePlayServicesUtilLight::class.java.getDeclaredField("zzc").apply { isAccessible = true }
        val independent = field.get(null) as AtomicBoolean
        val oldValue = independent.get()
        try {
            GooglePlayServicesUtilLight.enableUsingApkIndependentContext()
            PrefsManager.markFirstLaunchDone(app)
            PrefsManager.setEnabled(app, true)
            MosqueSilenceSettings.saveMode(app, AutomaticSilenceMode.TIME_AND_MOSQUE)
            val fine = Manifest.permission.ACCESS_FINE_LOCATION
            val background = Manifest.permission.ACCESS_BACKGROUND_LOCATION
            shadowOf(app).denyPermissions(fine, background)
            val before = readAppAccess(app)
            assertTrue(R.string.mosque_needs_precise in before.silenceIssues.orEmpty())
            assertTrue(R.string.mosque_needs_background in before.silenceIssues.orEmpty())
            assertTrue(before.issues.any { it.message == R.string.mosque_needs_precise && it.action == AccessAction.MOSQUE_SETUP })
            assertTrue(before.issues.any { it.message == R.string.mosque_needs_background && it.action == AccessAction.MOSQUE_SETUP })
            assertTrue(R.string.mosque_no_areas in before.silenceIssues.orEmpty())
            assertTrue(before.issues.none { it.message == R.string.mosque_no_areas || it.message == R.string.mosque_needs_disclosure })

            shadowOf(app).grantPermissions(fine, background)
            val granted = readAppAccess(app)
            assertFalse(R.string.mosque_needs_precise in granted.silenceIssues.orEmpty())
            assertFalse(R.string.mosque_needs_background in granted.silenceIssues.orEmpty())
            assertTrue(granted.issues.none { it.message == R.string.mosque_needs_background })

            shadowOf(app).denyPermissions(background)
            assertTrue(R.string.mosque_needs_background in readAppAccess(app).silenceIssues.orEmpty())
            assertNull(shadowOf(app).nextStartedService)
            val locations = shadowOf(app.getSystemService(LocationManager::class.java))
            assertTrue(locations.locationUpdateListeners.isEmpty())
            assertTrue(locations.locationUpdatePendingIntents.isEmpty())
        } finally {
            independent.set(oldValue)
        }
    }
}
