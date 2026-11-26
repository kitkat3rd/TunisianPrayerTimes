package com.tunisianprayertimes.ui

import android.Manifest
import android.app.Application
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.DelegationLocationResult
import com.tunisianprayertimes.FirstRunPlaceChoice
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.ZoneSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
class FirstRunPlaceScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun denialKeepsSearchAndBrowsingAvailableWithoutAnotherPermissionRequest() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val requested = mutableListOf<Set<String>>()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?,
            ) {
                val permissions = (input as Array<*>).filterIsInstance<String>().toSet()
                requested += permissions
                dispatchResult(requestCode, permissions.associateWith { false })
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { OnboardingScreen {} }
        }
        compose.onNodeWithTag("onboarding_location_pick").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_browse").assertIsDisplayed()
        assertTrue(requested.isEmpty())
        compose.onNodeWithTag("onboarding_location_locate").performClick()
        compose.onNodeWithText(context.getString(R.string.first_run_denied_title)).assertIsDisplayed()
        compose.onNodeWithTag("onboarding_location_pick").assertIsEnabled()
        compose.onNodeWithTag("onboarding_browse").assertIsEnabled()
        compose.onNodeWithTag("onboarding_location_locate").assertDoesNotExist()
        assertEquals(listOf(setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)), requested)
        assertTrue(PrefsManager.isFirstLaunch(context))
    }

    @Test fun restoringAnOverseasMatchKeepsItsZoneAndCalculationInputs() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val place = PrayerPlace.Coordinates(
            latE5 = 2486000, lngE5 = 4668000, elevationM = 612, zoneId = "Asia/Riyadh",
            zoneSource = ZoneSource.NEAREST_CITY, countryCode = "SA", admin1 = "01", admin2 = "0101",
        )
        val result = DelegationLocationResult.Abroad(place, "قرب الرياض")
        val bytes = ByteArrayOutputStream().apply {
            ObjectOutputStream(this).use { it.writeObject(result.firstRunMatch()) }
        }
        val restored = ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() as FirstRunPlace.Match }
        assertEquals(FirstRunPlaceChoice.Abroad(place, result.label, fromGps = true), restored.resolve(context))
        assertTrue(PrefsManager.isFirstLaunch(context))
    }
}
