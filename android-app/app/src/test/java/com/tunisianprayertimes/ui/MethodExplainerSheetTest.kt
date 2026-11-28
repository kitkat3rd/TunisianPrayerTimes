package com.tunisianprayertimes.ui

import android.app.Application
import android.graphics.Insets
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.LayoutDirection
import com.tunisianprayertimes.MethodPresentation
import com.tunisianprayertimes.method.PrayerMethodChoice
import com.tunisianprayertimes.method.PrayerMethods
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.ZoneSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w393dp-h873dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MethodExplainerSheetTest {
    @get:Rule val compose = createComposeRule()

    private val method = checkNotNull(PrayerMethods.byId("ecfr_90"))
    private val credit = "GeoNames (CC BY 4.0)، والأسماء العربية الناقصة من Wikidata (CC0)"

    @Test
    fun sourceIsArabicAndStillDisclosesTheUnverifiedAttribution() {
        open()

        compose.onNodeWithText(MethodPresentation.source(method)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(method.evidence.source).assertDoesNotExist()
        compose.onNodeWithText(credit).performScrollTo().assertIsDisplayed()
    }

    @Test(timeout = 60_000)
    fun tallStatusBarAndBottomFlingDoNotMakeTheSheetBobOrClipTheFooter() {
        var dismissed = false
        open(onDismiss = { dismissed = true })
        // The annual chart loads above the footer on a background thread, outside the Compose clock.
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("twilight_year_strip").fetchSemanticsNodes().isNotEmpty() }
        // Semantics scrolling animates and waits for completion, so it needs the running test clock.
        compose.onNodeWithText(credit).performScrollTo()
        compose.mainClock.autoAdvance = false
        val insets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 80, 0, 0))
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 24))
            .setVisible(WindowInsets.Type.systemBars(), true)
            .build()
        compose.runOnUiThread {
            val roots = WindowInspector.getGlobalWindowViews()
            assertTrue("The test must apply the tall insets to the sheet's window", roots.size >= 2)
            roots.forEach { root ->
                root.setOnApplyWindowInsetsListener { _, _ -> insets }
                root.dispatchApplyWindowInsets(insets)
            }
        }
        compose.mainClock.advanceTimeBy(3_000)

        val sheet = compose.onNodeWithTag("method_explainer")
        val top = sheet.fetchSemanticsNode().boundsInRoot.top
        assertTrue("The sheet must clear the 80px status bar and its 8dp gap: $top", top >= 88f)
        sheet.performTouchInput { swipeUp(durationMillis = 100) }
        compose.mainClock.advanceTimeBy(3_000)
        repeat(12) {
            compose.mainClock.advanceTimeBy(100)
            assertEquals("The expanded sheet must stay at one position after a fling", top, sheet.fetchSemanticsNode().boundsInRoot.top, 1f)
        }
        assertFalse("Scrolling to the bottom must not dismiss the sheet", dismissed)
        val footer = compose.onNodeWithText(credit).getUnclippedBoundsInRoot()
        val viewport = sheet.getUnclippedBoundsInRoot()
        assertTrue("The final attribution line must remain above the navigation bar", footer.bottom <= viewport.bottom)
        compose.onNodeWithText(credit).assertIsDisplayed()
    }

    private fun open(onDismiss: () -> Unit = {}) {
        val paris = PrayerPlace.Coordinates(
            latE5 = 4_885_660, lngE5 = 235_220, zoneId = "Europe/Paris", zoneSource = ZoneSource.CATALOGUE, countryCode = "FR",
        )
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AdhkarTheme { MethodExplainerSheet(paris, PrayerMethodChoice(method.id), onDismiss) }
            }
        }
        compose.waitForIdle()
    }
}
