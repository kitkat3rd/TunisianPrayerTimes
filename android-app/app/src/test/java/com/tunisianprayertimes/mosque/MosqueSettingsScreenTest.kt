package com.tunisianprayertimes.mosque

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.FirstRunPlaceChoice
import com.tunisianprayertimes.Locality
import com.google.android.gms.common.GooglePlayServicesUtilLight
import com.tunisianprayertimes.R
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.SilenceAccessSettings
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import org.robolectric.shadows.ShadowAlarmManager

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

    private fun openOnboarding(mode: AutomaticSilenceMode, alreadyEnabled: Boolean = false) {
        PrefsManager.initializeFirstRun(context)
        val sfax = Locality("sfax", "صفاقس", "", 1, 386, "صفاقس")
        assertTrue(PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Local(sfax)))
        assertTrue(PrefsManager.isFirstRunSilencePending(context))
        PrefsManager.setEnabled(context, alreadyEnabled)
        val intent = MosqueSettingsActivity.onboardingIntent(context, mode)
        controller = Robolectric.buildActivity(MosqueSettingsActivity::class.java, intent).setup()
        compose.waitForIdle()
    }

    private fun onboardingActivationAvailable(): Boolean =
        compose.onAllNodesWithTag("onboarding_activate_silence").fetchSemanticsNodes()
            .any { SemanticsProperties.Disabled !in it.config }

    private fun assertOpenedDndForThisApp() {
        val grantPage = shadowOf(controller.get()).nextStartedActivity
        assertEquals(SilenceAccessSettings.DND_ACCESS_DETAIL, grantPage?.action)
        assertEquals("package:${context.packageName}", grantPage?.dataString)
    }

    @Test
    fun onboardingTimeOnlyGrantAndRecreationWaitForExplicitActivation() {
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationPolicyAccessGranted(false)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        openOnboarding(AutomaticSilenceMode.TIME_ONLY)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(context.getString(R.string.mosque_allow_exact)).fetchSemanticsNodes().isNotEmpty()
        }
        row(R.string.mosque_allow_precise).assertDoesNotExist()
        row(R.string.mosque_allow_background).assertDoesNotExist()
        assertFalse(onboardingActivationAvailable())
        assertFalse(PrefsManager.isAutomaticSilenceRequested(context))
        assertOpenedDndForThisApp()

        controller.pause().stop().restart().start().resume().visible()
        controller.recreate()
        compose.waitForIdle()
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        row(R.string.mosque_allow_dnd).performScrollTo().performClick()
        assertOpenedDndForThisApp()

        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        controller.recreate()
        compose.waitUntil(10_000) { onboardingActivationAvailable() }
        assertFalse(PrefsManager.isAutomaticSilenceRequested(context))
        assertFalse(controller.get().isFinishing)
        compose.onNodeWithTag("onboarding_activate_silence").performScrollTo().performClick()
        compose.waitUntil(10_000) { controller.get().isFinishing }
        assertEquals(Activity.RESULT_OK, shadowOf(controller.get()).resultCode)
        assertTrue(PrefsManager.isAutomaticSilenceRequested(context))
        assertEquals(AutomaticSilenceMode.TIME_ONLY, MosqueSilenceSettings.mode(context))
        assertFalse(MosqueSilenceSettings.backgroundDisclosureAccepted(context))
    }

    @Test
    fun onboardingMosqueDraftShowsAreaRequirementsAndCancelKeepsSilenceOff() {
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        openOnboarding(AutomaticSilenceMode.TIME_AND_MOSQUE)
        controller.recreate()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(context.getString(R.string.mosque_areas_title)).fetchSemanticsNodes().isNotEmpty()
        }
        row(R.string.mosque_allow_precise).assertExists()
        row(R.string.mosque_allow_background).assertExists()
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        assertFalse(onboardingActivationAvailable())
        assertFalse(MosqueSilenceSettings.usesOwnedRules(context))
        assertFalse(MosqueSilenceSettings.backgroundDisclosureAccepted(context))
        assertEquals(AutomaticSilenceMode.TIME_ONLY, MosqueSilenceSettings.mode(context))
        compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
        assertTrue(controller.get().isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(controller.get()).resultCode)
        assertFalse(PrefsManager.isAutomaticSilenceRequested(context))
        assertEquals(AutomaticSilenceMode.TIME_ONLY, MosqueSilenceSettings.mode(context))
    }

    @Test
    fun missingDndInMosqueSetupOpensSettingsOnceAndCanBeDeclinedImmediately() {
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationPolicyAccessGranted(false)
        openOnboarding(AutomaticSilenceMode.TIME_AND_MOSQUE)
        assertOpenedDndForThisApp()
        controller.pause().stop().restart().start().resume().visible()
        compose.waitForIdle()
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        row(R.string.first_silence_decline).performScrollTo().performClick()
        assertTrue(controller.get().isFinishing)
        assertFalse(PrefsManager.isFirstRunSilencePending(context))
        assertFalse(PrefsManager.isAutomaticSilenceRequested(context))
        assertFalse(MosqueSilenceSettings.backgroundDisclosureAccepted(context))
        assertFalse(MosqueSilenceSettings.usesOwnedRules(context))
    }

    @Test
    fun closingACombinedSetupDraftPreservesAnAlreadyEnabledTimeOnlyChoice() {
        openOnboarding(AutomaticSilenceMode.TIME_AND_MOSQUE, alreadyEnabled = true)
        compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
        assertTrue(controller.get().isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(controller.get()).resultCode)
        assertTrue(PrefsManager.isAutomaticSilenceRequested(context))
        assertEquals(AutomaticSilenceMode.TIME_ONLY, MosqueSilenceSettings.mode(context))
        assertFalse(MosqueSilenceSettings.backgroundDisclosureAccepted(context))
    }

    @Test
    fun staleActivationAfterAnotherWindowSkipsCannotSwitchOnSilence() {
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationPolicyAccessGranted(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        openOnboarding(AutomaticSilenceMode.TIME_ONLY)
        compose.waitUntil(10_000) { onboardingActivationAvailable() }
        // A click already dispatched from a ready screen can arrive after another window commits.
        val activate = requireNotNull(compose.onNodeWithTag("onboarding_activate_silence")
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnIdle {
            PrefsManager.finishFirstRunSilenceOffer(context)
            activate()
        }
        compose.waitUntil(10_000) { controller.get().isFinishing }
        assertEquals(Activity.RESULT_CANCELED, shadowOf(controller.get()).resultCode)
        assertFalse(PrefsManager.isFirstRunSilencePending(context))
        assertFalse(PrefsManager.isAutomaticSilenceRequested(context))
        assertEquals(AutomaticSilenceMode.TIME_ONLY, MosqueSilenceSettings.mode(context))
    }

    @Test
    fun returningToAStaleMosqueDraftKeepsTheTimeOnlyModeEnabledByAnotherWindow() {
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationPolicyAccessGranted(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        openOnboarding(AutomaticSilenceMode.TIME_AND_MOSQUE)
        controller.pause().stop()
        assertTrue(PrefsManager.activateConfiguredSilence(context, AutomaticSilenceMode.TIME_ONLY, firstRun = true))
        controller.restart().start().resume().visible()
        compose.waitUntil(10_000) { controller.get().isFinishing }
        assertEquals(Activity.RESULT_CANCELED, shadowOf(controller.get()).resultCode)
        assertFalse(PrefsManager.isFirstRunSilencePending(context))
        assertTrue(PrefsManager.isAutomaticSilenceRequested(context))
        assertEquals(AutomaticSilenceMode.TIME_ONLY, MosqueSilenceSettings.mode(context))
        assertFalse(MosqueSilenceSettings.backgroundDisclosureAccepted(context))
    }

    @Test
    fun setupLinkOpensRequirementsAndBackReturnsToTheFeatureWithoutEnablingSilence() {
        PrefsManager.markFirstLaunchDone(context)
        PrefsManager.setEnabled(context, false)
        MosqueSilenceSettings.saveMode(context, AutomaticSilenceMode.TIME_AND_MOSQUE)
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationPolicyAccessGranted(false)
        val intent = MosqueSettingsActivity.setupIntent(context, SilenceOwner.WAKE)
        controller = Robolectric.buildActivity(MosqueSettingsActivity::class.java, intent).setup()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(context.getString(R.string.mosque_allow_dnd)).fetchSemanticsNodes().isNotEmpty()
        }
        row(R.string.mosque_readiness_title).assertIsDisplayed()
        row(SilenceOwner.WAKE.title).assertExists()
        row(R.string.mosque_allow_precise).assertDoesNotExist()
        row(R.string.mosque_mode_time).assertDoesNotExist()
        assertFalse(PrefsManager.isEnabled(context))
        compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun disabledWakeRuleLinksToItsAndroidPageWithoutEnablingIt() {
        PrefsManager.markFirstLaunchDone(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        assertTrue(SilenceCoordinator.enrollOrRepair(context))
        val owner = SilenceOwner.WAKE
        val id = manager.automaticZenRules.entries.single { it.value.name == context.getString(owner.title) }.key
        val rule = requireNotNull(manager.getAutomaticZenRule(id))
        rule.isEnabled = false
        manager.updateAutomaticZenRule(id, rule)
        assertEquals(id, SilenceCoordinator.disabledOwnedRuleId(context, owner))
        assertNull(SilenceCoordinator.disabledOwnedRuleId(context, SilenceOwner.PRAYER))
        val intent = MosqueSettingsActivity.setupIntent(context, owner)
        controller = Robolectric.buildActivity(MosqueSettingsActivity::class.java, intent).setup()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(context.getString(R.string.mosque_open_disabled_rule)).fetchSemanticsNodes().isNotEmpty()
        }
        row(R.string.mosque_open_disabled_rule).performScrollTo().performClick()
        val target = shadowOf(controller.get()).nextStartedActivity
        assertEquals(Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS, target.action)
        assertEquals(id, target.getStringExtra(Settings.EXTRA_AUTOMATIC_ZEN_RULE_ID))
        assertFalse(requireNotNull(manager.getAutomaticZenRule(id)).isEnabled)
    }

    @Test
    fun blockedObservationChannelIsReportedOnlyForMosqueModeAndOpensItsOwnSettings() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        assertNull(MosqueReadiness.notificationIssue(context))
        val channel = MosqueObservationService.CHANNEL
        manager.createNotificationChannel(NotificationChannel(channel, "test", NotificationManager.IMPORTANCE_NONE))
        assertEquals(R.string.mosque_observation_notifications_blocked, MosqueReadiness.notificationIssue(context))
        assertFalse(R.string.mosque_observation_notifications_blocked in MosqueReadiness.timeIssues(context))
        MosqueSilenceSettings.saveMode(context, AutomaticSilenceMode.TIME_AND_MOSQUE, backgroundUseExplained = true)
        openSettings()
        row(R.string.mosque_readiness_title).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(context.getString(R.string.mosque_open_observation_notifications))
                .fetchSemanticsNodes().isNotEmpty()
        }
        row(R.string.mosque_open_observation_notifications).performScrollTo().performClick()
        val intent = shadowOf(controller.get()).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(channel, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test
    fun turningOnSilenceInRegularSettingsWaitsForAccessAndConfirmation() {
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        manager.setNotificationPolicyAccessGranted(false)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        PrefsManager.setEnabled(context, false)
        openSettings()
        row(R.string.mosque_enable_auto).performScrollTo().performClick()
        assertFalse(PrefsManager.isAutomaticSilenceRequested(context))
        val intent = shadowOf(controller.get()).nextStartedActivity
        assertEquals(MosqueSettingsActivity.enableIntent(context, AutomaticSilenceMode.TIME_ONLY).action, intent.action)
        controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(MosqueSettingsActivity::class.java, intent).setup()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(context.getString(R.string.mosque_allow_dnd)).fetchSemanticsNodes().isNotEmpty()
        }
        assertOpenedDndForThisApp()
        assertFalse(onboardingActivationAvailable())
        row(R.string.mosque_allow_background).assertDoesNotExist()
        manager.setNotificationPolicyAccessGranted(true)
        controller.recreate()
        compose.waitUntil(10_000) { onboardingActivationAvailable() }
        assertFalse(PrefsManager.isAutomaticSilenceRequested(context))
        compose.onNodeWithTag("onboarding_activate_silence").performScrollTo().performClick()
        compose.waitUntil(10_000) { controller.get().isFinishing }
        assertTrue(PrefsManager.isEnabled(context))
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
