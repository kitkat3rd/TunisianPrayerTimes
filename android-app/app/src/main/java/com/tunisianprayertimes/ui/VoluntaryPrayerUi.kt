package com.tunisianprayertimes.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.CardBorder
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.PrayerNameColor
import com.tunisianprayertimes.ui.theme.TextMuted
import com.tunisianprayertimes.worship.NightPrayerWindow
import com.tunisianprayertimes.worship.VoluntaryPrayerTimes
import com.tunisianprayertimes.worship.VoluntaryPrayerWindow
import com.tunisianprayertimes.worship.WorshipReminder
import com.tunisianprayertimes.worship.voluntaryPrayerTimes
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private data class VoluntaryPrayerUiState(
    val now: Instant,
    val times: VoluntaryPrayerTimes?,
    val displayZone: ZoneId,
    val placeOffset: String?,
)

/** Reads the same saved place and method as the prayer tab, away from the UI thread. */
@Composable
internal fun VoluntaryPrayerTools(placeRevision: Int) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by produceState<VoluntaryPrayerUiState?>(null, context, lifecycle, placeRevision) {
        value = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val now = Instant.now()
                value = withContext(Dispatchers.IO) {
                    val phoneZone = ZoneId.systemDefault()
                    var placeOffset: String? = null
                    val times = try {
                        val place = PrayerTimesRepository.place(context)
                        // The phone's clock, as on the prayer tab (D8), with the place's offset when it keeps another.
                        placeOffset = placeOffsetLabel(place.zone, phoneZone, now)
                        voluntaryPrayerTimes(
                            now = now,
                            zone = place.zone,
                            dayFor = { PrayerTimesRepository.prayerDay(context, it, place) },
                            solarNoonFor = { date -> PrayerTimesRepository.solarNoon(context, date, place) },
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    VoluntaryPrayerUiState(now, times, phoneZone, placeOffset)
                }
                // Resume immediately refreshes clock, place, method and zone; midnight rolls over here too.
                delay(30_000L)
            }
        }
    }
    VoluntaryPrayerCards(
        times = state?.times,
        now = state?.now ?: Instant.now(),
        displayZone = state?.displayZone ?: ZoneId.systemDefault(),
        loading = state == null,
        placeOffset = state?.placeOffset,
    )
}

/** Stateless content also used to check compact Arabic layouts with a fixed clock. */
@Composable
internal fun VoluntaryPrayerCards(
    times: VoluntaryPrayerTimes?,
    now: Instant,
    displayZone: ZoneId,
    loading: Boolean = false,
    placeOffset: String? = null,
) {
    var explanation by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        placeOffset?.let { offset ->
            Text(
                stringResource(R.string.list_place_offset, offset),
                color = TextMuted, fontSize = 12.sp, lineHeight = 19.sp,
                modifier = Modifier.testTag("worship_place_offset"),
            )
        }
        Surface(shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, CardBorder), color = Color.White) {
            DuhaRow(times, now, displayZone, loading, onDetails = { explanation = "duha" })
        }
        Surface(shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, CardBorder), color = Color.White) {
            NightPrayerRow(times?.night, now, displayZone, loading, onDetails = { explanation = "night" })
        }
    }
    explanation?.let { kind -> WorshipTimeExplanation(duha = kind == "duha", onDismiss = { explanation = null }) }
}

@Composable
private fun DuhaRow(
    times: VoluntaryPrayerTimes?, now: Instant, displayZone: ZoneId, loading: Boolean, onDetails: () -> Unit,
) {
    val duha = times?.duha
    val tomorrow = times != null && times.duhaDate > times.date
    val dayLabel = stringResource(if (tomorrow) R.string.worship_tomorrow else R.string.worship_today)
    PrayerTimeRow(
        title = stringResource(R.string.worship_duha_title),
        reminder = WorshipReminder.DUHA, window = duha, now = now, displayZone = displayZone,
        icon = R.drawable.ic_adhkar_sun,
        period = when {
            duha == null -> null
            duha.isEstimated -> stringResource(R.string.worship_period_estimate, dayLabel)
            else -> dayLabel
        },
        tag = "worship_duha_card",
        detailsTag = "worship_duha_details",
        onDetails = onDetails,
        status = when {
            duha == null -> stringResource(if (loading) R.string.worship_times_loading else R.string.worship_duha_unavailable)
            tomorrow -> null
            now < duha.start -> stringResource(R.string.worship_duha_in, worshipCountdown(now, duha.start))
            now < duha.end -> stringResource(R.string.worship_duha_active, worshipCountdown(now, duha.end))
            else -> stringResource(R.string.worship_duha_ended)
        },
    ) {
        if (duha != null) {
            TimePoint(R.string.worship_duha_start, duha.start, displayZone)
            TimePoint(R.string.worship_duha_end, duha.end, displayZone)
        }
    }
}

