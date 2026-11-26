package com.tunisianprayertimes.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.DelegationLocationProvider
import com.tunisianprayertimes.DelegationLocator
import com.tunisianprayertimes.LocationPermissionState
import com.tunisianprayertimes.R
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
class QiblaLocationAccessTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val fine = Manifest.permission.ACCESS_FINE_LOCATION
    private val coarse = Manifest.permission.ACCESS_COARSE_LOCATION

    @After fun restoreLocationProvider() {
        DelegationLocator.resetLocationProviderForTests()
    }

    @Test fun aGenericVisitDoesNotAskAndTheSameCompassReflectsSettingsGrantsAndRevocation() {
        val prompts = mutableListOf<Set<String>>()
        val permissionRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?,
            ) {
                val requested = (input as Array<*>).filterIsInstance<String>().toSet()
                prompts += requested
                dispatchResult(requestCode, requested.associateWith { false })
            }
        }
        val registryOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = permissionRegistry }
        val fixes = mutableListOf<LocationPermissionState>()
        val coarseFix = CompletableDeferred<Location?>()
        DelegationLocator.locationProvider = object : DelegationLocationProvider {
            override suspend fun findCurrentLocation(context: Context, permissionState: LocationPermissionState): Location? {
                fixes += permissionState
                return if (permissionState.hasFine) fix(8f) else coarseFix.await()
            }
            override suspend fun findFreshLocation(context: Context, permissionState: LocationPermissionState): Location? = null
            override suspend fun findRecentLocation(context: Context, permissionState: LocationPermissionState): Location? = null
        }
        shadowOf(app).denyPermissions(fine, coarse)
        owner.registry.currentState = Lifecycle.State.RESUMED
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalActivityResultRegistryOwner provides registryOwner) {
                QiblaCard(placeRevision = 0, askLocation = false)
            }
        }
        val permissionHint = app.getString(R.string.qibla_location_permission_required)
        compose.onNodeWithText(permissionHint).assertExists()
        assertEquals(0, prompts.size)
        compose.onNodeWithText(permissionHint).performClick()
        assertEquals(listOf(setOf(fine, coarse)), prompts)
        assertEquals(0, fixes.size)

        // The OS may grant, downgrade or revoke from Settings without sending any activity-result callback.
        changeSettings { shadowOf(app).grantPermissions(fine, coarse) }
        val precise = app.getString(R.string.qibla_location_current_with_accuracy, 8f)
        waitFor(precise)
        assertEquals(listOf(LocationPermissionState(true, true)), fixes)
        compose.onNodeWithText(permissionHint).assertDoesNotExist()

        changeSettings { shadowOf(app).denyPermissions(fine) }
        compose.waitUntil(10_000) { fixes.size == 2 }
        compose.onNodeWithText(precise).assertDoesNotExist()
        compose.runOnIdle { coarseFix.complete(fix(180f)) }
        val approximate = app.getString(R.string.qibla_location_current_with_accuracy, 180f)
        waitFor(approximate)

        changeSettings { shadowOf(app).denyPermissions(coarse) }
        waitFor(permissionHint)
        compose.onNodeWithText(approximate).assertDoesNotExist()
        assertEquals(2, fixes.size)
        assertEquals(1, prompts.size)
    }

    private fun changeSettings(change: () -> Unit) {
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle {
            change()
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun fix(accuracyMeters: Float) = Location("test").apply {
        latitude = 36.8
        longitude = 10.18
        accuracy = accuracyMeters
        time = System.currentTimeMillis()
    }
}
