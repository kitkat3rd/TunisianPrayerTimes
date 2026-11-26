package com.tunisianprayertimes.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tunisianprayertimes.MainActivity
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhan.AdhanSettingsActivity
import com.tunisianprayertimes.engine.EventStatus
import com.tunisianprayertimes.engine.EventTime
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.NextPrayerBg
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Null is sunrise. Unavailable times retain their conventional slot; dated events follow their actual instants. */
internal fun prayerTimetableOrder(
    date: LocalDate,
    anchors: Map<Prayer, PrayerTimelineAnchor>,
    sunrise: Instant?,
    showFitr: Boolean,
    showAdha: Boolean,
): List<Prayer?> {
    val rows = buildList<Prayer?> {
        add(Prayer.FAJR)
        add(null)
        if (showFitr && anchors[Prayer.AID_FITR]?.prayerDay == date) add(Prayer.AID_FITR)
        if (showAdha && anchors[Prayer.AID_ADHA]?.prayerDay == date) add(Prayer.AID_ADHA)
        add(if (date.dayOfWeek == DayOfWeek.FRIDAY) Prayer.JOMOAA else Prayer.DHUHR)
        add(Prayer.ASR)
        add(Prayer.MAGHRIB)
        add(Prayer.ISHA)
    }
    fun at(prayer: Prayer?): Instant? = if (prayer == null) sunrise else anchors[prayer]?.adhan
    val knownTimes = rows.filter { at(it) != null }.sortedBy(::at).iterator()
    return rows.map { if (at(it) == null) it else knownTimes.next() }
}

@Composable
internal fun timetableSilenceSummary(
    anchor: PrayerTimelineAnchor,
    config: PrayerSilenceConfig,
    enabled: Boolean,
    ready: Boolean,
    mosqueMode: Boolean,
): String {
    if (!enabled) return stringResource(R.string.timetable_silence_disabled)
    val ends = remember(anchor, config) { silenceEndsAt(anchor, config) }
    if (ends.end <= ends.start) return stringResource(R.string.prayer_timeline_invalid_window)
    val (start, end) = silenceWindowClocks(anchor, ends.start, ends.end)
    val label = when {
        !ready -> R.string.timetable_saved_window
        mosqueMode -> R.string.timetable_mosque_window
        else -> R.string.timetable_silence_window
    }
    return stringResource(label, start, end)
}

@Composable
internal fun silenceWindowClocks(anchor: PrayerTimelineAnchor, start: Instant, end: Instant): Pair<String, String> {
    val showOffset = anchor.displayZone.rules.getOffset(start) != anchor.displayZone.rules.getOffset(end)
    return silenceEndpointClock(anchor, start, showOffset) to silenceEndpointClock(anchor, end, showOffset)
}

/** A scrollable Column also composes off-screen rows. Count only a visible time in the foreground. */
@Composable
internal fun firstTimetableRowModifier(activity: AppCompatActivity, displayedPlace: PrayerPlace, confirmed: Boolean): Modifier {
    val session = (activity as? MainActivity)?.firstRunAnalytics ?: return Modifier
    var visible by remember(displayedPlace) { mutableStateOf(false) }
    val minVisibleHeight = with(LocalDensity.current) { 24.dp.toPx() }
    if (visible && LocalWindowInfo.current.isWindowFocused) {
        LifecycleResumeEffect(session, displayedPlace, confirmed) {
            session.timetableShown(activity, displayedPlace, confirmed, hasPrayerTime = true)
            onPauseOrDispose { }
        }
    }
    return Modifier.onGloballyPositioned { coordinates ->
        val clipped = coordinates.boundsInWindow()
        visible = clipped.width > 0 && clipped.height >= minVisibleHeight
    }
}

@Composable
internal fun FirstRunFeatureSuggestion() {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(PrefsManager.isFirstRunSetupSuggested(context)) }
    if (!visible) return
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("first_timetable_setup")) {
        PrayerSettingsGroup(title = stringResource(R.string.today_optional_setup)) {
            PrayerSettingsLink(
                title = stringResource(R.string.today_setup_adhan),
                onClick = { context.startActivity(Intent(context, AdhanSettingsActivity::class.java)) },
            )
            PrayerSettingsDivider()
            TextButton(
                onClick = {
                    PrefsManager.dismissFirstRunSetup(context)
                    visible = false
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.today_dismiss_setup), color = GreenPrimaryDark)
            }
        }
    }
}

