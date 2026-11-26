package com.tunisianprayertimes.mosque

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.common.GooglePlayServicesUtilLight
import com.tunisianprayertimes.R
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MosqueSettingsScreenTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var controller: ActivityController<MosqueSettingsActivity>
    private lateinit var apkIndependent: AtomicBoolean
    private var previousApkIndependent = false

    @Before
    fun useHostContextForPlayServicesCheck() {
        // Play Services 18.9 exposes the setter for non-APK hosts, but no inverse. Save its
        // flag so the SDK's normal manifest validation is restored for the next test.
        val flag = GooglePlayServicesUtilLight::class.java.getDeclaredField("zzc")
        flag.isAccessible = true
        apkIndependent = flag.get(null) as AtomicBoolean
        previousApkIndependent = apkIndependent.get()
        GooglePlayServicesUtilLight.enableUsingApkIndependentContext()
    }

    private fun openSettings() {
        PrefsManager.markFirstLaunchDone(context)
        controller = Robolectric.buildActivity(MosqueSettingsActivity::class.java).setup()
        compose.waitForIdle()
    }

    @After
    fun closeSettings() {
        try {
            if (::controller.isInitialized) controller.pause().stop().destroy()
        } finally {
            if (::apkIndependent.isInitialized) apkIndependent.set(previousApkIndependent)
        }
    }

    private fun row(label: Int) = compose.onNodeWithText(context.getString(label))

    @Test
    fun activatingTimeOnlyWithMissingAccessShowsOnlyItsOwnRequirements() {
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationPolicyAccessGranted(false)
        PrefsManager.setEnabled(context, false)
        openSettings()
        assertFalse(PrefsManager.isEnabled(context))
        row(R.string.mosque_enable_auto).performScrollTo().performClick()
        assertTrue(PrefsManager.isEnabled(context))
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(context.getString(R.string.mosque_allow_dnd)).fetchSemanticsNodes().isNotEmpty()
        }
        row(R.string.mosque_allow_dnd).assertExists()
        row(R.string.mosque_allow_background).assertDoesNotExist()
    }

    @Test
    fun provisionalPlaceLeavesAutomaticSilenceOff() {
        PrefsManager.setEnabled(context, false)
        PrefsManager.setPlaceProvisional(context, true)
        openSettings()
        row(R.string.mosque_enable_auto).performScrollTo().performClick()
        row(R.string.prayer_feature_place_required).assertExists()
        assertFalse(PrefsManager.isEnabled(context))
        assertFalse(MosqueSilenceSettings.backgroundDisclosureAccepted(context))
    }

    @Test
    fun timeOnlyShowsBothMethodsAndProvidesAccessToSavedAreas() {
        openSettings()

        row(R.string.mosque_mode_time).assertIsDisplayed().assertIsSelected()
        row(R.string.mosque_mode_combined).assertIsNotSelected()
            .assertTextContains(context.getString(R.string.mosque_method_arrival_detail), substring = true)
        row(R.string.mosque_add_area).assertDoesNotExist()
        savePreview("overview")

        row(R.string.mosque_areas_title).performScrollTo().performClick()
        row(R.string.mosque_add_area).performScrollTo().assertIsDisplayed()
        savePreview("areas")
        compose.onNodeWithContentDescription(context.getString(R.string.mosque_back)).performClick()

        row(R.string.mosque_mode_time).performScrollTo().assertIsSelected()
        assertEquals(AutomaticSilenceMode.TIME_ONLY, MosqueSilenceSettings.mode(context))
    }

    @Test
    fun leavingSetupDiscardsThePendingMethodAndItsBackgroundDisclosure() {
        val notifications = context.getSystemService(NotificationManager::class.java)
        shadowOf(notifications).setNotificationPolicyAccessGranted(false)
        openSettings()
        row(R.string.mosque_mode_combined).performScrollTo().performClick()

        compose.onNode(isDialog()).assertDoesNotExist()
        row(R.string.mosque_readiness_title).assertIsDisplayed()
        row(R.string.mosque_mode_time).assertDoesNotExist()
        row(R.string.mosque_allow_dnd).assertExists()
        savePreview("setup")
        assertEquals(AutomaticSilenceMode.TIME_ONLY, MosqueSilenceSettings.mode(context))
        assertFalse(MosqueSilenceSettings.usesOwnedRules(context))
        assertFalse(MosqueSilenceSettings.backgroundDisclosureAccepted(context))

        compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
        row(R.string.mosque_mode_time).performScrollTo().assertIsSelected()
        assertEquals(AutomaticSilenceMode.TIME_ONLY, MosqueSilenceSettings.mode(context))
        assertFalse(MosqueSilenceSettings.backgroundDisclosureAccepted(context))
        row(R.string.mosque_readiness_title).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(context.getString(R.string.mosque_allow_dnd)).fetchSemanticsNodes().isNotEmpty()
        }
        row(R.string.mosque_allow_dnd).performScrollTo().assertIsDisplayed()
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        row(R.string.mosque_prepare_rules).performScrollTo().performClick()
        assertTrue(MosqueSilenceSettings.usesOwnedRules(context))
        assertEquals(AutomaticSilenceMode.TIME_ONLY, MosqueSilenceSettings.mode(context))
        assertFalse(MosqueSilenceSettings.backgroundDisclosureAccepted(context))

        compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
        row(R.string.mosque_mode_combined).performScrollTo().performClick().assertIsSelected()
        assertTrue(MosqueSilenceSettings.backgroundDisclosureAccepted(context))
    }

    @Test
    fun preparingRulesAfterGrantingAccessCompletesThePendingMosqueSelection() {
        val notifications = context.getSystemService(NotificationManager::class.java)
        shadowOf(notifications).setNotificationPolicyAccessGranted(false)
        openSettings()
        row(R.string.mosque_mode_combined).performScrollTo().performClick()
        row(R.string.mosque_setup_enrollment_failed).assertIsDisplayed()
        assertEquals(AutomaticSilenceMode.TIME_ONLY, MosqueSilenceSettings.mode(context))
        assertFalse(MosqueSilenceSettings.backgroundDisclosureAccepted(context))

        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        row(R.string.mosque_prepare_rules).performScrollTo().performClick()

        row(R.string.mosque_settings_title).assertIsDisplayed()
        row(R.string.mosque_mode_combined).assertIsSelected()
        assertTrue(MosqueSilenceSettings.usesOwnedRules(context))
        assertEquals(AutomaticSilenceMode.TIME_AND_MOSQUE, MosqueSilenceSettings.mode(context))
        assertTrue(MosqueSilenceSettings.backgroundDisclosureAccepted(context))
    }

    @Test
    fun oneTapSelectsTheMosqueMethodWithArrivalDetectionAndSavesTheChoice() {
        assertTrue(MosqueSilenceSettings.enroll(context))
        openSettings()
        row(R.string.mosque_mode_time).assertIsSelected()
        assertFalse(MosqueSilenceSettings.backgroundDisclosureAccepted(context))
        row(R.string.mosque_mode_combined).performScrollTo().performClick().assertIsSelected()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(AutomaticSilenceMode.TIME_AND_MOSQUE, MosqueSilenceSettings.mode(context))
        assertTrue(MosqueSilenceSettings.backgroundDisclosureAccepted(context))

        controller.recreate()
        row(R.string.mosque_mode_combined).assertIsSelected()
        row(R.string.mosque_mode_time).assertIsNotSelected()
        row(R.string.mosque_method_arrival_detail).assertExists()
        compose.onNodeWithText("تفعيل التعرّف على الوصول").assertDoesNotExist()
        compose.onNodeWithText("التعرّف على الوصول مفعّل").assertDoesNotExist()
        assertTrue(MosqueSilenceSettings.backgroundDisclosureAccepted(context))
        savePreview("combined")
        row(R.string.mosque_areas_title).performScrollTo().performClick()
        row(R.string.mosque_add_area).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
        row(R.string.mosque_mode_time).performScrollTo().performClick().assertIsSelected()
        assertEquals(AutomaticSilenceMode.TIME_ONLY, MosqueSilenceSettings.mode(context))
        row(R.string.mosque_mode_combined).performScrollTo().performClick().assertIsSelected()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertTrue(MosqueSilenceSettings.combined(context))
    }

    @Test
    fun anExistingMosqueSelectionShowsTheSameArrivalMethodWithoutAnUpgradePrompt() {
        assertTrue(MosqueSilenceSettings.enroll(context))
        assertTrue(MosqueSilenceSettings.saveMode(context, AutomaticSilenceMode.TIME_AND_MOSQUE))
        MosqueSilenceSettings.acceptBackgroundDisclosure(context)
        openSettings()

        row(R.string.mosque_mode_combined).assertIsSelected()
            .assertTextEquals(
                context.getString(R.string.mosque_mode_combined),
                context.getString(R.string.mosque_method_arrival_detail),
            )
        compose.onNode(isDialog()).assertDoesNotExist()
        assertTrue(MosqueSilenceSettings.combined(context))
        assertTrue(MosqueSilenceSettings.backgroundDisclosureAccepted(context))
    }

    @Test
    @Config(qualifiers = "ar-rTN-w320dp-h740dp-xhdpi")
    fun methodNamesAndDescriptionsWrapFullyAtLargeTextSize() {
        RuntimeEnvironment.setFontScale(1.5f)
        openSettings()
        savePreview("overview-font-150")

        val labels = listOf(R.string.mosque_mode_time, R.string.mosque_method_time_detail,
            R.string.mosque_mode_combined, R.string.mosque_method_arrival_detail)
        labels.forEach { id ->
            val label = context.getString(id)
            val node = compose.onNodeWithText(label, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse(label, layout.didOverflowHeight)
            assertTrue(label, (0 until layout.lineCount).none { layout.isLineEllipsized(it) })
            assertEquals(label, label.length, layout.getLineEnd(layout.lineCount - 1))
        }
        row(R.string.mosque_areas_title).performScrollTo().performClick()
        row(R.string.mosque_add_area).performScrollTo().assertIsDisplayed()
    }

    private fun savePreview(name: String) {
        val loadingLabels = listOf(R.string.mosque_phone_status_loading, R.string.mosque_setup_checking)
            .map(context::getString)
        compose.waitUntil(timeoutMillis = 20_000) {
            loadingLabels.all { compose.onAllNodesWithText(it).fetchSemanticsNodes().isEmpty() }
        }
        val bitmap = compose.runOnIdle {
            val view = controller.get().window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val output = File("build/outputs/mosque-settings-preview/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
