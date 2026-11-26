package com.tunisianprayertimes.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.MethodChip
import com.tunisianprayertimes.MethodPresentation
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.engine.CustomMethodRanges
import com.tunisianprayertimes.engine.HighLatitudeRule
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.method.FajrRule
import com.tunisianprayertimes.method.IshaRule
import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.method.PrayerMethodChoice
import com.tunisianprayertimes.method.PrayerMethods
import com.tunisianprayertimes.method.ZonePick
import com.tunisianprayertimes.place.PlaceTimes
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

@Composable
internal fun PrayerMethodSection(
    place: PrayerPlace.Coordinates,
    onTimesChanged: () -> Unit,
    onShowInmExample: () -> Unit,
) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var sheet by remember(place.cacheKey) { mutableStateOf<String?>(null) }
    val choice = remember(place, revision) {
        PrayerTimesRepository.choice(context, place) ?: PrayerMethodChoice(PrayerMethods.MWL.id)
    }
    val placeTimes = remember { PrayerTimesRepository.placeTimesFor(context) }
    val method = remember(place, choice) { placeTimes.method(place, choice) }
    val year = LocalDate.now(place.zone).year
    val mapped = remember(place, choice, year) {
        val mappedChoice = choice.copy(zonePick = choice.zonePick.takeUnless { it == ZonePick.Exact })
        runCatching { placeTimes.mapping(place, mappedChoice, year) }.getOrNull()
    }
    fun save(next: PrayerMethodChoice) {
        PrefsManager.setMethodChoice(context, place, next)
        revision++
        onTimesChanged()
    }

    PrayerSettingsGroup {
        PrayerSettingsLink(
            title = stringResource(R.string.prayer_settings_choose_method),
            summary = MethodPresentation.name(method.id),
            onClick = { sheet = "methods" },
        )
        if (mapped != null) {
            PrayerSettingsDivider()
            val useExactLocation = choice.zonePick == ZonePick.Exact
            PrayerSettingsSwitch(
                title = stringResource(R.string.prayer_settings_exact_location),
                summary = if (useExactLocation) null else MethodPresentation.zoneLine(method.id, mapped, place),
                checked = useExactLocation,
                onCheckedChange = { exact -> save(choice.copy(zonePick = if (exact) ZonePick.Exact else null)) },
            )
        }
        PrayerSettingsDivider()
        AsrRow(factor = choice.asrShadow ?: method.asrShadow, onFactor = { f -> save(choice.copy(asrShadow = f)) })
        if (kotlin.math.abs(place.point.latitude) >= HIGH_LATITUDE_FROM) {
            PrayerSettingsDivider()
            PrayerSettingsLink(
                title = "الليالي القصيرة",
                summary = highLatitudeName(choice.highLatitude ?: method.highLatitude),
                onClick = { sheet = "nights" },
            )
        }
        PrayerSettingsDivider()
        PrayerSettingsLink(
            title = "تعديل الأوقات بالدقائق",
            summary = adjustmentsSummary(choice.adjustments),
            onClick = { sheet = "adjust" },
        )
    }

    PrayerSettingsGroup(title = stringResource(R.string.prayer_settings_about_calculation)) {
        PrayerSettingsLink(
            title = stringResource(R.string.prayer_settings_method_details),
            onClick = { sheet = "explainer" },
        )
        PrayerSettingsDivider()
        PrayerSettingsLink(
            title = stringResource(R.string.prayer_method_entry_example_title),
            modifier = Modifier.testTag(TestTags.PRAYER_TIME_METHOD_ROW),
            onClick = onShowInmExample,
        )
    }

    when (sheet) {
        "methods" -> PrayerMethodSheet(
            place = place,
            current = choice,
            placeTimes = placeTimes,
            onPick = { id ->
                sheet = null
                if (id == PrayerMethod.CUSTOM_ID) sheet = "custom"
                else save(PrayerMethodChoice(id, asrShadow = choice.asrShadow, adjustments = choice.adjustments, chooserAnswered = true, methodPicked = true))
            },
            onDismiss = { sheet = null },
        )
        "custom" -> CustomMethodSheet(
            base = method,
            mainlandFrance = place.isMainlandFrance,
            onSave = { custom ->
                sheet = null
                save(PrayerMethodChoice(PrayerMethod.CUSTOM_ID, adjustments = choice.adjustments, custom = custom, chooserAnswered = true, methodPicked = true))
            },
            onDismiss = { sheet = null },
        )
        "adjust" -> AdjustmentsSheet(
            adjustments = choice.adjustments,
            onChange = { event, minutes -> save(choice.withAdjustment(event, minutes)) },
            onDismiss = { sheet = null },
        )
        "explainer" -> MethodExplainerSheet(place = place, choice = choice, onDismiss = { sheet = null })
        "nights" -> HighLatitudeSheet(
            current = choice.highLatitude ?: method.highLatitude,
            onPick = { rule -> sheet = null; save(choice.copy(highLatitude = rule)) },
            onDismiss = { sheet = null },
        )
    }
}

