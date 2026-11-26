package com.tunisianprayertimes.ui

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.FirstRunPlaceChoice
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.MosqueSettingsActivity
import com.tunisianprayertimes.mosque.MosqueSilenceSettings
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import com.tunisianprayertimes.place.PrayerPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
class FirstRunSilenceScreenTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val launches = mutableListOf<Intent>()
    private var finishes = 0
    private var pendingCode = 0
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches += input as Intent
            pendingCode = requestCode
        }
    }

    @Before fun chooseCity() {
        app.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("mosque_silence_settings", Context.MODE_PRIVATE).edit().clear().commit()
        PrefsManager.initializeFirstRun(app)
        PrefsManager.completeFirstRun(app, FirstRunPlaceChoice.Browse(PrayerPlace.Inm(615), null))
        PrefsManager.setPlaceProvisional(app, false)
    }

    private fun show(): StateRestorationTester {
        val restoration = StateRestorationTester(compose)
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                FirstRunSilenceScreen { finishes++ }
            }
        }
        return restoration
    }

    @Test fun choosingAModeIsOnlyAPreviewAndCanBeSkippedAfterRotation() {
        val restoration = show()
        compose.onNodeWithTag("onboarding_silence_time").assertIsSelected()
        compose.onNodeWithTag("onboarding_silence_mosque").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("onboarding_silence_mosque").assertIsSelected()
        compose.onNodeWithText(app.getString(R.string.first_silence_decline)).performClick()
        assertEquals(1, finishes)
        assertTrue(launches.isEmpty())
        assertFalse(PrefsManager.isFirstRunSilencePending(app))
        assertFalse(PrefsManager.isAutomaticSilenceRequested(app))
        assertFalse(MosqueSilenceSettings.backgroundDisclosureAccepted(app))
        assertEquals(AutomaticSilenceMode.TIME_ONLY, MosqueSilenceSettings.mode(app))
    }

    @Test fun cancelFromMosqueSetupReturnsToTheChosenModeWithoutEnabling() {
        show()
        compose.onNodeWithTag("onboarding_silence_mosque").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding_silence_setup").performClick()
        val expected = MosqueSettingsActivity.onboardingIntent(app, AutomaticSilenceMode.TIME_AND_MOSQUE)
        assertEquals(expected.component, launches.single().component)
        assertEquals(expected.action, launches.single().action)
        for (key in requireNotNull(expected.extras).keySet()) {
            assertEquals(expected.getStringExtra(key), launches.single().getStringExtra(key))
        }
        compose.runOnIdle { registry.dispatchResult(pendingCode, ActivityResult(Activity.RESULT_CANCELED, null)) }
        compose.onNodeWithText(app.getString(R.string.first_silence_off)).assertExists()
        compose.onNodeWithTag("onboarding_silence_mosque").assertIsSelected()
        assertFalse(PrefsManager.isAutomaticSilenceRequested(app))
        assertEquals(0, finishes)
    }

    @Test fun successfulActivationFinishesDirectlyOnlyWithSavedMatchingIntent() {
        show()
        compose.onNodeWithTag("onboarding_silence_setup").performClick()
        compose.runOnIdle { registry.dispatchResult(pendingCode, ActivityResult(Activity.RESULT_OK, null)) }
        assertEquals(0, finishes)
        compose.onNodeWithTag("onboarding_silence_setup").performClick()
        compose.runOnIdle {
            shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationPolicyAccessGranted(true)
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            PrefsManager.setEnabled(app, true)
            registry.dispatchResult(pendingCode, ActivityResult(Activity.RESULT_OK, null))
        }
        assertEquals(1, finishes)
    }

    @Test fun decliningAnIncompleteSavedRequestTurnsItOffAndOpensTheTimetable() {
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationPolicyAccessGranted(false)
        PrefsManager.setEnabled(app, true)
        val dnd = app.getString(R.string.mosque_needs_dnd)
        show()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(dnd, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(app.getString(R.string.first_silence_resume)).assertExists()
        compose.onNodeWithTag("onboarding_silence_continue").performClick()
        assertEquals(1, finishes)
        assertFalse(PrefsManager.isAutomaticSilenceRequested(app))
        assertFalse(PrefsManager.isFirstRunSilencePending(app))
        assertTrue(launches.isEmpty())
    }

    @Test fun provisionalCityCanDeferWithoutEnablingOrConfirmingIt() {
        PrefsManager.setPlaceProvisional(app, true)
        show()
        compose.onNodeWithTag("onboarding_silence_setup").performClick()
        compose.onNodeWithTag("feature_cancel_place").performClick()
        compose.onNodeWithTag("onboarding_silence_continue").performClick()
        assertTrue(launches.isEmpty())
        assertFalse(PrefsManager.hasConfirmedPlace(app))
        assertFalse(PrefsManager.isAutomaticSilenceRequested(app))
        assertEquals(1, finishes)
    }

    @Test @Config(sdk = [28]) fun androidNineExplainsThatMosqueSilenceIsUnavailable() {
        show()
        compose.onNodeWithTag("onboarding_silence_mosque").assertIsNotSelected().assertIsNotEnabled()
        compose.onNodeWithTag("onboarding_silence_time").assertIsSelected()
        assertTrue(launches.isEmpty())
    }
}
