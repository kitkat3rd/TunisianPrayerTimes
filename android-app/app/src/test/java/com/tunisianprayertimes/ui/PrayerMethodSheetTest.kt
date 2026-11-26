package com.tunisianprayertimes.ui

import android.app.Application
import android.graphics.Insets
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.R
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.ZoneSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h720dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrayerMethodSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 60_000)
    fun methodListStaysStillWithATallStatusBarAfterFlingingToTheEnd() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val paris = PrayerPlace.Coordinates(
            latE5 = 4_885_660, lngE5 = 235_220, zoneId = "Europe/Paris", zoneSource = ZoneSource.CATALOGUE, countryCode = "FR",
        )
        compose.setContent {
            AdhkarTheme {
                Column { PrayerMethodSection(paris, onTimesChanged = {}, onShowInmExample = {}) }
            }
        }
        compose.onNodeWithText(context.getString(R.string.prayer_settings_choose_method)).performClick()
        compose.waitForIdle()
        val list = compose.onNodeWithTag("prayer_method_list")

        compose.mainClock.autoAdvance = false
        val statusBarHeight = 120
        val insets = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, statusBarHeight, 0, 0))
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 24))
            .setVisible(WindowInsets.Type.systemBars(), true)
            .build()
        compose.runOnUiThread {
            val windows = WindowInspector.getGlobalWindowViews()
            assertTrue("Apply insets to the sheet's dialog window", windows.size >= 2)
            windows.forEach { window ->
                window.setOnApplyWindowInsetsListener { _, _ -> insets }
                window.dispatchApplyWindowInsets(insets)
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        list.assertIsDisplayed()
        val sheet = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle))
        val sheetTop = sheet.fetchSemanticsNode().boundsInRoot.top
        val listTop = list.fetchSemanticsNode().boundsInRoot.top
        assertTrue("The sheet must clear the status bar: $sheetTop", sheetTop >= statusBarHeight)

        repeat(3) {
            list.performTouchInput { swipeUp(durationMillis = 100) }
            compose.mainClock.advanceTimeBy(750)
        }
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            assertEquals("The sheet must stay in place", sheetTop, sheet.fetchSemanticsNode().boundsInRoot.top, 1f)
            assertEquals("The method list must stay in place", listTop, list.fetchSemanticsNode().boundsInRoot.top, 1f)
        }
        compose.onNodeWithText("تعديل الزوايا والدقائق").assertIsDisplayed()
    }
}
