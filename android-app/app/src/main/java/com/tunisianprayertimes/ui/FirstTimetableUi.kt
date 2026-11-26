package com.tunisianprayertimes.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tunisianprayertimes.MainActivity
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhan.AdhanSettingsActivity
import com.tunisianprayertimes.engine.EventStatus
import com.tunisianprayertimes.engine.EventTime
import com.tunisianprayertimes.mosque.MosqueSettingsActivity
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.NextPrayerBg
import java.time.LocalDate
import java.time.ZoneId

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
            PrayerSettingsLink(
                title = stringResource(R.string.mosque_settings_title),
                onClick = { context.startActivity(Intent(context, MosqueSettingsActivity::class.java)) },
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
    onTimeClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    CompactTimetableRow(
        name = name,
        isNext = isNext,
        modifier = modifier.testTag("today_time_${prayer.name}"),
        markers = { if (anchor != null) PrayerTimeMarkers(name, anchor) },
    ) {
        if (anchor != null) PrayerSilenceClock(prayer, prayerClockText(anchor.minutes), onTimeClick)
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
    clock: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { }
            .background(if (isNext) NextPrayerBg else Color.Transparent)
            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = PrayerSilencePalette.PrimaryText)
            if (isNext) {
                Text(stringResource(R.string.prayer_timeline_up_next), fontSize = 12.sp, color = GreenPrimaryDark)
            }
            markers()
        }
        clock()
    }
}
