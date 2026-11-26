package com.tunisianprayertimes.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.FirstRunPlaceChoice
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.fasting.FastingReminder
import com.tunisianprayertimes.place.PrayerPlace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
class FastingCityReturnTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun browseWithSuggestedCity() {
        FastingRequests.sheet = false
        FastingRequests.suhoor = false
        PrefsManager.initializeFirstRun(context)
        PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Browse(PrayerPlace.Inm(615), null))
        assertFalse(PrefsManager.hasConfirmedPlace(context))
    }

    @Test fun confirmingTheCityReturnsToTheReminderWithoutEnablingIt() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FastingCard() }
        compose.onNodeWithContentDescription("تذكير صيام التطوع").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("feature_confirm_suggested_place").performClick()

        compose.onNodeWithText("النية").assertExists()
        compose.onNodeWithTag("feature_confirm_suggested_place").assertDoesNotExist()
        assertTrue(PrefsManager.hasConfirmedPlace(context))
        assertFalse(FastingReminder.isEnabled(context))
    }

    @Test fun cancellingThenConfirmingReturnsToSuhoorPreferencesWithoutArmingAnAlarm() {
        val prefs = context.getSharedPreferences("fasting_reminder", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("enabled", true).apply()
        FastingRequests.sheet = true
        compose.setContent { FastingCard() }
        val preferences = compose.onNodeWithText("غيّر")
        preferences.performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("feature_cancel_place").performSemanticsAction(SemanticsActions.OnClick)
        assertFalse(PrefsManager.hasConfirmedPlace(context))
        compose.onNodeWithText("كم دقيقة قبل الفجر يرنّ؟").assertDoesNotExist()

        preferences.performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("feature_confirm_suggested_place").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("كم دقيقة قبل الفجر يرنّ؟").assertExists()
        assertFalse(FastingReminder.suhoorChosen(context))
        assertFalse(prefs.contains("suhoor_fast_day"))
    }
}