/** Where a method's own short-night rule can act: Diyanet's takdir, the first, starts at 44.375° N (README H6). */
private const val HIGH_LATITUDE_FROM = 44.375

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AsrRow(factor: Int, onFactor: (Int) -> Unit) {
    val chipColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = PrayerSilencePalette.TintedStrip,
        selectedLabelColor = PrayerSilencePalette.PrimaryText,
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text("وقت العصر", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = factor == 1, onClick = { onFactor(1) }, label = { Text("الجمهور") }, colors = chipColors)
            FilterChip(selected = factor == 2, onClick = { onFactor(2) }, label = { Text("الحنفية") }, colors = chipColors)
        }
    }
}

private fun adjustmentsSummary(adjustments: Map<TimeEvent, Int>): String =
    if (adjustments.isEmpty()) "بلا تعديل"
    else TimeEvent.entries.mapNotNull { e -> adjustments[e]?.let { eventName(e) + " " + MethodPresentation.signedMinutes(it) } }.joinToString("، ")

internal fun eventName(event: TimeEvent): String = when (event) {
    TimeEvent.FAJR -> "الفجر"
    TimeEvent.SUNRISE -> "الشروق"
    TimeEvent.DHUHR -> "الظهر"
    TimeEvent.ASR -> "العصر"
    TimeEvent.MAGHRIB -> "المغرب"
    TimeEvent.ISHA -> "العشاء"
}

internal fun highLatitudeName(rule: HighLatitudeRule): String = when (rule) {
    is HighLatitudeRule.AngleBased -> "نسبة الزاوية من الليل كل ليلة"
    is HighLatitudeRule.AngleBasedAbsentNights -> "نسبة الزاوية من الليل حين تغيب الزاوية"
    is HighLatitudeRule.LocalRelativeEstimation -> "التقدير النسبي المحلي"
    is HighLatitudeRule.OneSeventh -> "سُبع الليل"
    is HighLatitudeRule.MiddleOfNight -> "منتصف الليل"
    is HighLatitudeRule.NearestDay -> "أقرب يوم تظهر فيه العلامة"
    is HighLatitudeRule.ClockCaps -> "حدود بالساعة"
    is HighLatitudeRule.MoonsightingSeasonal -> "منحنى لجنة رؤية الهلال"
    else -> "قاعدة الطريقة"
}

/** The every-night rules move real times (6.11): offered last, with that warning. */
private val CHOOSABLE_RULES: List<Pair<HighLatitudeRule, Boolean>> = listOf(
    HighLatitudeRule.AngleBasedAbsentNights to false,
    HighLatitudeRule.LocalRelativeEstimation to false,
    HighLatitudeRule.OneSeventh to false,
    HighLatitudeRule.MiddleOfNight() to false,
    HighLatitudeRule.NearestDay() to false,
    HighLatitudeRule.AngleBased to true,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HighLatitudeSheet(current: HighLatitudeRule, onPick: (HighLatitudeRule) -> Unit, onDismiss: () -> Unit) {
    AppModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("الليالي القصيرة", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextDark)
            CHOOSABLE_RULES.forEach { (rule, everyNight) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(rule) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(highLatitudeName(rule), fontSize = 15.sp, color = TextDark,
                            fontWeight = if (rule::class == current::class) FontWeight.Bold else FontWeight.Normal)
                        if (everyNight) Text("تُطبَّق كل ليلة، وقد تغيّر أوقاتًا حقيقية.", fontSize = 12.sp, color = TextMuted)
                    }
                }
                HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
            }
        }
    }
}

