package com.tunisianprayertimes.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PrayerHeaderGpsActionTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val choice = mutableIntStateOf(1)
    private var locates = 0
    private var manual = 0
    private var settingsRequest = -1
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            if (contract is ActivityResultContracts.RequestMultiplePermissions) {
                dispatchResult(code, mapOf(Manifest.permission.ACCESS_FINE_LOCATION to false, Manifest.permission.ACCESS_COARSE_LOCATION to false))
            } else {
                val intent = input as Intent
                assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
                assertEquals(app.packageName, intent.data?.schemeSpecificPart)
                settingsRequest = code
            }
        }
    }

    private fun tapGpsAndDeny() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                PrayerHeaderGpsAction(choice.intValue, false, false, { locates++ }, { manual++ })
            }
        }
        compose.onNodeWithContentDescription(app.getString(R.string.gps_auto_detect)).performClick()
    }

    private fun openSettings() {
        compose.onNodeWithText(app.getString(R.string.prayer_open_app_settings)).performClick()
        assertTrue(settingsRequest >= 0)
    }

    @Test fun selectingACityAfterDenialStaysPermissionFree() {
        tapGpsAndDeny()
        compose.onNodeWithText(app.getString(R.string.onboarding_location_pick)).performClick()
        assertEquals(1, manual)
        assertEquals(0, locates)
        assertEquals(-1, settingsRequest)
    }

    @Test fun settingsReturnChecksActualApproximateGrantAndResumesTheLookupOnce() {
        tapGpsAndDeny()
        openSettings()
        compose.runOnIdle {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            // Settings commonly returns RESULT_CANCELED even after the grant.
            registry.dispatchResult(settingsRequest, Activity.RESULT_CANCELED, null)
        }
        compose.waitForIdle()
        assertEquals(1, locates)
    }

    @Test fun aNewSelectionWhileAwayWinsOverTheSettingsGrant() {
        tapGpsAndDeny()
        openSettings()
        compose.runOnIdle { choice.intValue++ }
        compose.waitForIdle()
        compose.runOnIdle {
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            registry.dispatchResult(settingsRequest, Activity.RESULT_OK, null)
        }
        compose.waitForIdle()
        assertEquals(0, locates)
    }
}
