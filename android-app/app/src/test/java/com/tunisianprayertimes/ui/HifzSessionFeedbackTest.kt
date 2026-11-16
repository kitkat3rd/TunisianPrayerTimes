package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tunisianprayertimes.quran.hifz.HifzSessionProblem
import com.tunisianprayertimes.quran.hifz.HifzSessionProblemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HifzSessionFeedbackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun anOrdinaryNoticeIsInlineDismissibleAndHasNoRetry() {
        var notice by mutableStateOf<String?>("لا توجد أسئلة مستحقة الآن.")
        compose.setContent {
            AdhkarTheme {
                HifzSessionFeedback(null, notice, false, {}, {}, { notice = null })
            }
        }
        compose.onNodeWithTag("hifz_session_feedback").assertIsDisplayed()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithTag("hifz_session_feedback_retry").assertDoesNotExist()
        compose.onNodeWithTag("hifz_session_feedback_dismiss").performClick()
        compose.onNodeWithTag("hifz_session_feedback").assertDoesNotExist()
    }

    @Test fun aPositionSaveWarningDoesNotBlockTheScreenAndOffersTheSaveRetry() {
        var retries = 0
        var otherActions = 0
        var retrying by mutableStateOf(false)
        compose.setContent {
            AdhkarTheme {
                Column {
                    HifzSessionFeedback(
                        HifzSessionProblem(HifzSessionProblemKind.PositionSave, "تعذّر حفظ موضع المراجعة."),
                        null, retrying, { retries++ }, {}, {},
                    )
                    TextButton(onClick = { otherActions++ }) { Text("دفتر المراجعة") }
                }
            }
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithText("دفتر المراجعة").performClick()
        assertEquals(1, otherActions)
        compose.onNodeWithTag("hifz_session_feedback_retry").performClick()
        assertEquals(1, retries)
        compose.runOnIdle { retrying = true }
        compose.onNodeWithTag("hifz_session_feedback_retry").assertIsNotEnabled()
    }

    @Test fun restoreAndChangedPointWarningsHaveDismissButNoRetry() {
        var problem by mutableStateOf<HifzSessionProblem?>(HifzSessionProblem(HifzSessionProblemKind.Restore, "تعذّر استرجاع موضع المراجعة."))
        compose.setContent {
            AdhkarTheme {
                HifzSessionFeedback(problem, null, false, {}, { problem = null }, {})
            }
        }
        for (kind in listOf(HifzSessionProblemKind.Restore, HifzSessionProblemKind.PointChanged)) {
            compose.runOnIdle { problem = HifzSessionProblem(kind, "لم نسجّل إجابة جديدة.") }
            compose.onNodeWithTag("hifz_session_feedback").assertIsDisplayed()
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.onNodeWithTag("hifz_session_feedback_retry").assertDoesNotExist()
            compose.onNodeWithTag("hifz_session_feedback_dismiss").performClick()
            compose.onNodeWithTag("hifz_session_feedback").assertDoesNotExist()
        }
    }

    @Test fun closingAnUnsavedAnswerDialogLeavesItsWarningAndRetryUntilSaved() {
        var problem by mutableStateOf<HifzSessionProblem?>(HifzSessionProblem(HifzSessionProblemKind.AnswerSave, "تعذّر حفظ الإجابة."))
        var dismissed = false
        var retries = 0
        compose.setContent {
            AdhkarTheme {
                HifzSessionFeedback(problem, null, false, { retries++; problem = null }, { dismissed = true }, {})
            }
        }
        compose.onNodeWithTag("hifz_session_save_error").assertIsDisplayed()
        compose.onNodeWithTag("hifz_session_dialog_retry").assertIsDisplayed()
        compose.onNodeWithText("إغلاق").performClick()
        assertFalse(dismissed)
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithTag("hifz_session_feedback").assertIsDisplayed()
        compose.onNodeWithTag("hifz_session_feedback_dismiss").assertDoesNotExist()
        compose.onNodeWithTag("hifz_session_feedback_retry").performClick()
        assertEquals(1, retries)
        compose.onNodeWithTag("hifz_session_feedback").assertDoesNotExist()
    }
}