/**
 * `PrayerMethodSheet` (6.11): «الشائعة في بلدك» (the country's list, 6.4), «طرق أخرى», and «مخصّصة»; each entry with
 * its parameters, its chip and today's Fajr, Maghrib and Isha at the place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrayerMethodSheet(
    place: PrayerPlace.Coordinates,
    current: PrayerMethodChoice,
    placeTimes: PlaceTimes,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val defaults = remember(place) { placeTimes.defaults(place) }
    val country = defaults.entries.mapNotNull(PrayerMethods::byId)
    // Only the methods followed at the place (owner, 2026-10-18): never Slovenia's in Riyadh or New York.
    val others = remember(place) { placeTimes.offeredMethods(place).filter { m -> country.none { it.id == m.id } } }
    val today = LocalDate.now(place.zone)
    AppModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding().testTag("prayer_method_list")) {
            item { SectionTitle("الشائعة في بلدك") }
            items(country, key = { "c:" + it.id }) { m ->
                MethodEntry(place, m, today, current.methodId == m.id, placeTimes,
                    inferred = defaults.inferred && m.id == defaults.fallback, onPick = onPick)
            }
            item { SectionTitle("طرق أخرى") }
            items(others, key = { "o:" + it.id }) { m ->
                MethodEntry(place, m, today, current.methodId == m.id, placeTimes, inferred = false, onPick = onPick)
            }
            item { SectionTitle("مخصّصة") }
            item { CustomMethodEntry(place, current, placeTimes, today, onPick) }
        }
    }
}

/**
 * «مخصّصة» (6.11): the bounded editor's entry under its section title, laid out as the other entries; the user's own
 * method, its parameters and today's times when it is the current one.
 */
@Composable
private fun CustomMethodEntry(place: PrayerPlace.Coordinates, current: PrayerMethodChoice, placeTimes: PlaceTimes, today: LocalDate, onPick: (String) -> Unit) {
    val custom = current.custom?.takeIf { current.methodId == PrayerMethod.CUSTOM_ID }
    val day = remember(custom, today) { custom?.let { runCatching { placeTimes.day(place, current, today) }.getOrNull() } }
    Column(Modifier.fillMaxWidth().clickable { onPick(PrayerMethod.CUSTOM_ID) }.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(
            "تعديل الزوايا والدقائق", fontSize = 15.sp, color = if (custom != null) GreenPrimaryDark else TextDark,
            fontWeight = if (custom != null) FontWeight.Bold else FontWeight.Normal,
        )
        if (custom != null) MethodDetails(custom, day)
        else Text("ضمن الأقوال المعتبرة", fontSize = 12.sp, color = PrayerSilencePalette.SecondaryText)
    }
}

