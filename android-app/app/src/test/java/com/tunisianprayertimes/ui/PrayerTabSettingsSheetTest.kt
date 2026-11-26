package com.tunisianprayertimes.ui

import android.app.Application
import android.content.Context
import android.view.inspector.WindowInspector
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.ZoneSource
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h720dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrayerTabSettingsSheetTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val tunis = PrayerPlace.Inm(615)
    private val paris = PrayerPlace.Coordinates(
        4_885_600, 235_200, null, "Europe/Paris", ZoneSource.CATALOGUE, "FR", null, null, "75",
    )

    private fun showSettings(
        place: PrayerPlace,
        access: PrayerSettingsAccess = PrayerSettingsAccess(true, true, false, false),
        fontScale: Float = 1f,
        onBackgroundTravelChange: (Boolean) -> Unit = {},
    ) {
        val selectedDate = LocalDate.of(2026, 11, 24).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        compose.setContent {
            var page by remember { mutableStateOf<PrayerTabSettingsPage?>(PrayerTabSettingsPage.Overview) }
            var autoLocation by remember { mutableStateOf(true) }
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                TunisianPrayerTimesTheme(fontFamily = AppFontFamily) {
                    page?.let {
                        PrayerTabSettingsSheet(
                            page = it,
                            onPageChange = { page = it },
                            place = place,
                            selectedDate = selectedDate,
                            onTimesChanged = {},
                            autoSilenceEnabled = true,
                            onAutoSilenceChange = {},
                            callEndVibrationEnabled = true,
                            onCallEndVibrationChange = {},
                            autoLocationEnabled = autoLocation,
                            onAutoLocationChange = { autoLocation = it },
                            access = access,
                            onBackgroundTravelChange = onBackgroundTravelChange,
                            onDismiss = { page = null },
                        )
                    }
                }
            }
        }
    }

    private fun row(id: Int) = compose.onNodeWithText(context.getString(id))

    @Test
    fun tunisianCalculationExplanationReturnsToSettings() {
        showSettings(tunis)

        row(R.string.prayer_settings_method).assertDoesNotExist()
        compose.onNodeWithTag(TestTags.PRAYER_TIME_METHOD_ROW).performScrollTo().performClick()
        compose.onNodeWithTag(TestTags.PRAYER_TIME_METHOD_DIALOG).assertIsDisplayed()

        compose.onNodeWithContentDescription(context.getString(R.string.prayer_method_close)).performClick()
        compose.onNodeWithTag(TestTags.PRAYER_TIME_METHOD_DIALOG).assertDoesNotExist()
        row(R.string.prayer_tab_settings_title).assertIsDisplayed()
    }

    @Test
    fun abroadCalculationOpensSeparatelyAndSystemBackReturnsToSettings() {
        showSettings(paris)

        row(R.string.prayer_settings_choose_method).assertDoesNotExist()
        row(R.string.prayer_settings_method).performScrollTo().performClick()
        row(R.string.prayer_settings_calculation_title).assertIsDisplayed()
        row(R.string.prayer_settings_choose_method).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.AUTO_LOCATION_SWITCH).assertDoesNotExist()

        compose.onNodeWithText("الحنفية").performScrollTo().performClick().assertIsSelected()
        assertEquals(2, PrefsManager.getMethodChoice(context, paris)?.asrShadow)

        pressSheetBack()
        row(R.string.prayer_tab_settings_title).assertIsDisplayed()
        row(R.string.prayer_settings_calculation_title).assertDoesNotExist()
        pressSheetBack()
        row(R.string.prayer_tab_settings_title).assertDoesNotExist()
    }

    @Test
    fun backgroundPermissionActionAppearsOnlyWhileAutoLocationIsOn() {
        var requests = 0
        showSettings(tunis, fontScale = 1.6f, onBackgroundTravelChange = { if (it) requests++ })

        row(R.string.travel_in_background).performScrollTo().performClick()
        assertEquals(1, requests)
        compose.onNodeWithTag(TestTags.AUTO_LOCATION_SWITCH).performScrollTo().performClick()
        row(R.string.travel_in_background).assertDoesNotExist()
    }

    @Test
    fun missingAccessShowsTheRelevantRecoveryActions() {
        showSettings(tunis, access = PrayerSettingsAccess(false, false, false, true))

        row(R.string.call_feedback_allow_phone_state).performScrollTo().assertIsDisplayed()
        row(R.string.travel_allow_foreground).performScrollTo().assertIsDisplayed()
        row(R.string.travel_in_background).assertDoesNotExist()
        row(R.string.prayer_check_battery).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun grantedAccessLeavesRepairActionsHidden() {
        showSettings(tunis)
        row(R.string.call_feedback_allow_phone_state).assertDoesNotExist()
        row(R.string.travel_allow_foreground).assertDoesNotExist()
        row(R.string.prayer_check_battery).assertDoesNotExist()
    }

    private fun pressSheetBack() {
        compose.runOnIdle {
            val window = WindowInspector.getGlobalWindowViews().last { it.isAttachedToWindow }
            requireNotNull(window.findViewTreeOnBackPressedDispatcherOwner()).onBackPressedDispatcher.onBackPressed()
        }
    }
}
