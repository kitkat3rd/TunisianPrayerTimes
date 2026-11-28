package com.tunisianprayertimes.ui

import android.Manifest
import android.app.Application
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.DelegationLocationResult
import com.tunisianprayertimes.FirstRunPlaceChoice
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.ZoneSource
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
class FirstRunPlaceScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "ar-rTN-w320dp-h480dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun narrowRtlScreenAtDoubleFontSizeKeepsAllThreeChoicesReadableAndReachable() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                TunisianPrayerTimesTheme { OnboardingScreen {} }
            }
        }
        val actions = listOf(
            "onboarding_location_pick" to R.string.onboarding_location_pick,
            "onboarding_location_locate" to R.string.first_run_use_location,
            "onboarding_browse" to R.string.first_run_browse,
        )
        for ((tag, resource) in actions) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
            val label = context.getString(resource)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse(
                "Clipped action label: $label, measured=${layout.size}, " +
                    "paragraph=${layout.multiParagraph.width}×${layout.multiParagraph.height}, " +
                    "constraints=${layout.layoutInput.constraints}, lines=${layout.lineCount}",
                layout.hasVisualOverflow,
            )
        }
    }

    @Test fun denialKeepsSearchAndBrowsingAvailableWithoutAnotherPermissionRequest() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val requested = mutableListOf<Set<String>>()
        lateinit var denyRequest: () -> Unit
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?,
            ) {
                val permissions = (input as Array<*>).filterIsInstance<String>().toSet()
                requested += permissions
                denyRequest = { dispatchResult(requestCode, permissions.associateWith { false }) }
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val keyboard = object : InputModeManager {
            override val inputMode = InputMode.Keyboard
            override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
        }
        compose.setContent {
            val window = LocalWindowInfo.current
            val focusedWindow = remember(window) {
                object : WindowInfo by window { override val isWindowFocused = true }
            }
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides owner,
                LocalInputModeManager provides keyboard,
                LocalWindowInfo provides focusedWindow,
            ) { OnboardingScreen {} }
        }
        val opening = compose.onNodeWithText(context.getString(R.string.first_run_place_title)).fetchSemanticsNode()
        assertTrue(opening.config.contains(SemanticsProperties.Heading))
        assertFalse(opening.config.contains(SemanticsProperties.LiveRegion))
        compose.onNodeWithTag("onboarding_location_pick").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_browse").assertIsDisplayed()
        assertTrue(requested.isEmpty())
        val locationAction = compose.onNodeWithTag("onboarding_location_locate")
        val launchingNodeId = locationAction.fetchSemanticsNode().id
        locationAction.performClick()
        locationAction.assertIsEnabled().performClick()
        compose.runOnIdle { denyRequest() }
        val recovery = compose.onNodeWithText(context.getString(R.string.first_run_denied_title))
            .assertIsDisplayed().fetchSemanticsNode()
        assertTrue(recovery.config.contains(SemanticsProperties.Heading))
        assertEquals(LiveRegionMode.Polite, recovery.config[SemanticsProperties.LiveRegion])
        val recoveryAction = compose.onNodeWithTag("onboarding_location_pick").assertIsEnabled().assertIsFocused()
        assertEquals("TalkBack can restore the original action after the system dialog", launchingNodeId, recoveryAction.fetchSemanticsNode().id)
        compose.onNodeWithTag("onboarding_browse").assertIsEnabled()
        compose.onNodeWithTag("onboarding_location_locate").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_location_pick").performClick()
        compose.onNodeWithTag("locality_search").assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).performSemanticsAction(SemanticsActions.Dismiss)
        compose.onNodeWithTag("locality_search").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_location_pick").assertIsFocused()
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
