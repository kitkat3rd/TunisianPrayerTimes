package com.tunisianprayertimes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.HijriCalendarDate
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.Divider
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.time.LocalDate

@Composable
internal fun DateNavigationRow(
    delegationId: Int,
    selectedDate: Long,
    isToday: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDateSelected: (Long) -> Unit,
) {
    val context = LocalContext.current
    val date = calendarLocalDate(selectedDate)
    val calendar = rememberTunisianHijriCalendar()
    val hijriDate = remember(date, calendar) { calendar.date(date) }
    LoadOfficialCalendarYear(algorithmicHijriYear(date))
    val dateRange = remember(context, delegationId) {
        PrayerTimesRepository.getDateRange(context, delegationId)
    }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var pickerOpeningDate by rememberSaveable { mutableLongStateOf(selectedDate) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GoldLight.copy(alpha = 0.18f))
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The arrows stay beside the date at every font size; a long date wraps
        // inside the calendar page instead of pushing them onto a second row.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DateDayButton(
                previous = true,
                enabled = canGoBack,
                onClick = onPrevious,
            )
            CalendarPage(
                date = date,
                hijriDate = hijriDate,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                onClick = {
                    pickerOpeningDate = selectedDate
                    showDatePicker = true
                },
            )
            DateDayButton(
                previous = false,
                enabled = canGoForward,
                onClick = onNext,
            )
        }
        if (!isToday) {
            TextButton(
                onClick = { onDateSelected(calendarDateMillis(LocalDate.now())) },
                modifier = Modifier.testTag(TestTags.DATE_TODAY_BUTTON).heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(R.string.date_go_back_today),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GreenPrimary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (showDatePicker) {
        HijriCalendarDialog(
            selectedDate = pickerOpeningDate,
            dateRange = dateRange,
            onDateSelected = {
                showDatePicker = false
                onDateSelected(it)
            },
            onDismiss = { showDatePicker = false },
        )
    }
}

/**
 * The selected day drawn as a tear-off calendar page: a green header carrying the Hijri date, binding
 * rings across its top edge, and the Gregorian date on the paper below. The whole page opens the picker.
 */
@Composable
private fun CalendarPage(date: LocalDate, hijriDate: HijriCalendarDate, modifier: Modifier, onClick: () -> Unit) {
    val pickerLabel = stringResource(R.string.calendar_open_picker)
    val shape = RoundedCornerShape(12.dp)
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = RING_OVERHANG)
                .testTag(TestTags.DATE_LABEL)
                .clip(shape)
                .background(Color.White)
                .border(1.dp, Divider, shape)
                .clickable(role = Role.Button, onClickLabel = pickerLabel, onClick = onClick)
                .heightIn(min = 48.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GreenPrimary)
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = hijriDateLabel(hijriDate),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = gregorianDateLabel(date),
                    fontSize = 13.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                )
                if (!hijriDate.isEstimated) Text(
                    text = stringResource(R.string.calendar_official_start_short),
                    fontSize = 11.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Canvas(Modifier.matchParentSize()) {
            val width = 5.dp.toPx()
            val height = 11.dp.toPx()
            for (fraction in listOf(0.16f, 0.84f)) {
                val topLeft = Offset(size.width * fraction - width / 2f, 0f)
                val corner = CornerRadius(width / 2f)
                drawRoundRect(Color.White, topLeft, Size(width, height), corner)
                drawRoundRect(GreenPrimaryDark, topLeft, Size(width, height), corner, style = Stroke(1.25.dp.toPx()))
            }
        }
    }
}

// How far the binding rings rise above the page's top edge.
private val RING_OVERHANG = 5.dp

@Composable
private fun DateDayButton(previous: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (previous) R.string.calendar_previous_day else R.string.calendar_next_day)
    val color = if (enabled) GreenPrimary else TextMuted.copy(alpha = 0.35f)
    // RTL reading order places the previous-day control on the right. Its arrow
    // points outwards, as does the next-day control on the opposite side.
    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val pointsRight = previous == isRtl
    Box(
        modifier = Modifier
            .testTag(if (previous) TestTags.DATE_PREVIOUS_BUTTON else TestTags.DATE_NEXT_BUTTON)
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Color.White.copy(alpha = 0.75f) else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val outerX = size.width * if (pointsRight) 0.35f else 0.65f
            val tipX = size.width * if (pointsRight) 0.65f else 0.35f
            drawLine(color, Offset(outerX, size.height * 0.2f), Offset(tipX, size.height * 0.5f), 2.dp.toPx(), StrokeCap.Round)
            drawLine(color, Offset(tipX, size.height * 0.5f), Offset(outerX, size.height * 0.8f), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}
