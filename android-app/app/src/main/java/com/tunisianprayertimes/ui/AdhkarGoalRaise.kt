package com.tunisianprayertimes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The highest goal a reminder takes, as the editor's validation allows. */
private const val MAX_DHIKR_GOAL = 100_000

/**
 * Asks for a goal above the day's [current] one, once it is reached: for the day only, or for good.
 * For good raises Friday's own goal when [fridayGoal] (today is a Friday that has one), else the
 * daily goal. The suggestion is twice the goal: the same again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DhikrGoalRaiseSheet(current: Int, fridayGoal: Boolean, onRaise: (goal: Int, permanent: Boolean) -> Unit,
                                 onDismiss: () -> Unit) {
    val p = LocalAdhkarPalette.current
    var value by rememberSaveable(current) { mutableStateOf(latinNumber((current * 2).coerceAtMost(MAX_DHIKR_GOAL))) }
    val goal = parseDhikrInt(value)?.takeIf { it in (current + 1)..MAX_DHIKR_GOAL }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = AdhkarSurface, unfocusedContainerColor = AdhkarSurface,
        focusedBorderColor = p.primary.copy(alpha = .6f), unfocusedBorderColor = AdhkarBorder)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.background, modifier = Modifier.testTag("adhkar_raise_goal_sheet")) {
        DhikrSheetHeader("زيادة الهدف", onDismiss)
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
            .padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("أتممت هدف اليوم: " + latinNumber(current) + ". ما هدفك الجديد؟",
                color = p.forest, fontSize = 15.sp, lineHeight = 26.sp)
            GoalStepper(value, { value = it }, minimum = current + 1, "زيادة العدد", "تقليص العدد",
                "adhkar_raise_goal_input", fieldColors)
            // Shown only for a number typed at or below the goal reached; a cleared field just waits.
            if (goal == null && value.isNotBlank()) Text(
                if ((parseDhikrInt(value) ?: 0) > MAX_DHIKR_GOAL) "أدخل هدفًا لا يزيد على 100,000."
                else "اختر عددًا أكبر من " + latinNumber(current) + ".",
                color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            Button(onClick = { goal?.let { onRaise(it, false) } }, enabled = goal != null, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("adhkar_raise_goal_today")) {
                Text("لهذا اليوم فقط", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = { goal?.let { onRaise(it, true) } }, enabled = goal != null, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("adhkar_raise_goal_always")) {
                Text(if (fridayGoal) "لكل جمعة" else "دائمًا", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
