package com.tunisianprayertimes.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.R
import com.tunisianprayertimes.engine.SolarEvents
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.platform.PrayerDataLoader
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.PrayerNameColor
import com.tunisianprayertimes.ui.theme.TextMuted
import com.tunisianprayertimes.worship.VoluntaryPrayerTimes
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

/** The last third's part of the bar, and its label in a deeper gold that reads on white (5.3:1). */
private val BestTimeGold = Color(0xFFB8860B)
private val BestTimeLabel = Color(0xFF8A6508)

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
                        val longitude = when (place) {
                            is PrayerPlace.Inm -> PrayerDataLoader.prayerTimes(context).location(place.delegationId)?.longitude
                            is PrayerPlace.Coordinates -> place.point.longitude
                        }
                        voluntaryPrayerTimes(
                            now = now,
                            zone = place.zone,
                            dayFor = { PrayerTimesRepository.prayerDay(context, it, place) },
                            solarNoonFor = { date -> longitude?.let { SolarEvents.transit(date, place.zone, it) } },
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
        WorshipTimeCard(
            title = stringResource(R.string.worship_duha_title),
            subtitle = stringResource(R.string.worship_duha_subtitle),
            icon = R.drawable.ic_adhkar_sun,
            tag = "worship_duha_card",
        ) {
            val duha = times?.duha
            if (duha == null) {
                UnavailableWorshipTime(loading, R.string.worship_duha_unavailable)
            } else {
                val tomorrow = times.duhaDate > times.date
                WorshipPeriodLabel(stringResource(if (tomorrow) R.string.worship_tomorrow else R.string.worship_today))
                WorshipTimePoints(
                    listOf(
                        WorshipTimePoint(stringResource(R.string.worship_duha_start), duha.start),
                        WorshipTimePoint(stringResource(R.string.worship_duha_end), duha.end),
                    ),
                    zone = displayZone,
                )
                // One period with nothing to mark inside it: the line says something only while it is under way.
                if (!tomorrow && now >= duha.start && now < duha.end) {
                    WorshipTimeBar(start = duha.start, end = duha.end, bestFrom = null, now = now)
                }
                WorshipTimeStatus(
                    text = when {
                        tomorrow -> stringResource(R.string.worship_duha_tomorrow)
                        now < duha.start -> stringResource(R.string.worship_duha_in, worshipCountdown(now, duha.start))
                        now < duha.end -> stringResource(R.string.worship_duha_active, worshipCountdown(now, duha.end))
                        else -> stringResource(R.string.worship_duha_ended)
                    },
                    active = !tomorrow && now >= duha.start && now < duha.end,
                )
            }
            WorshipExplanationButton("worship_duha_details") { explanation = "duha" }
        }
        WorshipTimeCard(
            title = stringResource(R.string.worship_night_title),
            subtitle = stringResource(R.string.worship_night_subtitle),
            icon = R.drawable.ic_prayer_isha_night,
            tag = "worship_night_card",
        ) {
            val night = times?.night
            if (night == null) {
                UnavailableWorshipTime(loading, R.string.worship_night_unavailable)
            } else {
                // Isha after the third has begun (far north in summer): the whole time from Isha is the last third,
                // so there is no third time to show between the two; the note says why.
                val lateIsha = night.lastThirdPrayerStart > night.lastThirdStart
                WorshipPeriodLabel(stringResource(R.string.worship_current_night))
                WorshipTimePoints(
                    listOfNotNull(
                        WorshipTimePoint(stringResource(R.string.worship_isha_time), night.isha),
                        WorshipTimePoint(stringResource(R.string.worship_third_start), night.lastThirdStart, best = true)
                            .takeUnless { lateIsha },
                        WorshipTimePoint(stringResource(R.string.worship_fajr_time), night.fajr),
                    ),
                    zone = displayZone,
                )
                WorshipTimeBar(start = night.isha, end = night.fajr, bestFrom = night.lastThirdPrayerStart, now = now)
                WorshipTimeStatus(
                    text = when {
                        now < night.isha -> stringResource(R.string.worship_night_before_isha, worshipCountdown(now, night.isha))
                        now < night.lastThirdPrayerStart ->
                            stringResource(R.string.worship_third_in, worshipCountdown(now, night.lastThirdPrayerStart))
                        now < night.fajr -> stringResource(R.string.worship_third_active, worshipCountdown(now, night.fajr))
                        else -> stringResource(R.string.worship_night_ended)
                    },
                    active = now >= night.isha && now < night.fajr,
                )
                if (lateIsha && now < night.isha) {
                    Text(stringResource(R.string.worship_late_isha), color = TextMuted, fontSize = 12.sp, lineHeight = 20.sp)
                }
                if (night.isEstimated) {
                    Text(stringResource(R.string.worship_night_estimated), color = TextMuted, fontSize = 12.sp, lineHeight = 20.sp)
                }
            }
            WorshipExplanationButton("worship_night_details") { explanation = "night" }
        }
    }
    explanation?.let { kind -> WorshipTimeExplanation(duha = kind == "duha", onDismiss = { explanation = null }) }
}

@Composable
private fun WorshipTimeCard(
    title: String,
    subtitle: String,
    icon: Int,
    tag: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(18.dp), color = Color.White,
        border = BorderStroke(1.dp, Gold.copy(alpha = .25f)),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(icon), contentDescription = null, tint = GreenPrimary, modifier = Modifier.size(24.dp))
                    Text(title, color = PrayerNameColor, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).semantics { heading() })
                }
                Text(subtitle, color = TextMuted, fontSize = 13.sp, lineHeight = 21.sp)
            }
            content()
        }
    }
}

