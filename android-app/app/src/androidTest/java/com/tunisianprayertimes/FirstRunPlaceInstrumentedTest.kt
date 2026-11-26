package com.tunisianprayertimes

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.location.Location
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.tunisianprayertimes.mosque.MosqueSettingsActivity
import com.tunisianprayertimes.mosque.MosqueSilenceSettings
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.ui.TestTags
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
private annotation class NeedsCoarseLocation

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
private annotation class AfterCitySelection(val cityPickerLink: Boolean = false)

@RunWith(AndroidJUnit4::class)
class FirstRunPlaceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val fix = CompletableDeferred<Location?>()
    private val locationReads = AtomicInteger()
    private val permissionLaunches = CopyOnWriteArrayList<String>()
    private val pending = TestRule { base, description ->
        object : Statement() {
            override fun evaluate() {
                val stores = listOf("prayer_silence_prefs", "mosque_silence_settings").associateWith {
                    context.getSharedPreferences(it, Context.MODE_PRIVATE)
                }
                val saved = stores.values.associateWith { it.all.toMap() }
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val permissionMonitor = object : Instrumentation.ActivityMonitor() {
                    override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                        val action = intent.action ?: return null
                        if (action != "android.content.pm.action.REQUEST_PERMISSIONS" && !action.startsWith("android.settings.")) return null
                        permissionLaunches += action
                        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    }
                }
                instrumentation.addMonitor(permissionMonitor)
                try {
                    stores.values.forEach { check(it.edit().clear().commit()) }
                    // Initialize the real first-run flags. Other features on a tester's phone can contain
                    // restored data; give the migration an empty view of those stores without deleting them.
                    val cleanProfile = object : ContextWrapper(context) {
                        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                            stores[name] ?: super.getSharedPreferences("first_run_instrumented_empty", mode)
                        override fun getFilesDir() = File(cacheDir, "first_run_instrumented_empty")
                    }
                    PrefsManager.initializeFirstRun(cleanProfile)
                    check(PrefsManager.isFirstRunSilencePending(context))
                    if (description.getAnnotation(AfterCitySelection::class.java) != null) {
                        val tunis = PrayerPlace.Inm(615)
                        check(PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Browse(tunis, null)))
                        check(PrefsManager.confirmSuggestedPlace(context, tunis))
                    }
                    DelegationLocator.locationProvider = object : DelegationLocationProvider {
                        override suspend fun findCurrentLocation(context: Context, permissionState: LocationPermissionState): Location? {
                            locationReads.incrementAndGet()
                            return fix.await()
                        }
                        override suspend fun findFreshLocation(context: Context, permissionState: LocationPermissionState): Location? {
                            locationReads.incrementAndGet()
                            return null
                        }
                        override suspend fun findRecentLocation(context: Context, permissionState: LocationPermissionState): Location? {
                            locationReads.incrementAndGet()
                            return null
                        }
                    }
                    base.evaluate()
                } finally {
                    instrumentation.removeMonitor(permissionMonitor)
                    DelegationLocator.resetLocationProviderForTests()
                    for ((prefs, snapshot) in saved) {
                        val restore = prefs.edit().clear()
                        for ((key, value) in snapshot) {
                            when (value) {
                                is Boolean -> restore.putBoolean(key, value)
                                is Int -> restore.putInt(key, value)
                                is Long -> restore.putLong(key, value)
                                is Float -> restore.putFloat(key, value)
                                is String -> restore.putString(key, value)
                                is Set<*> -> restore.putStringSet(key, value.filterIsInstance<String>().toSet())
                            }
                        }
                        check(restore.commit())
                    }
                }
            }
        }
    }
    private val locationGrant = TestRule { base, description ->
        if (description.getAnnotation(NeedsCoarseLocation::class.java) == null) base
        else GrantPermissionRule.grant(Manifest.permission.ACCESS_COARSE_LOCATION).apply(base, description)
    }
    private val launch = TestRule { base, description ->
        object : Statement() {
            override fun evaluate() {
                scenario = if (description.getAnnotation(AfterCitySelection::class.java)?.cityPickerLink == true) {
                    // Cold start with the same destination/action as the travel notification.
                    val intent = Intent(context, MainActivity::class.java)
                        .setData(Uri.parse(MainActivityPendingIntents.CHOOSE_PLACE_URI))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_PRAYERS)
                        .putExtra(MainTabNavigation.EXTRA_PRAYERS_ACTION, MainTabNavigation.PRAYERS_ACTION_CHOOSE_PLACE)
                    ActivityScenario.launch<MainActivity>(intent)
                } else {
                    ActivityScenario.launch(MainActivity::class.java)
                }
                scenario.use { base.evaluate() }
            }
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(pending)
        .around(locationGrant)
        .around(compose)
        .around(launch)

    private fun waitFor(tag: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun tapSilenceAction(tag: String) {
        val action = compose.onNodeWithTag(tag)
        if (!action.isDisplayed()) action.performScrollTo()
        action.assertIsDisplayed().performClick()
    }

    private fun selectRow(query: String, tag: String) {
        compose.onNodeWithTag("onboarding_location_pick").performClick()
        compose.onNodeWithTag("locality_search").performTextInput(query)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        waitFor(tag)
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(30_000) { !PrefsManager.isFirstLaunch(context) }
    }

    private fun tunisFix() = Location("test").apply {
        latitude = 36.8428
        longitude = 10.1465
        time = System.currentTimeMillis()
        accuracy = 30f
    }

    @Test fun browseKeepsPlaceProvisionalAcrossRecreation() {
        compose.onNodeWithTag("onboarding_browse").performClick()
        compose.waitUntil(30_000) { !PrefsManager.isFirstLaunch(context) }
        waitFor("onboarding_silence")
        compose.onNodeWithTag("onboarding_silence_time").assertIsSelected()
        scenario.recreate()
        waitFor("onboarding_silence")
        compose.onNodeWithTag("onboarding_browse").assertDoesNotExist()
        assertTrue(PrefsManager.isPlaceProvisional(context))

        tapSilenceAction("onboarding_silence_setup")
        waitFor("feature_cancel_place")
        val automation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("feature_cancel_place").fetchSemanticsNodes().isEmpty() }
        assertTrue(PrefsManager.isPlaceProvisional(context))
        assertFalse(PrefsManager.isAutomaticSilenceRequested(context))
        tapSilenceAction("onboarding_silence_continue")
        waitFor(TestTags.LOCATION_PICKER)
        assertFalse(PrefsManager.isFirstRunSilencePending(context))
        scenario.recreate()
        waitFor(TestTags.LOCATION_PICKER)
        compose.onNodeWithTag("onboarding_silence").assertDoesNotExist()
        assertTrue(PrefsManager.isPlaceProvisional(context))
        assertFalse(PrefsManager.isAutomaticSilenceRequested(context))
        assertFalse(MosqueSilenceSettings.backgroundDisclosureAccepted(context))
        assertFalse(PrefsManager.isAutoLocationUpdateEnabled(context))
        assertEquals(0, locationReads.get())
        assertTrue("Unexpected system request: $permissionLaunches", permissionLaunches.isEmpty())
    }

    @AfterCitySelection
    @Test fun confirmedCityOpensUnfinishedSilenceOfferAndRoutesOnlyTheChosenSetup() {
        // This activity has no saved bundle; the place was confirmed by an earlier launch.
        waitFor("onboarding_silence")
        compose.onNodeWithTag("onboarding_location_pick").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_silence_time").assertIsSelected()
        val modes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AutomaticSilenceMode.entries
        } else {
            listOf(AutomaticSilenceMode.TIME_ONLY)
        }
        val launches = CopyOnWriteArrayList<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className != MosqueSettingsActivity::class.java.name) return null
                launches += Intent(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(monitor)
        try {
            modes.forEachIndexed { index, mode ->
                val tag = if (mode == AutomaticSilenceMode.TIME_ONLY) "onboarding_silence_time" else "onboarding_silence_mosque"
                compose.onNodeWithTag(tag).performScrollTo().performClick().assertIsSelected()
                if (mode == AutomaticSilenceMode.TIME_AND_MOSQUE) {
                    scenario.recreate()
                    waitFor("onboarding_silence")
                    compose.onNodeWithTag(tag).assertIsSelected()
                }
                assertEquals(index, launches.size)
                assertFalse(PrefsManager.isAutomaticSilenceRequested(context))
                tapSilenceAction("onboarding_silence_setup")
                compose.waitUntil(10_000) { launches.size == index + 1 }
                val expected = MosqueSettingsActivity.onboardingIntent(context, mode)
                assertEquals(expected.action, launches[index].action)
                assertEquals(mode.name, launches[index].getStringExtra("onboarding_mode"))
                waitFor("onboarding_silence_status")
                assertTrue(PrefsManager.isFirstRunSilencePending(context))
                assertFalse(PrefsManager.isAutomaticSilenceRequested(context))
                assertFalse(MosqueSilenceSettings.backgroundDisclosureAccepted(context))
            }
            tapSilenceAction("onboarding_silence_continue")
            waitFor(TestTags.LOCATION_PICKER)
            assertFalse(PrefsManager.isFirstRunSilencePending(context))
            assertEquals(0, locationReads.get())
            assertTrue("Unexpected system request: $permissionLaunches", permissionLaunches.isEmpty())
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @AfterCitySelection
    @Test fun editingASavedPrayerRuleAfterOptOutKeepsAutomaticSilencingOff() {
        waitFor("onboarding_silence")
        tapSilenceAction("onboarding_silence_continue")
        waitFor("today_time_FAJR")
        compose.onNodeWithTag("today_time_FAJR").performScrollTo().performClick()
        val inclusion = compose.onNodeWithContentDescription(
            context.getString(R.string.prayer_silence_switch_desc, context.getString(R.string.prayer_fajr)),
        )
        inclusion.performScrollTo().assertIsOn().performClick().assertIsOff()
        inclusion.performClick().assertIsOn()
        assertTrue(PrefsManager.isPrayerSilenceEnabled(context, Prayer.FAJR))
        assertFalse(PrefsManager.isAutomaticSilenceRequested(context))
        assertTrue("Unexpected system request: $permissionLaunches", permissionLaunches.isEmpty())
    }

    @AfterCitySelection(cityPickerLink = true)
    @Test fun travelCityLinkDefersTheSilenceOfferOnlyForThatPrayerVisit() {
        waitFor("locality_search")
        compose.onNodeWithTag("locality_search").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_silence").assertDoesNotExist()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("locality_search").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag(TestTags.LOCATION_PICKER).assertExists()
        compose.onNodeWithTag("onboarding_silence").assertDoesNotExist()
        compose.onNodeWithTag(TestTags.MAIN_TAB_ALARMS).performClick()
        compose.onNodeWithTag(TestTags.LOCATION_PICKER).assertDoesNotExist()
        assertTrue(PrefsManager.isFirstRunSilencePending(context))
        compose.onNodeWithTag(TestTags.MAIN_TAB_TODAY).performClick()
        waitFor("onboarding_silence")
        compose.onNodeWithTag("locality_search").assertDoesNotExist()
        assertFalse(PrefsManager.isAutomaticSilenceRequested(context))
        assertEquals(0, locationReads.get())
        assertTrue("Unexpected system request: $permissionLaunches", permissionLaunches.isEmpty())
    }

    @NeedsCoarseLocation
    @Test fun requestedGpsMatchSurvivesRotationUntilConfirmed() {
        fix.complete(tunisFix())
        compose.onNodeWithTag("onboarding_location_locate").performClick()
        waitFor("onboarding_confirm_location")
        assertTrue(PrefsManager.isFirstLaunch(context))
        assertFalse(PrefsManager.getLocationSelection(context).fromGps)
        scenario.recreate()
        waitFor("onboarding_confirm_location")
        compose.onNodeWithTag("onboarding_confirm_location").performClick()
        compose.waitUntil(30_000) { !PrefsManager.isFirstLaunch(context) }
        assertTrue(PrefsManager.getLocationSelection(context).fromGps)
        assertFalse(PrefsManager.isPlaceProvisional(context))
        assertFalse(PrefsManager.isAutoLocationUpdateEnabled(context))
    }

    @NeedsCoarseLocation
    @Test fun citySearchSupersedesPendingFix() {
        compose.onNodeWithTag("onboarding_location_locate").performClick()
        selectRow("Paris", "city_row_2988507")
        fix.complete(tunisFix())
        compose.waitForIdle()
        val place = PrefsManager.getPlace(context) as PrayerPlace.Coordinates
        assertEquals(2988507L, place.catalogueId)
        assertFalse(PrefsManager.getLocationSelection(context).fromGps)
    }

    @NeedsCoarseLocation
    @Test fun unavailableFixAllowsRetryAndManualLocalitySelection() {
        fix.complete(null)
        compose.onNodeWithTag("onboarding_location_locate").performClick()
        val retry = context.getString(R.string.first_run_retry_location)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(retry).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(retry).assertIsEnabled()
        selectRow("المرسى الشاطئ", "locality_row_osm:relation:7062337")
        assertEquals("osm:relation:7062337", PrefsManager.getLocationSelection(context).localityId)
        assertFalse(PrefsManager.isPlaceProvisional(context))
    }
}
