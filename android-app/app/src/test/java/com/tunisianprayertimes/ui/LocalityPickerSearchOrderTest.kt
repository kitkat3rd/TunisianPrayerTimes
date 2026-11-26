package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.CitySearch
import com.tunisianprayertimes.GouvernoratRepository
import com.tunisianprayertimes.LocalityPickerCatalog
import com.tunisianprayertimes.LocalityRepository
import com.tunisianprayertimes.PlaceCatalogue
import com.tunisianprayertimes.PlaceResolution
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.place.CityRef
import com.tunisianprayertimes.place.LatLngE5
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The picker orders its sections by match quality, wherever the place is (owner, 2026-10-19). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
class LocalityPickerSearchOrderTest {
    @get:Rule val compose = createComposeRule()

    private fun tagged(prefix: String) = SemanticsMatcher("test tag starting with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    @Test
    fun switchingFromMarlyToLondonUpdatesTheVisibleHeaderAndPickerSelection() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val catalogue = PlaceCatalogue.get(context)
        fun saveCity(id: Long) {
            val row = requireNotNull(catalogue.byId(id))
            PrefsManager.setPlace(context, PlaceResolution.placeFromCatalogue(context, row),
                PlaceResolution.catalogueLabel(row), fromGps = false)
        }
        saveCity(2995656L) // Marly near Valenciennes, France.
        compose.setContent { LocationPickerCard(onDelegationSelected = {}) }
        compose.mainClock.advanceTimeBy(3_100)
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("فالنسيان", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Marly").assertTextContains(CitySearch.countryName("FR"), substring = true)

        compose.runOnIdle { saveCity(2643743L) }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("لندن") and hasText(CitySearch.countryName("GB"), substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Marly").assertDoesNotExist()
        compose.onNodeWithText("فالنسيان", substring = true).assertDoesNotExist()
        compose.onNodeWithText(CitySearch.countryName("FR"), substring = true).assertDoesNotExist()
        compose.onNodeWithText("لندن").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("locality_search").performTextInput("London")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("city_row_2643743")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("city_row_2643743").assertIsSelected()
    }

    @Test
    fun jordanianNamesakesAnnounceTheirGovernoratesAndSelectTheChosenCity() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val cities = PlaceCatalogue.get(context).pickerIndex
        var selectedId: Long? = null
        compose.setContent {
            LocalityPickerSheet(
                catalog = LocalityPickerCatalog.empty, gouvernorats = emptyList(), selectedId = "",
                onDismiss = {}, onSelect = {}, cities = cities, onSelectCity = { selectedId = it.ref.id },
            )
        }
        val name = "الحسينية"
        compose.onNodeWithTag("locality_search").performTextInput(name)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(tagged("city_row_") and hasText(name)).fetchSemanticsNodes().isNotEmpty()
        }
        val results = compose.onNodeWithTag("locality_list")
        results.performScrollToNode(hasTestTag("city_row_250662"))
        compose.onNodeWithTag("city_row_250662").assertTextContains(name).assertTextContains("الكرك", substring = true)
        results.performScrollToNode(hasTestTag("city_row_7015781"))
        compose.onNodeWithTag("city_row_7015781").assertTextContains(name).assertTextContains("معان", substring = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(7015781L, selectedId) }
    }

    @Test
    fun anExactCityAbroadComesBeforeATunisianPlaceFoundOnlyAsATypo() {
        // «القاهرة» is two letters from Manouba's «الزهرة», which the Tunisian search finds as a typo; Cairo is the whole name.
        val context = ApplicationProvider.getApplicationContext<Application>()
        val catalog = LocalityRepository.preparePicker(context, GouvernoratRepository.loadAllDelegations(context))
        val cairo = PlaceCatalogue.Row(
            CityRef(360630, LatLngE5.of(30.06263, 31.24967), "EG", "Africa/Cairo", population = 9_606_916), "Cairo", "", "القاهرة", emptyList(), 23,
        )
        compose.setContent {
            LocalityPickerSheet(
                catalog = catalog, gouvernorats = GouvernoratRepository.loadAll(context), selectedId = "",
                onDismiss = {}, onSelect = {}, cities = CitySearch.Index(listOf(cairo)),
            )
        }
        compose.onNodeWithTag("locality_search").performTextInput("القاهرة")
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(tagged("city_row_360630")).fetchSemanticsNodes().isNotEmpty() }
        val tunisian = compose.onAllNodes(tagged("locality_row_")).fetchSemanticsNodes()
        assertTrue("the typo match is still listed, after Cairo", tunisian.isNotEmpty())
        val cairoTop = compose.onNodeWithTag("city_row_360630").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Cairo first", tunisian.all { it.boundsInRoot.top > cairoTop })
    }
}
