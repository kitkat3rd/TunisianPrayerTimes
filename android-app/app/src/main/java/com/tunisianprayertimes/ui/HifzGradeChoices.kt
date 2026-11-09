package com.tunisianprayertimes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.hifz.HifzGrade
import com.tunisianprayertimes.ui.theme.*

/** Keep the decision and its next review in view; counting and scheduling explanations are optional. */
@Composable
internal fun HifzGradeChoices(
    pointId: Long,
    outcomes: Map<HifzGrade, HifzGradeOutcome?>,
    assisted: Boolean,
    compact: Boolean,
    tagPrefix: String = "hifz_grade",
    notice: String? = null,
    explanation: String? = null,
    onHowItWorks: (() -> Unit)? = null,
    onGrade: (HifzGrade) -> Unit,
) {
    var infoOpen by rememberSaveable(pointId) { mutableStateOf(false) }
    val grades = outcomes.keys.toList()
    val sharedBack = outcomes.values.map { it?.back }.distinct().singleOrNull()
    fun detail(grade: HifzGrade): String = if (sharedBack != null) ""
        else outcomes.getValue(grade)?.let { "المراجعة القادمة: ${it.back}" } ?: "موعد المراجعة لا يتغيّر"
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("هل تذكّرتها قبل أن تكشفها؟", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
            IconButton(onClick = { infoOpen = true }, modifier = Modifier.size(48.dp).testTag("${tagPrefix}_info")) {
                Icon(painterResource(R.drawable.ic_adhkar_info), "شرح خيارات الإجابة", Modifier.size(20.dp), tint = GreenPrimaryDark)
            }
        }
        // Assistance changes which answers are offered, so its reason must not be hidden in help.
        if (notice != null) Text(notice, fontSize = 12.sp, color = QuranHifzViolet)
        if (compact && LocalDensity.current.fontScale <= 1.3f) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                grades.forEach { grade ->
                    HifzGradeChip(hifzGradeLabel(grade, assisted), detail(grade), grade, "${tagPrefix}_${grade.name.lowercase()}", Modifier.weight(1f)) {
                        onGrade(grade)
                    }
                }
            }
        } else {
            grades.forEach { grade ->
                HifzGradeButton(hifzGradeLabel(grade, assisted), detail(grade), grade, "${tagPrefix}_${grade.name.lowercase()}") { onGrade(grade) }
            }
        }
        if (sharedBack != null) Text(
            "المراجعة القادمة: $sharedBack", fontSize = 12.sp, color = TextMuted, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}_shared_back"),
        )
    }
    if (infoOpen) AlertDialog(
        onDismissRequest = { infoOpen = false },
        modifier = Modifier.testTag("${tagPrefix}_info_dialog"),
        title = { Text("خيارات الإجابة") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (explanation != null) Text(explanation, fontSize = 14.sp, color = TextMuted)
                grades.forEach { grade ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(hifzGradeLabel(grade, assisted), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
                        Text(outcomes.getValue(grade)?.text(withBack = true, short = false) ?: "موعد المراجعة لا يتغيّر.", fontSize = 14.sp)
                    }
                }
                if (onHowItWorks != null) TextButton(
                    onClick = { infoOpen = false; onHowItWorks() },
                    contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("hifz_how_it_works"),
                ) { Text("كيف تعمل مراجعة اليوم؟", fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = { infoOpen = false }, modifier = Modifier.testTag("${tagPrefix}_info_close")) { Text("فهمت") }
        },
    )
}
