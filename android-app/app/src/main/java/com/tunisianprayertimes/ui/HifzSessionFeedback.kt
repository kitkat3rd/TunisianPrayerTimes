package com.tunisianprayertimes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.hifz.HifzSessionCoordinator
import com.tunisianprayertimes.quran.hifz.HifzSessionProblem
import com.tunisianprayertimes.quran.hifz.HifzSessionProblemKind
import com.tunisianprayertimes.ui.theme.*

@Composable
internal fun HifzSessionFeedback(sessions: HifzSessionCoordinator) = HifzSessionFeedback(
    problem = sessions.problem, notice = sessions.notice, retrying = sessions.busy,
    onRetry = sessions::retry, onDismissProblem = sessions::dismissError, onDismissNotice = sessions::dismissNotice,
)

/** Only an unsaved answer interrupts. Closing its dialog leaves the warning and retry in the review itself. */
@Composable
internal fun HifzSessionFeedback(
    problem: HifzSessionProblem?,
    notice: String?,
    retrying: Boolean,
    onRetry: () -> Unit,
    onDismissProblem: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    val message = problem?.message ?: notice ?: return
    val answerSave = problem?.kind == HifzSessionProblemKind.AnswerSave
    var showDialog by remember(problem) { mutableStateOf(answerSave) }
    if (answerSave && showDialog) AlertDialog(
        onDismissRequest = { showDialog = false },
        title = { Text("لم تُحفظ الإجابة") },
        text = { Text(message, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = onRetry, enabled = !retrying, modifier = Modifier.testTag("hifz_session_dialog_retry")) { Text("إعادة المحاولة") }
        },
        dismissButton = {
            TextButton(onClick = { showDialog = false }) { Text("إغلاق") }
        },
        modifier = Modifier.testTag("hifz_session_save_error"),
    )
    val textColor = if (problem != null) MaterialTheme.colorScheme.onErrorContainer else GreenPrimaryDark
    Surface(
        color = if (problem != null) MaterialTheme.colorScheme.errorContainer else AdhkarSoftGreen,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("hifz_session_feedback"),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f).padding(vertical = 8.dp), fontSize = 13.sp, color = textColor)
                if (!answerSave) IconButton(
                    onClick = if (problem != null) onDismissProblem else onDismissNotice,
                    modifier = Modifier.size(48.dp).testTag("hifz_session_feedback_dismiss"),
                ) {
                    Icon(painterResource(R.drawable.ic_adhkar_close), "إخفاء", Modifier.size(14.dp), tint = textColor)
                }
            }
            if (problem?.retrySave == true) TextButton(
                onClick = onRetry, enabled = !retrying, modifier = Modifier.testTag("hifz_session_feedback_retry"),
            ) { Text("إعادة المحاولة", color = textColor, fontSize = 13.sp) }
        }
    }
}
