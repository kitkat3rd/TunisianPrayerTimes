package com.tunisianprayertimes.mosque

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.PresenceFix
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MosqueArrivalPermissionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun restoringTheEditorReadsOnlyItsOwnLatestPrivateDraft() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val original = AreaDraft(null, "Original", "36, 10")
        val restoration = StateRestorationTester(compose)
        var open: (AreaDraft) -> Unit = {}
        var displayed: AreaDraft? = null
        restoration.setContent {
            var editor by rememberSaveable(stateSaver = activeMosqueDraftSaver(app)) { mutableStateOf<AreaDraft?>(null) }
            SideEffect { displayed = editor; open = { editor = it } }
        }
        val edited = original.copy(name = "Edited", coordinates = "36, 10\n36, 10.001\n36.001, 10")
        compose.runOnIdle { open(original) }
        compose.runOnIdle { PrivateSilenceAreaStore.saveDraft(app, edited) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(edited, displayed) }
        val another = AreaDraft(null, "Another", "")
        compose.runOnIdle {
            assertTrue(PrivateSilenceAreaStore.clearDraft(app, edited.key))
            PrivateSilenceAreaStore.saveDraft(app, another)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(null, displayed)
            assertEquals(another, PrivateSilenceAreaStore.readDraft(app))
        }
    }

    @Test fun approximateWaitsForAnExplicitUpgradeAndSettingsReturnStartsOnlyForegroundReadings() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        shadowOf(app).denyPermissions(fine)
        shadowOf(app).grantPermissions(coarse)
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        var settingsRequest = -1
        val launches = mutableListOf<String>()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                when (contract) {
                    is ActivityResultContracts.RequestMultiplePermissions -> {
                        assertEquals(setOf(coarse, fine), (input as Array<*>).toSet())
                        launches += "permission"
                        dispatchResult(code, mapOf(coarse to true, fine to false))
                    }
                    is ActivityResultContracts.StartActivityForResult -> {
                        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, (input as Intent).action)
                        assertEquals(app.packageName, input.data?.schemeSpecificPart)
                        settingsRequest = code
                        launches += "settings"
                    }
                }
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        var starts = 0
        var stops = 0
        var dismissed = false
        val boundary = SilencePolygon(listOf(GeoPoint(36.0, 10.0), GeoPoint(36.0, 10.001), GeoPoint(36.001, 10.0)))
        compose.runOnUiThread { lifecycle.lifecycle.currentState = Lifecycle.State.RESUMED }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalLifecycleOwner provides lifecycle) {
                MosqueArrivalTestDialog(listOf(boundary), false, readings = {
                    flow<PresenceFix> {
                        starts++
                        try { awaitCancellation() } finally { stops++ }
                    }
                }, onDismiss = { dismissed = true })
            }
        }
        compose.onNodeWithText(app.getString(R.string.mosque_test_approximate)).assertExists()
        compose.onNodeWithText(app.getString(R.string.mosque_test_remaining, 120)).assertDoesNotExist()
        assertEquals(0, starts)
        assertTrue(launches.isEmpty())
        compose.onNodeWithText(app.getString(R.string.mosque_allow_precise)).performClick()
        compose.onNodeWithText(app.getString(R.string.prayer_open_app_settings)).performClick()
        assertEquals(listOf("permission", "settings"), launches)
        compose.runOnIdle {
            lifecycle.lifecycle.currentState = Lifecycle.State.CREATED
            assertFalse(dismissed)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(0, starts)
            shadowOf(app).grantPermissions(fine)
            registry.dispatchResult(settingsRequest, ActivityResult(Activity.RESULT_OK, null))
            lifecycle.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        // Resuming and receiving an activity result invalidate the restored composition.
        // Drain that work, including repeatOnLifecycle's collector, before reading its counters.
        compose.waitForIdle()
        compose.onNodeWithText(app.getString(R.string.mosque_test_waiting)).assertExists()
        assertEquals(1, starts)
        compose.runOnIdle { lifecycle.lifecycle.currentState = Lifecycle.State.CREATED }
        compose.runOnIdle {
            assertEquals(1, stops)
            assertTrue(dismissed)
        }
    }
}
