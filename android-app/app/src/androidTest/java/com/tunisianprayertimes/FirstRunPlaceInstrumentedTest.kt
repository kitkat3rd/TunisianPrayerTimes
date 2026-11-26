package com.tunisianprayertimes

import android.Manifest
import android.content.Context
import android.location.Location
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.tunisianprayertimes.place.PrayerPlace
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class FirstRunPlaceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val compose = createAndroidComposeRule<MainActivity>()
    private val fix = CompletableDeferred<Location?>()
    private val pending = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val prefs = context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
                val saved = prefs.all.toMap()
                prefs.edit().clear().commit()
                PrefsManager.initializeFirstRun(context)
                DelegationLocator.locationProvider = object : DelegationLocationProvider {
                    override suspend fun findCurrentLocation(context: Context, permissionState: LocationPermissionState) = fix.await()
                    override suspend fun findFreshLocation(context: Context, permissionState: LocationPermissionState): Location? = null
                    override suspend fun findRecentLocation(context: Context, permissionState: LocationPermissionState): Location? = null
                }
                try {
                    base.evaluate()
                } finally {
                    DelegationLocator.resetLocationProviderForTests()
                    val restore = prefs.edit().clear()
                    for ((key, value) in saved) {
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
    @get:Rule val rules: RuleChain = RuleChain.outerRule(pending)
        .around(GrantPermissionRule.grant(Manifest.permission.ACCESS_COARSE_LOCATION))
        .around(compose)

    private fun waitFor(tag: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
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
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("onboarding_browse").assertDoesNotExist()
        assertTrue(PrefsManager.isPlaceProvisional(context))
    }

    @Test fun requestedGpsMatchSurvivesRotationUntilConfirmed() {
        fix.complete(tunisFix())
        compose.onNodeWithTag("onboarding_location_locate").performClick()
        waitFor("onboarding_confirm_location")
        assertTrue(PrefsManager.isFirstLaunch(context))
        assertFalse(PrefsManager.getLocationSelection(context).fromGps)
        compose.activityRule.scenario.recreate()
        waitFor("onboarding_confirm_location")
        compose.onNodeWithTag("onboarding_confirm_location").performClick()
        compose.waitUntil(30_000) { !PrefsManager.isFirstLaunch(context) }
        assertTrue(PrefsManager.getLocationSelection(context).fromGps)
        assertFalse(PrefsManager.isPlaceProvisional(context))
        assertFalse(PrefsManager.isAutoLocationUpdateEnabled(context))
    }

    @Test fun citySearchSupersedesPendingFix() {
        compose.onNodeWithTag("onboarding_location_locate").performClick()
        selectRow("Paris", "city_row_2988507")
        fix.complete(tunisFix())
        compose.waitForIdle()
        val place = PrefsManager.getPlace(context) as PrayerPlace.Coordinates
        assertEquals(2988507L, place.catalogueId)
        assertFalse(PrefsManager.getLocationSelection(context).fromGps)
    }

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