/**
 * An entry's details: the method's parameters, then today's Fajr, Maghrib and Isha in the phone's clock (owner,
 * 2026-10-19: all three). Two blocks on one line where they fit, the times under the parameters where they do not;
 * each label stays with its time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MethodDetails(method: PrayerMethod, day: com.tunisianprayertimes.engine.PrayerDay?) {
    val zone = ZoneId.systemDefault()
    fun clock(event: TimeEvent) = day?.get(event)?.at?.atZone(zone)?.let { String.format(Locale.US, "⁦%02d:%02d⁩", it.hour, it.minute) } ?: "—"
    val times = listOf("الفجر" to TimeEvent.FAJR, "المغرب" to TimeEvent.MAGHRIB, "العشاء" to TimeEvent.ISHA)
        .joinToString(" · ") { (label, event) -> label + " " + clock(event) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(MethodPresentation.parameters(method), fontSize = 12.sp, color = PrayerSilencePalette.SecondaryText)
        Text(times, fontSize = 12.sp, color = PrayerSilencePalette.SecondaryText)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = GreenPrimary, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp))
}

@Composable
internal fun MethodEntry(
    place: PrayerPlace.Coordinates,
    method: PrayerMethod,
    today: LocalDate,
    selected: Boolean,
    placeTimes: PlaceTimes,
    inferred: Boolean,
    onPick: (String) -> Unit,
) {
    val choice = PrayerMethodChoice(method.id)
    val day = remember(method.id, today) { runCatching { placeTimes.day(place, choice, today) }.getOrNull() }
    val mapped = remember(method.id) { runCatching { placeTimes.mapping(place, choice, today.year) }.getOrNull() }
    val chip = MethodPresentation.chip(method, place, mapped)
    Column(
        Modifier.fillMaxWidth().clickable { onPick(method.id) }.padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                MethodPresentation.name(method.id), fontSize = 15.sp, color = if (selected) GreenPrimaryDark else TextDark,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f),
            )
            Text(chip.labelAr, fontSize = 11.sp, color = if (chip == MethodChip.OFFICIAL) GreenPrimary else TextMuted)
        }
        MethodDetails(method, day)
        if (inferred) Text("مقترحة بالقياس على البلد المجاور، ولم نجد بعدُ جدولًا محليًا.", fontSize = 11.sp, color = TextMuted)
    }
    HorizontalDivider(color = PrayerSilencePalette.SoftBorder, modifier = Modifier.padding(horizontal = 20.dp))
}

/** «تعديل الأوقات بالدقائق»: six steppers, −30 to +30 (6.11). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdjustmentsSheet(adjustments: Map<TimeEvent, Int>, onChange: (TimeEvent, Int) -> Unit, onDismiss: () -> Unit) {
    AppModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("تعديل الأوقات بالدقائق", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextDark)
            TimeEvent.entries.forEach { event ->
                val value = adjustments[event] ?: 0
                Stepper(eventName(event), value, PrayerMethodChoice.ADJUSTMENT_RANGE, { onChange(event, it) }, signed = true)
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onValue: (Int) -> Unit, signed: Boolean = false, unit: String = "د") {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = TextDark, modifier = Modifier.weight(1f))
        TextButton(onClick = { if (value - 1 in range) onValue(value - 1) }, enabled = value - 1 in range) { Text("−") }
        Text(
            (if (signed) MethodPresentation.signedMinutes(value) else "⁦$value⁩") + " " + unit,
            fontSize = 15.sp, color = TextDark, modifier = Modifier.width(72.dp),
        )
        TextButton(onClick = { if (value + 1 in range) onValue(value + 1) }, enabled = value + 1 in range) { Text("+") }
    }
}

/**
 * «مخصّصة»: the bounded editor of 6.11. Only matters of ijtihad, each inside the positions recognised bodies hold;
 * the astronomy stays the engine's. It starts from the current method; E19 checks the result wherever it is used.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomMethodSheet(base: PrayerMethod, mainlandFrance: Boolean, onSave: (PrayerMethod) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val fajrAngles = if (mainlandFrance) CustomMethodRanges.FRANCE_FAJR_ANGLES else CustomMethodRanges.FAJR_ANGLES
    fun clampAngle(x: Double, r: ClosedFloatingPointRange<Double>) = (Math.round(x * 2) / 2.0).coerceIn(r.start, r.endInclusive)
    var fajrByMinutes by remember { mutableStateOf(base.fajr is FajrRule.MinutesBeforeSunrise) }
    var fajrAngle by remember { mutableStateOf(clampAngle((base.fajr as? FajrRule.Angle)?.degrees ?: 18.0, fajrAngles)) }
    var fajrMinutes by remember { mutableIntStateOf(((base.fajr as? FajrRule.MinutesBeforeSunrise)?.minutes ?: 90).coerceIn(CustomMethodRanges.FAJR_MINUTES)) }
    var ishaByMinutes by remember { mutableStateOf(base.isha is IshaRule.MinutesAfter) }
    var ishaAngle by remember { mutableStateOf(clampAngle((base.isha as? IshaRule.Angle)?.degrees ?: 17.0, CustomMethodRanges.ISHA_ANGLES)) }
    var ishaMinutes by remember { mutableIntStateOf(((base.isha as? IshaRule.MinutesAfter)?.minutes ?: 90).coerceIn(CustomMethodRanges.ISHA_MINUTES)) }
    var ishaEarliest by remember { mutableStateOf(base.ishaEarliest) }
    var asr by remember { mutableIntStateOf(base.asrShadow) }
    var dhuhr by remember { mutableIntStateOf((base.offset(TimeEvent.DHUHR)).coerceIn(CustomMethodRanges.DHUHR_MINUTES)) }
    var maghrib by remember { mutableIntStateOf((base.offset(TimeEvent.MAGHRIB)).coerceIn(CustomMethodRanges.MAGHRIB_MINUTES)) }
    var sunrise by remember { mutableIntStateOf((base.offset(TimeEvent.SUNRISE)).coerceIn(CustomMethodRanges.SUNRISE_MINUTES)) }

    fun build(): PrayerMethod = PrayerMethod(
        id = PrayerMethod.CUSTOM_ID,
        fajr = if (fajrByMinutes) FajrRule.MinutesBeforeSunrise(fajrMinutes) else FajrRule.Angle(fajrAngle),
        isha = if (ishaByMinutes) IshaRule.MinutesAfter(ishaMinutes) else IshaRule.Angle(ishaAngle),
        ishaEarliest = ishaEarliest,
        asrShadow = asr,
        offsets = mapOf(TimeEvent.DHUHR to dhuhr, TimeEvent.MAGHRIB to maghrib, TimeEvent.SUNRISE to sunrise).filterValues { it != 0 },
        highLatitude = base.highLatitude,
        polar = base.polar,
        evidence = com.tunisianprayertimes.method.Evidence(com.tunisianprayertimes.method.EvidenceKind.PUBLISHED, "custom"),
    )

    AppModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("طريقة مخصّصة", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextDark)
            Text("الفجر", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDark, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !fajrByMinutes, onClick = { fajrByMinutes = false }, label = { Text("بالزاوية") })
                FilterChip(selected = fajrByMinutes, onClick = { fajrByMinutes = true }, label = { Text("قبل الشروق بدقائق") })
            }
            if (fajrByMinutes) Stepper("الدقائق", fajrMinutes, CustomMethodRanges.FAJR_MINUTES, { fajrMinutes = it })
            else AngleStepper(fajrAngle, fajrAngles) { fajrAngle = it }
            Text("12° مسلمو فرنسا · 15° الجمعية الإسلامية لأمريكا الشمالية · 18° رابطة العالم الإسلامي ورئاسة الشؤون الدينية والمعهد الوطني للرصد الجوي · 19.5° مصر", fontSize = 11.sp, color = TextMuted)

            Text("العشاء", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDark, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !ishaByMinutes, onClick = { ishaByMinutes = false }, label = { Text("بالزاوية") })
                FilterChip(selected = ishaByMinutes, onClick = { ishaByMinutes = true }, label = { Text("بعد المغرب بدقائق") })
            }
            if (ishaByMinutes) Stepper("الدقائق", ishaMinutes, CustomMethodRanges.ISHA_MINUTES, { ishaMinutes = it })
            else AngleStepper(ishaAngle, CustomMethodRanges.ISHA_ANGLES) { ishaAngle = it }
            Text("الشفق الأحمر من 12° إلى 17°، والشفق الأبيض 18° (قول الحنفية والمعهد الوطني للرصد الجوي)", fontSize = 11.sp, color = TextMuted)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("العشاء ليس قبل", fontSize = 15.sp, color = TextDark, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val t = ishaEarliest ?: LocalTime.of(19, 0)
                    TimePickerDialog(context, { _, h, m -> ishaEarliest = LocalTime.of(h, m) }, t.hour, t.minute, true).show()
                }) { Text(ishaEarliest?.let { String.format(Locale.US, "⁦%02d:%02d⁩", it.hour, it.minute) } ?: "بلا") }
                if (ishaEarliest != null) TextButton(onClick = { ishaEarliest = null }) { Text("إلغاء") }
            }

            Text("العصر", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDark, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = asr == 1, onClick = { asr = 1 }, label = { Text("الجمهور (المثل)") })
                FilterChip(selected = asr == 2, onClick = { asr = 2 }, label = { Text("الحنفية (المثلان)") })
            }
            Stepper("الظهر بعد الزوال", dhuhr, CustomMethodRanges.DHUHR_MINUTES, { dhuhr = it })
            Stepper("المغرب بعد الغروب", maghrib, CustomMethodRanges.MAGHRIB_MINUTES, { maghrib = it })
            Stepper("الشروق", sunrise, CustomMethodRanges.SUNRISE_MINUTES, { sunrise = it }, signed = true)

            val problems = CustomMethodRanges.problems(build(), mainlandFrance)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("إلغاء") }
                TextButton(onClick = { onSave(build()) }, enabled = problems.isEmpty()) { Text("حفظ") }
            }
        }
    }
}

@Composable
private fun AngleStepper(value: Double, range: ClosedFloatingPointRange<Double>, onValue: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("الزاوية", fontSize = 15.sp, color = TextDark, modifier = Modifier.weight(1f))
        TextButton(onClick = { onValue((value - 0.5).coerceAtLeast(range.start)) }, enabled = value - 0.5 >= range.start) { Text("−") }
        Text(String.format(Locale.US, "⁦%s°⁩", value.toBigDecimal().stripTrailingZeros().toPlainString()), fontSize = 15.sp, color = TextDark, modifier = Modifier.width(72.dp))
        TextButton(onClick = { onValue((value + 0.5).coerceAtMost(range.endInclusive)) }, enabled = value + 0.5 <= range.endInclusive) { Text("+") }
    }
}
