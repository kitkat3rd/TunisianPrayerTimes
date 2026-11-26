package com.tunisianprayertimes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
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
import com.tunisianprayertimes.HijriLabels
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle

private val weekdayAndDate = DateTimeFormatter.ofPattern("EEEE، d MMMM", calendarLocale)
    .withDecimalStyle(DecimalStyle.STANDARD)

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
    val placeZone = PrayerTimesRepository.place(context).zone
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
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DateDayButton(
                previous = true,
                enabled = canGoBack,
                onClick = onPrevious,
            )
            TimetableDate(
                date = date,
                hijriDate = hijriDate,
                isToday = isToday,
                modifier = Modifier.weight(1f),
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
                onClick = { onDateSelected(calendarDateMillis(LocalDate.now(placeZone))) },
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
            todayZone = placeZone,
            onDateSelected = {
                showDatePicker = false
                onDateSelected(it)
            },
            onDismiss = { showDatePicker = false },
        )
    }
}

@Composable
private fun TimetableDate(date: LocalDate, hijriDate: HijriCalendarDate, isToday: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val pickerLabel = stringResource(R.string.calendar_open_picker)
    val hijri = hijriDateLabel(hijriDate)
    val gregorian = gregorianDateLabel(date)
    val fullDate = stringResource(R.string.calendar_day_description, hijri, gregorian)
    val announcement = if (hijriDate.isEstimated) fullDate
        else "$fullDate. ${stringResource(R.string.calendar_official_start_short)}"
    Column(
        modifier = modifier
            .testTag(TestTags.DATE_LABEL)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = pickerLabel, onClick = onClick)
            .semantics { contentDescription = announcement }
            .heightIn(min = 48.dp)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (isToday) HijriLabels.dayMonthLabel(hijriDate) else hijri,
            fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold,
            color = GreenPrimaryDark, textAlign = TextAlign.Center,
        )
        Text(
            text = if (isToday) weekdayAndDate.format(date) else gregorian,
            fontSize = 13.sp, lineHeight = 18.sp,
            color = TextMuted, textAlign = TextAlign.Center,
        )
    }
}

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

@Composable
internal fun TimetableSourceDetails(methodName: String?, officialSource: Boolean, zoneName: String?) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(R.string.prayer_timetable_source)
    TextButton(
        onClick = { open = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("times_source_details"),
    ) { Text(label, color = GreenPrimaryDark) }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (methodName != null) {
                        Text(
                            if (officialSource) methodName else stringResource(R.string.today_calculation_method, methodName),
                            modifier = Modifier.testTag("today_calculation_method"),
                        )
                    }
                    if (zoneName != null) {
                        Text(stringResource(R.string.list_zone_line, zoneName), modifier = Modifier.testTag("times_zone_line"))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.prayer_control_close)) }
            },
        )
    }
}