/** «اليوم», «غدًا» or «الليلة»: which day or night the times below belong to. */
@Composable
private fun WorshipPeriodLabel(text: String) {
    Text(text, color = GreenPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

private data class WorshipTimePoint(val label: String, val at: Instant, val best: Boolean = false)

/**
 * The times side by side, the first above the bar's start and the last above its end. When they do not fit on one
 * line (large text on a narrow phone) they go one under the other, all at the start: wrapped, a time would stand
 * above the wrong end of the bar.
 */
@Composable
private fun WorshipTimePoints(points: List<WorshipTimePoint>, zone: ZoneId) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = { points.forEach { point -> WorshipTimePointColumn(point, zone) } },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val gap = 12.dp.roundToPx()
        val width = constraints.maxWidth
        val oneLine = placeables.sumOf { it.width } + gap * (placeables.size - 1).coerceAtLeast(0) <= width
        if (oneLine) {
            val free = width - placeables.sumOf { it.width }
            val between = if (placeables.size > 1) free / (placeables.size - 1) else 0
            layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
                var x = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(x, 0)
                    x += placeable.width + between
                }
            }
        } else {
            val rowGap = 10.dp.roundToPx()
            val height = placeables.sumOf { it.height } + rowGap * (placeables.size - 1).coerceAtLeast(0)
            layout(width, height) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + rowGap
                }
            }
        }
    }
}

@Composable
private fun WorshipTimePointColumn(point: WorshipTimePoint, zone: ZoneId) {
    // TalkBack reads a label and its time together.
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(point.label, color = if (point.best) BestTimeLabel else TextMuted, fontSize = 12.sp,
            fontWeight = if (point.best) FontWeight.SemiBold else FontWeight.Normal)
        Text(point.at.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.US)),
            color = GreenPrimaryDark, fontWeight = FontWeight.Bold, fontSize = 24.sp)
    }
}

/**
 * The period as a line from [start] to [end] (from the right in Arabic), its best part from [bestFrom] in gold,
 * and a dot where now falls inside it. Decorative: the status line under it says the same in words.
 */
@Composable
private fun WorshipTimeBar(start: Instant, end: Instant, bestFrom: Instant?, now: Instant) {
    val total = Duration.between(start, end).toMillis().toFloat().coerceAtLeast(1f)
    fun fraction(at: Instant) = (Duration.between(start, at).toMillis() / total).coerceIn(0f, 1f)
    val best = bestFrom?.let(::fraction)
    val nowAt = if (now >= start && now < end) fraction(now) else null
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val radius = 7.dp.toPx()
        val y = size.height / 2
        val stroke = 6.dp.toPx()
        val usable = size.width - 2 * radius
        fun x(f: Float) = radius + usable * (if (rtl) 1f - f else f)
        drawLine(GreenPrimary.copy(alpha = .22f), Offset(x(0f), y), Offset(x(1f), y), stroke, StrokeCap.Round)
        if (best != null && best < 1f) {
            drawLine(BestTimeGold.copy(alpha = .75f), Offset(x(best), y), Offset(x(1f), y), stroke, StrokeCap.Round)
        }
        nowAt?.let {
            drawCircle(Color.White, radius = radius, center = Offset(x(it), y))
            drawCircle(GreenPrimaryDark, radius = radius - 2.dp.toPx(), center = Offset(x(it), y))
        }
    }
}

@Composable
private fun WorshipTimeStatus(text: String, active: Boolean) {
    Text(
        text, color = if (active) GreenPrimaryDark else TextMuted, fontSize = 13.sp, lineHeight = 21.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth()
            .background(if (active) GoldLight.copy(alpha = .45f) else Color(0xFFF7F8F6), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun UnavailableWorshipTime(loading: Boolean, message: Int) {
    Text(stringResource(if (loading) R.string.worship_times_loading else message), color = TextMuted, fontSize = 14.sp, lineHeight = 23.sp)
}

@Composable
private fun WorshipExplanationButton(tag: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = ButtonDefaults.TextButtonContentPadding,
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
    ) {
        Icon(painterResource(R.drawable.ic_adhkar_info), contentDescription = null, tint = GreenPrimary,
            modifier = Modifier.padding(end = 6.dp).size(18.dp))
        Text(stringResource(R.string.worship_times_details), color = GreenPrimary, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
    }
}

/** Time left in words («ساعتين و5 دقائق»), rounded up so a future boundary never reads as already reached. */
internal fun worshipCountdown(now: Instant, target: Instant): String =
    formatWakeCountdownDuration(Duration.between(now, target).toMillis().coerceAtLeast(0L))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorshipTimeExplanation(duha: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val sourceUrl = if (duha) "https://binbaz.org.sa/fatwas/8356/ما-وقت-كل-من-صلاة-الضحى-وقيام-الليل"
        else "https://binbaz.org.sa/fatwas/17595/كيفية-تقسيم-الليل-لمعرفة-الثلث-الاخير"
    fun openSource(url: String) {
        runCatching { uriHandler.openUri(url) }.onFailure {
            Toast.makeText(context, context.getString(R.string.worship_source_failed), Toast.LENGTH_SHORT).show()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color.White) {
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
            TextButton(onClick = { openSource(sourceUrl) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (duha) R.string.worship_source_prayer_times else R.string.worship_source_thirds), color = GreenPrimary)
            }
            if (!duha) TextButton(
                onClick = { openSource("https://binbaz.org.sa/fatwas/8356/ما-وقت-كل-من-صلاة-الضحى-وقيام-الليل") },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.worship_source_prayer_times), color = GreenPrimary)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("worship_time_close")) {
                Text(stringResource(R.string.worship_times_close), color = GreenPrimary)
            }
        }
    }
}