@Composable
internal fun CompactPrayerTimeRow(
    prayer: Prayer,
    name: String,
    anchor: PrayerTimelineAnchor?,
    isNext: Boolean,
    onOpenEditor: (() -> Unit)?,
    modifier: Modifier = Modifier,
    silenceSummary: String? = null,
) {
    CompactTimetableRow(
        name = name,
        isNext = isNext,
        modifier = modifier.testTag("today_time_${prayer.name}"),
        markers = { if (anchor != null) PrayerTimeMarkers(name, anchor, explainOnClick = onOpenEditor == null) },
        onOpenEditor = onOpenEditor,
        silenceSummary = silenceSummary,
    ) {
        if (anchor != null) PrayerSilenceClock(prayer, prayerClockText(anchor.minutes), null)
        else Text(stringResource(R.string.today_time_missing), color = PrayerSilencePalette.SecondaryText)
    }
}

@Composable
internal fun CompactSunriseRow(event: EventTime?, date: LocalDate, phoneZone: ZoneId) {
    val time = event?.at?.atZone(phoneZone)
    val name = stringResource(R.string.adhkar_shuruk)
    CompactTimetableRow(
        name = name,
        isNext = false,
        modifier = Modifier.testTag("today_time_SUNRISE"),
        markers = {
            if (time != null) {
                val dayOffset = (time.toLocalDate().toEpochDay() - date.toEpochDay()).toInt()
                PrayerTimeMarkers(name, "SUNRISE", dayOffset, event.status == EventStatus.ESTIMATED, event.rule)
            }
        },
    ) {
        Text(
            text = time?.let { prayerClockText(it.hour * 60 + it.minute) } ?: stringResource(R.string.today_time_missing),
            color = PrayerSilencePalette.SecondaryText,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun CompactTimetableRow(
    name: String,
    isNext: Boolean,
    modifier: Modifier,
    markers: @Composable () -> Unit,
    onOpenEditor: (() -> Unit)? = null,
    silenceSummary: String? = null,
    clock: @Composable () -> Unit,
) {
    val editLabel = stringResource(R.string.timetable_edit_prayer, name)
    Column(
        modifier = modifier.fillMaxWidth()
            .background(if (isNext) NextPrayerBg else Color.Transparent)
            .then(if (onOpenEditor == null) Modifier.semantics(mergeDescendants = true) { }
                else Modifier.clickable(role = Role.Button, onClickLabel = editLabel, onClick = onOpenEditor))
            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(7.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = PrayerSilencePalette.PrimaryText)
                if (isNext) {
                    Text(stringResource(R.string.prayer_timeline_up_next), fontSize = 12.sp, color = GreenPrimaryDark)
                }
                markers()
            }
            clock()
            if (onOpenEditor != null) {
                Icon(painterResource(R.drawable.ic_qibla_chevron), null, Modifier.size(16.dp), tint = PrayerSilencePalette.SecondaryText)
            } else {
                Spacer(Modifier.size(16.dp))
            }
        }
        if (silenceSummary != null) {
            Text(silenceSummary, Modifier.fillMaxWidth(), fontSize = 12.sp, lineHeight = 17.sp, color = PrayerSilencePalette.SecondaryText)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimetablePrayerEditorSheet(
    onDismiss: () -> Unit,
    otherDate: LocalDate?,
    content: @Composable () -> Unit,
) {
    val title = stringResource(R.string.timetable_silence_settings)
    AppModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PrayerSilencePalette.Surface,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).semantics { paneTitle = title }) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).semantics { heading() }, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, color = PrayerSilencePalette.PrimaryText)
                IconButton(onClick = onDismiss) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.prayer_control_close),
                        tint = PrayerSilencePalette.SecondaryText)
                }
            }
            if (otherDate != null) {
                val formatted = remember(otherDate) {
                    otherDate.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.forLanguageTag("ar-TN")))
                }
                Text(formatted, Modifier.padding(horizontal = 16.dp), color = PrayerSilencePalette.SecondaryText)
            }
            content()
        }
    }
}
