package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import com.tunisianprayertimes.adhkar.DhikrClosingTexts
import com.tunisianprayertimes.adhkar.DhikrSalawatClosingTexts
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdhkarClosingCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun salawatHeadingTextAndReferenceStayTogetherAcrossRestorationAndMidnight() {
        val date = mutableStateOf(LocalDate.of(2026, 11, 6)) // Friday
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AdhkarTheme {
                    DhikrClosingCard(DhikrClosingKind.SALAWAT, "الصلاة على النبي ﷺ", {}, {}, date.value)
                }
            }
        }
        val heading = compose.onNodeWithTag("adhkar_closing_reward_heading")
            .fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        val benefit = DhikrSalawatClosingTexts.ALL.single { it.heading == heading }
        compose.onNodeWithTag("adhkar_closing_reward_heading").performScrollTo().assertTextEquals(heading)
        if (benefit.quran) compose.onNodeWithContentDescription(benefit.text).assertExists()
        else compose.onNodeWithText(benefit.text).assertExists()
        compose.onNodeWithText(benefit.reference).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("من فضل الذكر").assertDoesNotExist()

        compose.runOnIdle { date.value = date.value.plusDays(1) }
        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("adhkar_closing_reward_heading").assertTextEquals(heading)
        if (benefit.quran) compose.onNodeWithContentDescription(benefit.text).assertExists()
        else compose.onNodeWithText(benefit.text).assertExists()
        compose.onNodeWithText(benefit.reference).assertExists()
        compose.onNodeWithTag("adhkar_closing_done").assertIsDisplayed()
        compose.onNodeWithTag("adhkar_closing_stay").assertIsDisplayed()
    }

    @Test fun weekdaySalawatCardNeverSelectsFridaysBenefit() {
        compose.setContent {
            AdhkarTheme {
                DhikrClosingCard(DhikrClosingKind.SALAWAT, "الصلاة على النبي ﷺ", {}, {}, LocalDate.of(2026, 11, 7))
            }
        }
        val heading = compose.onNodeWithTag("adhkar_closing_reward_heading")
            .fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        assertTrue(DhikrSalawatClosingTexts.eligible(isFriday = false).any { it.heading == heading })
        compose.onNodeWithText(DhikrSalawatClosingTexts.ALL.single { it.fridayOnly }.heading!!).assertDoesNotExist()
    }

    @Test fun anotherReminderKeepsItsGenericDailyReward() {
        val date = LocalDate.of(2026, 11, 7)
        val reward = DhikrClosingTexts.rewardFor(date.toEpochDay(), 5)
        compose.setContent {
            AdhkarTheme {
                DhikrClosingCard(DhikrClosingKind.REMINDER, "الاستغفار", {}, {}, date)
            }
        }
        compose.onNodeWithTag("adhkar_closing_reward_heading").assertTextEquals(
            if (reward.theme == DhikrClosingTexts.ClosingTheme.PERSEVERANCE) "من فضل المداومة" else "من فضل الذكر")
        compose.onNodeWithText(reward.reference).assertExists()
    }
}
