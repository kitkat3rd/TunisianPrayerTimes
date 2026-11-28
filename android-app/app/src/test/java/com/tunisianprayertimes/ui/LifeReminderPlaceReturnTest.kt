package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
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
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.remembrance.DeathReminder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w400dp-h900dp-mdpi", application = Application::class)
class LifeReminderPlaceReturnTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dailyReadingStaysAvailableAndCityConfirmationReturnsToTheDisabledSwitch() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PrefsManager.initializeFirstRun(app)
        PrefsManager.completeFirstRun(app, FirstRunPlaceChoice.Browse(PrayerPlace.Inm(615), null))
        val reading = DeathReminder.remembranceFor(DeathReminder.today(app))
        compose.setContent { DeathRemembranceCard() }
        compose.onNodeWithText(reading.headline).assertExists()
        compose.onNodeWithContentDescription("تذكير ذكر الموت").performClick()
        compose.onNodeWithTag("feature_confirm_suggested_place").performClick()
        val reason = "نتذكّر الموت لنراجع"
        compose.onNodeWithText(reason, substring = true).assertDoesNotExist()
        compose.onNodeWithText("لماذا نذكر الموت؟").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText(reason, substring = true).performScrollTo().assertExists()
        assertTrue(PrefsManager.hasConfirmedPlace(app))
        assertFalse(DeathReminder.isEnabled(app))
    }
}
