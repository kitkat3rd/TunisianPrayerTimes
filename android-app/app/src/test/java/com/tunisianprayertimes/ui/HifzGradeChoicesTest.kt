package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.quran.hifz.HifzGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HifzGradeChoicesTest {
    @get:Rule val compose = createComposeRule()
    private val answers = mutableListOf<HifzGrade>()
    private val outcomes = linkedMapOf(
        HifzGrade.Held to HifzGradeOutcome("نراجعها على فترات أطول", "غدًا بعد الفجر"),
        HifzGrade.Hesitated to HifzGradeOutcome("لا تُحتسب ضمن مرات التذكّر دون مساعدة", "اليوم بعد العصر"),
        HifzGrade.Again to HifzGradeOutcome("نُسجّل خطأً جديدًا", "اليوم بعد العصر"),
    )

    private fun show(
        choices: Map<HifzGrade, HifzGradeOutcome?> = outcomes,
        fontScale: Float = 1f,
        compact: Boolean = true,
        assisted: Boolean = false,
        notice: String? = null,
        explanation: String? = null,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                AdhkarTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        HifzGradeChoices(1L, choices, assisted, compact, notice = notice, explanation = explanation, onGrade = { answers += it })
                    }
                }
            }
        }
    }

    @Test
    fun choicesKeepLabelsAndSchedulesButMoveEffectsIntoOneInfoDialog() {
        show()
        outcomes.forEach { (grade, outcome) ->
            compose.onNodeWithText(hifzGradeLabel(grade)).assertIsDisplayed()
            compose.onNodeWithText(outcome.effect!!, substring = true).assertDoesNotExist()
        }
        compose.onNodeWithText("المراجعة القادمة: غدًا بعد الفجر").assertIsDisplayed()
        compose.onAllNodesWithText("المراجعة القادمة: اليوم بعد العصر").assertCountEquals(2)
        compose.onNodeWithTag("hifz_grade_info").assertContentDescriptionEquals("شرح خيارات الإجابة").performClick()
        compose.onNodeWithTag("hifz_grade_info_dialog").assertIsDisplayed()
        outcomes.values.forEach { outcome ->
            compose.onNodeWithText(outcome.text(withBack = true, short = false)).performScrollTo().assertIsDisplayed()
        }
        assertTrue("Reading help must not submit an answer", answers.isEmpty())
        compose.onNodeWithTag("hifz_grade_info_close").performClick()
        compose.onNodeWithTag("hifz_grade_info_dialog").assertDoesNotExist()
        compose.onNodeWithTag("hifz_grade_held").performClick()
        assertEquals(listOf(HifzGrade.Held), answers)
    }

    @Test
    fun compactChoicesAreEqualInSizeAndKeepAdequateTouchTargets() {
        show()
        val bounds = HifzGrade.entries.map { compose.onNodeWithTag("hifz_grade_${it.name.lowercase()}").getUnclippedBoundsInRoot() }
        bounds.forEach {
            assertEquals(bounds.first().bottom - bounds.first().top, it.bottom - it.top)
            assertEquals(bounds.first().right - bounds.first().left, it.right - it.left)
            assertTrue(it.bottom - it.top >= 48.dp)
        }
        val info = compose.onNodeWithTag("hifz_grade_info").getUnclippedBoundsInRoot()
        assertTrue(info.right - info.left >= 48.dp && info.bottom - info.top >= 48.dp)
    }

    @Test
    fun aSharedNextReviewIsVisibleOnceOutsideTheChoices() {
        show(outcomes.mapValues { (_, outcome) -> outcome.copy(back = "اليوم بعد العصر") })
        compose.onAllNodesWithText("المراجعة القادمة: اليوم بعد العصر").assertCountEquals(1)
        compose.onNodeWithTag("hifz_grade_shared_back").assertIsDisplayed()
        outcomes.values.forEach { compose.onNodeWithText(it.effect!!, substring = true).assertDoesNotExist() }
    }

    @Test
    fun largeTextStacksChoicesRatherThanSqueezingOrTruncatingThem() {
        show(fontScale = 1.8f)
        val bounds = HifzGrade.entries.map { compose.onNodeWithTag("hifz_grade_${it.name.lowercase()}").getUnclippedBoundsInRoot() }
        assertTrue(bounds[0].bottom <= bounds[1].top)
        assertTrue(bounds[1].bottom <= bounds[2].top)
        bounds.forEach { assertTrue(it.bottom - it.top >= 48.dp) }
        HifzGrade.entries.forEach { compose.onNodeWithText(hifzGradeLabel(it)).performScrollTo().assertIsDisplayed() }
    }

    @Test
    fun assistanceRemainsVisibleAndDoesNotOfferUnaidedRecall() {
        val notice = "رأيت منها شيئًا قبل أن تكشفها."
        show(outcomes.filterKeys { it != HifzGrade.Held }, assisted = true, notice = notice)
        compose.onNodeWithText(notice).assertIsDisplayed()
        compose.onNodeWithTag("hifz_grade_held").assertDoesNotExist()
        compose.onNodeWithText(hifzGradeLabel(HifzGrade.Hesitated, assisted = true)).assertIsDisplayed()
        compose.onNodeWithTag("hifz_grade_hesitated").performClick()
        assertEquals(listOf(HifzGrade.Hesitated), answers)
    }

    @Test
    fun optionalPracticeKeepsUnchangedScheduleVisibleAndExplainsCountingOnDemand() {
        val explanation = "هذا تدريب اختياري: التذكّر لا يغيّر موعد المراجعة، والنسيان أو الخطأ يُسجّل."
        show(linkedMapOf(HifzGrade.Held to null, HifzGrade.Hesitated to null, HifzGrade.Again to outcomes.getValue(HifzGrade.Again)),
            compact = false, explanation = explanation)
        compose.onAllNodesWithText("موعد المراجعة لا يتغيّر").assertCountEquals(2)
        compose.onNodeWithText("المراجعة القادمة: اليوم بعد العصر").assertIsDisplayed()
        compose.onNodeWithText(explanation).assertDoesNotExist()
        compose.onNodeWithTag("hifz_grade_info").performClick()
        compose.onNodeWithText(explanation).assertIsDisplayed()
        assertTrue(answers.isEmpty())
    }

    @Test
    fun openExplanationSurvivesRestorationAndCanOpenGeneralHelpWithoutGrading() {
        var helpOpened = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            AdhkarTheme {
                HifzGradeChoices(1L, outcomes, assisted = false, compact = true, onHowItWorks = { helpOpened = true }, onGrade = { answers += it })
            }
        }
        compose.onNodeWithTag("hifz_grade_info").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("hifz_grade_info_dialog").assertIsDisplayed()
        compose.onNodeWithTag("hifz_how_it_works").performScrollTo().performClick()
        compose.onNodeWithTag("hifz_grade_info_dialog").assertDoesNotExist()
        assertTrue(helpOpened)
        assertTrue(answers.isEmpty())
    }
}
