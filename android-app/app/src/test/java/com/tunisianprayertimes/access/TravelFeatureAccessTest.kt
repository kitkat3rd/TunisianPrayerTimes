package com.tunisianprayertimes.access

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PlaceResolution
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TravelFeatureAccessTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val locations get() = app.getSystemService(LocationManager::class.java)
    private val notifications get() = app.getSystemService(NotificationManager::class.java)

    @Before fun configureCityUpdates() {
        PrefsManager.markFirstLaunchDone(app)
        PrefsManager.setAutoLocationUpdateEnabled(app, true)
        shadowOf(locations).setLocationEnabled(true)
        shadowOf(notifications).setNotificationsEnabled(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test fun disabledCityUpdatesDoNotRequireLocationOrNotifications() {
        PrefsManager.setAutoLocationUpdateEnabled(app, false)
        shadowOf(locations).setLocationEnabled(false)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(TravelFeatureAccess.issues(app).isEmpty())
    }

    @Test fun approximateAccessIsSufficientAndBackgroundAccessFollowsTheSeparateChoice() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertEquals(listOf(R.string.access_city_location_missing), TravelFeatureAccess.issues(app).map { it.message })
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(TravelFeatureAccess.issues(app).isEmpty())

        PrefsManager.setBackgroundTravelEnabled(app, true)
        assertEquals(listOf(R.string.travel_allow_in_settings), TravelFeatureAccess.issues(app).map { it.message })
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertTrue(TravelFeatureAccess.issues(app).isEmpty())
        shadowOf(locations).setLocationEnabled(false)
        assertEquals(listOf(AccessAction.DEVICE_LOCATION), TravelFeatureAccess.issues(app).map { it.action })
    }

    @Test fun blockedPlaceNotificationsOnlyAffectTheCityChangeAlert() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        notifications.createNotificationChannel(NotificationChannel(
            PlaceResolution.LOCATION_CHANNEL, "City changes", NotificationManager.IMPORTANCE_NONE))
        val onlyIssue = TravelFeatureAccess.issues(app).single()
        assertEquals(R.string.access_city_change_alerts, onlyIssue.feature)
        assertEquals(AccessAction.NOTIFICATIONS, onlyIssue.action)
        assertEquals(PlaceResolution.LOCATION_CHANNEL, onlyIssue.channelId)

        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val appBlock = TravelFeatureAccess.issues(app).single()
        assertEquals(AccessAction.NOTIFICATIONS, appBlock.action)
        assertNull(appBlock.channelId)
    }
}