@Composable
private fun NightPrayerRow(
    night: NightPrayerWindow?, now: Instant, displayZone: ZoneId, loading: Boolean, onDetails: () -> Unit,
) {
    // Where Isha falls after the last third starts, the two prayer milestones coincide.
    val lateIsha = night != null && night.lastThirdPrayerStart > night.lastThirdStart
    val status = when {
        night == null -> stringResource(if (loading) R.string.worship_times_loading else R.string.worship_night_unavailable)
        now < night.isha -> stringResource(R.string.worship_night_before_isha, worshipCountdown(now, night.isha))
        now < night.lastThirdPrayerStart -> stringResource(R.string.worship_third_in, worshipCountdown(now, night.lastThirdPrayerStart))
        now < night.fajr -> stringResource(R.string.worship_third_active, worshipCountdown(now, night.fajr))
        else -> stringResource(R.string.worship_night_ended)
    }
    PrayerTimeRow(
        title = stringResource(R.string.worship_night_title),
        reminder = WorshipReminder.QIYAM, window = night?.prayerWindow, now = now, displayZone = displayZone,
        lastThird = night?.lastThirdPrayerStart,
        icon = R.drawable.ic_prayer_isha_night,
        period = if (night != null) stringResource(R.string.worship_current_night) else null,
        tag = "worship_night_card",
        detailsTag = "worship_night_details",
        onDetails = onDetails,
        status = status,
        note = listOfNotNull(
            if (lateIsha && now < night.isha) stringResource(R.string.worship_late_isha) else null,
            if (night?.isEstimated == true) stringResource(R.string.worship_night_estimated) else null,
        ).joinToString("\n"),
    ) {
        if (night != null) {
            TimePoint(R.string.worship_isha_time, night.isha, displayZone)
            if (!lateIsha) TimePoint(R.string.worship_third_start, night.lastThirdStart, displayZone)
            TimePoint(R.string.worship_fajr_time, night.fajr, displayZone)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrayerTimeRow(
    title: String,
    reminder: WorshipReminder,
    window: VoluntaryPrayerWindow?,
    now: Instant,
    displayZone: ZoneId,
    lastThird: Instant? = null,
    icon: Int,
    period: String?,
    tag: String,
    detailsTag: String,
    onDetails: () -> Unit,
    status: String?,
    note: String = "",
    times: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag(tag).padding(start = 16.dp, end = 12.dp, bottom = 12.dp)) {
        // Give the title its full line when the reminder action would crowd it at large text sizes.
        val buttonBelow = maxWidth < 200.dp * LocalDensity.current.fontScale
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            WorshipReminderControl(reminder, window, now, displayZone, lastThird) { reminderButton ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FlowRow(Modifier.weight(1f).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(painterResource(icon), null, Modifier.size(19.dp), tint = GreenPrimary)
                            Text(title, color = PrayerNameColor, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                                lineHeight = 24.sp, modifier = Modifier.semantics { heading() })
                        }
                        period?.let { Text(it, color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp) }
                    }
                    AppInfoButton(title = stringResource(R.string.worship_times_details, title), onClick = onDetails,
                        modifier = Modifier.testTag(detailsTag), tint = TextMuted)
                    if (!buttonBelow) reminderButton()
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    times()
                    if (buttonBelow) reminderButton()
                }
            }
            status?.let { Text(it, color = TextMuted, fontSize = 13.sp, lineHeight = 20.sp) }
            if (note.isNotEmpty()) Text(note, color = TextMuted, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}

internal val worshipClockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

/** A time and its label wrap together when the reader enlarges the text. */
@Composable
private fun TimePoint(label: Int, at: Instant, zone: ZoneId) {
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), color = TextMuted, fontSize = 13.sp, lineHeight = 20.sp)
        Text(worshipClockFormat.format(at.atZone(zone)), color = PrayerNameColor, fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp)
    }
}

/** Time left in words («ساعتين و5 دقائق»), rounded up so a future boundary never reads as already reached. */
internal fun worshipCountdown(now: Instant, target: Instant): String =
    formatWakeCountdownDuration(Duration.between(now, target).toMillis().coerceAtLeast(0L))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorshipTimeExplanation(duha: Boolean, onDismiss: () -> Unit) {
    AppModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color.White) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag("worship_time_explanation"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(if (duha) R.string.worship_duha_title else R.string.worship_night_title),
                color = PrayerNameColor, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text(stringResource(if (duha) R.string.worship_duha_explanation else R.string.worship_night_explanation),
                color = PrayerNameColor, fontSize = 15.sp, lineHeight = 27.sp)
            Text(stringResource(if (duha) R.string.worship_duha_calculation else R.string.worship_night_calculation),
                color = TextMuted, fontSize = 14.sp, lineHeight = 25.sp)
            if (duha) {
                val context = LocalContext.current
                val uriHandler = LocalUriHandler.current
                TextButton(
                    onClick = {
                        runCatching {
                            uriHandler.openUri("https://binbaz.org.sa/fatwas/8356/ما-وقت-كل-من-صلاة-الضحى-وقيام-الليل")
                        }.onFailure {
                            Toast.makeText(context, context.getString(R.string.worship_source_failed), Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.worship_source_prayer_times), color = GreenPrimary)
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("worship_time_close")) {
                Text(stringResource(R.string.worship_times_close), color = GreenPrimary)
            }
        }
    }
}
