package com.tunisianprayertimes.tv.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.FlowTiming
import com.tunisianprayertimes.mosque.IqamahRule
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.PrayerEvent
import com.tunisianprayertimes.mosque.PrayerOverride
import com.tunisianprayertimes.tv.data.IqamahConfig
import com.tunisianprayertimes.tv.data.IqamahMode
import com.tunisianprayertimes.tv.data.PrefsManager
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.FocusableSurface
import com.tunisianprayertimes.tv.ui.common.Stepper
import com.tunisianprayertimes.tv.ui.common.ToggleRow
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.common.onSurfaceMuted
import com.tunisianprayertimes.tv.ui.common.onSurfaceText
import com.tunisianprayertimes.tv.ui.common.rtl
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.midadStyle
import java.time.LocalTime

/** The table's columns for the iqamah editor and the duration stepper. */
private val KEY = 34.dp
private val IQAMAH_VALUE = 140.dp
private val DURATION_VALUE = 64.dp
private val IQAMAH_COLUMN = KEY * 2 + IQAMAH_VALUE + 12.dp
private val DURATION_COLUMN = KEY * 2 + DURATION_VALUE + 12.dp
/** In settings, the iqamah the wall uses today. */
private val TODAY_COLUMN = 76.dp
/** A toggle in the table starts its label 12 dp in, as the prayer rows do. */
private val TABLE_TOGGLE_PADDING = PaddingValues(horizontal = 12.dp, vertical = 7.dp)

/**
 * The iqamah table shared by onboarding and settings: the daily prayers and Jumu'a, then the two
 * Eids, whose minutes count from sunrise. Under Jumu'a and under the Eids, whether the mosque holds
 * them at all (a neighbourhood masjid holds neither), and under a Jumu'a it holds, whether the dua
 * after the adhan comes before the khutba (on by default) and the khutba's length. The first row's
 * iqamah key takes the focus when the page opens. A plain scrolling column, not a lazy list: a row scrolled
 * away and back must not take the focus again.
 *
 * Each row can switch between minutes and a fixed time. [prayerTimes] provides the adhan or sunrise
 * to start from. In settings, [today] shows the iqamah the wall uses today, marked when Ramadan's
 * changes or a fixed time that does not fit today decided it. A prayer's
 * Ramadan change from the USB file or the phone ([ramadan]) shows under its row, with a key that
 * removes it. With [adhanScreenMinutes] (the settings), a row over the table sets how long the adhan
 * screen lasts, and says that an iqamah set sooner waits for its end.
 */
@Composable
fun IqamahTable(
    configs: Map<Prayer, IqamahConfig>,
    onChanged: (Prayer, IqamahConfig) -> Unit,
    modifier: Modifier = Modifier,
    today: Map<Prayer, PrayerEvent>? = null,
    prayerTimes: DayPrayerTimes? = null,
    ramadan: Map<Prayer, PrayerOverride> = emptyMap(),
    onRamadanCleared: (Prayer) -> Unit = {},
    adhanScreenMinutes: Int? = null,
    onAdhanScreenMinutesChanged: (Int) -> Unit = {},
    onEditorInteraction: () -> Unit = {},
) {
    fun config(prayer: Prayer) = configs[prayer] ?: IqamahConfig.from(MosqueSchedule.DEFAULT.settings(prayer))
    Column(
        modifier
            .widthIn(max = if (today != null) 840.dp else 680.dp)
            .verticalScroll(rememberScrollState())
            // Room above and below for the focus ring; sideways the scroll does not cut it, so the
            // rows stay flush with the title.
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        adhanScreenMinutes?.let { AdhanScreenRow(it, withToday = today != null, onAdhanScreenMinutesChanged) }
        IqamahHeader(withToday = today != null)
        PrefsManager.EDITABLE.forEach { prayer ->
            if (prayer == MosqueSchedule.EID.first()) {
                Text(
                    TvStrings.EID_AFTER_SUNRISE,
                    style = midadStyle(14.sp, color = Midad.Muted),
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp),
                )
            }
            val config = config(prayer)
            IqamahEditorRow(
                prayerName = TvStrings.editorLabel(prayer),
                config = config,
                onConfigChanged = { onChanged(prayer, it) },
                focusFirst = prayer == PrefsManager.EDITABLE.first(),
                afterSunrise = prayer in MosqueSchedule.EID,
                withToday = today != null,
                todayEvent = today?.get(prayer),
                anchor = prayerTimes?.iqamahAnchor(prayer),
                onEditorInteraction = onEditorInteraction,
                ramadan = ramadan[prayer]?.takeIf { today != null },
                onRamadanCleared = { onRamadanCleared(prayer) },
            )
            // One switch for Jumu'a, one for both Eids: a mosque holds both Eid prayers or neither.
            val held = when (prayer) {
                Prayer.JOMOAA -> listOf(prayer)
                MosqueSchedule.EID.last() -> MosqueSchedule.EID
                else -> null
            }
            if (held != null) {
                val on = held.all { config(it).held }
                ToggleRow(
                    label = if (prayer == Prayer.JOMOAA) TvStrings.HOLDS_JUMUA else TvStrings.HOLDS_EID,
                    checked = on,
                    onToggle = { held.forEach { onChanged(it, config(it).copy(held = !on)) } },
                    detail = if (prayer == Prayer.JOMOAA) TvStrings.HOLDS_JUMUA_HINT else TvStrings.HOLDS_EID_HINT,
                    contentPadding = TABLE_TOGGLE_PADDING,
                )
            }
            if (prayer == Prayer.JOMOAA && config.held) {
                // The order of the Friday screens: the adhan, its dua (unless off), then the khutba.
                ToggleRow(
                    label = TvStrings.JUMUA_ADHAN_DUA,
                    checked = config.adhanDua,
                    onToggle = { onChanged(prayer, config.copy(adhanDua = !config.adhanDua)) },
                    detail = TvStrings.JUMUA_ADHAN_DUA_HINT,
                    contentPadding = TABLE_TOGGLE_PADDING,
                )
                KhutbaRow(config, withToday = today != null) { onChanged(prayer, it) }
            }
        }
        if (today.orEmpty().values.any { it.iqamahAdjusted }) {
            Text(
                TvStrings.IQAMAH_ADJUSTED_NOTE,
                style = midadStyle(14.sp, color = Midad.Muted, lineHeight = 1.45f).rtl(),
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            )
        }
    }
}

/** Jumu'a's khutba length, under its iqamah: «من الأذان» (the whole wait, as by default), or up to an hour. */
@Composable
private fun KhutbaRow(config: IqamahConfig, withToday: Boolean, onConfigChanged: (IqamahConfig) -> Unit) {
    val range = MosqueSchedule.KHUTBA_MINUTES
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .background(Midad.SurfaceRaised, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(TvStrings.KHUTBA_LENGTH, style = midadStyle(17.sp, FontWeight.Medium))
            Text(TvStrings.KHUTBA_LENGTH_HINT, style = midadStyle(14.sp, color = Midad.Muted))
        }
        // Under the iqamah's column, by five minutes.
        Stepper(
            value = TvStrings.khutbaLength(config.khutbaMinutes),
            onMinus = { onConfigChanged(config.copy(khutbaMinutes = (config.khutbaMinutes - 5).coerceIn(range))) },
            onPlus = { onConfigChanged(config.copy(khutbaMinutes = (config.khutbaMinutes + 5).coerceIn(range))) },
            valueWidth = IQAMAH_VALUE,
            keySize = KEY,
        )
        Spacer(Modifier.width(DURATION_COLUMN))
        if (withToday) Spacer(Modifier.width(TODAY_COLUMN))
    }
}

/**
 * How many minutes the adhan screen lasts (1 to 5), under the iqamah's column: an iqamah set closer to
 * the adhan waits for the end of the adhan screen and the minute of the dua after it, which the row's hint says.
 */
@Composable
private fun AdhanScreenRow(minutes: Int, withToday: Boolean, onChanged: (Int) -> Unit) {
    val range = FlowTiming.ADHAN_SCREEN_MINUTES
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .background(Midad.SurfaceRaised, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(TvStrings.ADHAN_SCREEN_LENGTH, style = midadStyle(17.sp, FontWeight.Medium))
            Text(TvStrings.ADHAN_SCREEN_LENGTH_HINT, style = midadStyle(14.sp, color = Midad.Muted, lineHeight = 1.45f).rtl())
        }
        Stepper(
            value = TvStrings.minutesShort(minutes),
            onMinus = { onChanged((minutes - 1).coerceIn(range)) },
            onPlus = { onChanged((minutes + 1).coerceIn(range)) },
            valueWidth = IQAMAH_VALUE,
            keySize = KEY,
        )
        Spacer(Modifier.width(DURATION_COLUMN))
        if (withToday) Spacer(Modifier.width(TODAY_COLUMN))
    }
}

/** «الصلاة · الإقامة · مدة الصلاة», and «اليوم» in the settings, over the table, quiet. */
@Composable
private fun IqamahHeader(withToday: Boolean) {
    val style = midadStyle(14.sp, color = Midad.Muted)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(TvStrings.PRAYER_COLUMN, style = style, modifier = Modifier.weight(1f))
        Text(TvStrings.IQAMAH_LABEL, style = style, textAlign = TextAlign.Center, modifier = Modifier.width(IQAMAH_COLUMN))
        Text(TvStrings.DURATION_LABEL, style = style, textAlign = TextAlign.Center, modifier = Modifier.width(DURATION_COLUMN))
        if (withToday) Text(TvStrings.TODAY_COLUMN, style = style, textAlign = TextAlign.Center, modifier = Modifier.width(TODAY_COLUMN))
    }
}

/**
 * One prayer's iqamah opens its editor. The duration stays adjustable on the row itself.
 */
@Composable
private fun IqamahEditorRow(
    prayerName: String,
    config: IqamahConfig,
    onConfigChanged: (IqamahConfig) -> Unit,
    anchor: LocalTime?,
    onEditorInteraction: () -> Unit,
    /** The first row of a table takes the remote's focus when the page opens. */
    focusFirst: Boolean = false,
    afterSunrise: Boolean = false,
    withToday: Boolean = false,
    todayEvent: PrayerEvent? = null,
    /** Ramadan's change to this prayer, shown under the row with the key that removes it. */
    ramadan: PrayerOverride? = null,
    onRamadanCleared: () -> Unit = {},
) {
    val salahRange = MosqueSchedule.SALAH_MINUTES
    var editing by remember { mutableStateOf(false) }
    val editKey = remember { FocusRequester() }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Midad.SurfaceRaised, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(prayerName, style = midadStyle(17.sp, FontWeight.Medium), modifier = Modifier.weight(1f))
            FocusableSurface(
                onClick = { editing = true },
                modifier = Modifier.width(IQAMAH_COLUMN).heightIn(min = KEY).focusRequester(editKey).initialFocus(focusFirst),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) { focused ->
                if (config.isFixed) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(TvStrings.SWITCH_TO_FIXED, style = midadStyle(14.sp, color = onSurfaceMuted(focused)))
                        val time = TvStrings.hm(LocalTime.of(config.fixedHour, config.fixedMinute))
                        Text(time, style = midadStyle(20.sp, FontWeight.SemiBold, onSurfaceText(focused)))
                    }
                } else {
                    Text(iqamahText(config, afterSunrise), style = midadStyle(17.sp, FontWeight.Medium, onSurfaceText(focused)))
                }
            }
            Stepper(
                value = TvStrings.minutesShort(config.salahMinutes),
                onMinus = { onConfigChanged(config.copy(salahMinutes = (config.salahMinutes - 1).coerceIn(salahRange))) },
                onPlus = { onConfigChanged(config.copy(salahMinutes = (config.salahMinutes + 1).coerceIn(salahRange))) },
                valueWidth = DURATION_VALUE,
                keySize = KEY,
            )
            if (withToday) TodayCell(todayEvent)
        }
        if (ramadan != null) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(ramadanText(ramadan), style = midadStyle(14.sp, color = Midad.Muted).rtl(), modifier = Modifier.weight(1f))
                RowKey(
                    text = TvStrings.RAMADAN_CLEAR,
                    onClick = {
                        onRamadanCleared()
                        editKey.requestFocus()
                    },
                    // Under today's iqamah when the table shows it: a column of its own, clear of the + key.
                    modifier = Modifier.width(TODAY_COLUMN),
                )
            }
        }
    }
    if (editing) {
        IqamahRuleDialog(
            prayerName = prayerName,
            config = config,
            anchor = anchor,
            afterSunrise = afterSunrise,
            onInteraction = onEditorInteraction,
            onDismiss = { editing = false },
            onSave = { draft ->
                onConfigChanged(config.copy(
                    mode = draft.mode,
                    delayMinutes = draft.delayMinutes,
                    fixedHour = draft.fixedHour,
                    fixedMinute = draft.fixedMinute,
                ))
                editing = false
            },
        )
    }
}

/**
 * Today's iqamah on the wall, and a word when it is not the rule's: Ramadan's, or moved to fit today.
 * Set tight, so the two lines keep the row's height.
 */
@Composable
private fun TodayCell(event: PrayerEvent?) {
    Column(Modifier.width(TODAY_COLUMN), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(todayIqamahText(event), style = midadStyle(16.sp, FontWeight.Medium, lineHeight = 1.15f))
        todayMark(event)?.let { mark ->
            val color = if (event?.iqamahAdjusted == true) Midad.Alert else Midad.Muted
            Text(mark, style = midadStyle(12.sp, color = color, lineHeight = 1.15f))
        }
    }
}

/** A small key on a row of the table, quieter than the steppers' keys. */
@Composable
private fun RowKey(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.heightIn(min = KEY),
        rest = Midad.Surface,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) { focused ->
        Text(text, style = midadStyle(14.sp, color = onSurfaceText(focused)), textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** A fixed iqamah that is really set; a fixed mode without its time counts from the adhan. */
internal val IqamahConfig.isFixed: Boolean
    get() = mode == IqamahMode.FIXED_TIME && fixedHour in 0..23 && fixedMinute in 0..59

/** The iqamah as the tables say it: «بعد الأذان 15 د», «بعد الشروق 20 د» for an Eid, «الساعة 19:40». */
internal fun iqamahText(config: IqamahConfig, afterSunrise: Boolean = false): String = when {
    config.isFixed -> TvStrings.atTime(config.fixedHour, config.fixedMinute)
    afterSunrise -> TvStrings.iqamahAfterSunrise(config.delayMinutes)
    else -> TvStrings.iqamahAfterAdhan(config.delayMinutes)
}

/** The fixed iqamah moved by [minutes], wrapping around midnight; the mode stays fixed. */
internal fun IqamahConfig.shiftFixed(minutes: Int): IqamahConfig {
    val total = Math.floorMod(fixedHour * 60 + fixedMinute + minutes, 24 * 60)
    return copy(fixedHour = total / 60, fixedMinute = total % 60)
}

/**
 * The rule turned into the other kind, keeping what the other kind had saved: a fixed time back to
 * minutes after the adhan, or minutes to the fixed time set before, else to where the minutes put it
 * after today's [anchor] (sunrise for an Eid). Without prayer times, the clock starts at noon.
 */
internal fun IqamahConfig.switched(anchor: LocalTime?): IqamahConfig = when {
    isFixed -> copy(mode = IqamahMode.DELAY)
    fixedHour in 0..23 && fixedMinute in 0..59 -> copy(mode = IqamahMode.FIXED_TIME)
    else -> {
        val time = anchor?.plusMinutes(delayMinutes.toLong()) ?: LocalTime.NOON
        copy(mode = IqamahMode.FIXED_TIME, fixedHour = time.hour, fixedMinute = time.minute)
    }
}

/** The noon adhan is shared by Dhuhr and Jumu'a, and both Eid prayers are timed from sunrise. */
internal fun DayPrayerTimes.iqamahAnchor(prayer: Prayer): LocalTime {
    val adhan = when (prayer) {
        Prayer.FAJR -> fajr
        Prayer.DHUHR, Prayer.JOMOAA -> dhuhr
        Prayer.ASR -> asr
        Prayer.MAGHRIB -> maghrib
        Prayer.ISHA -> isha
        Prayer.AID_FITR, Prayer.AID_ADHA -> return LocalTime.of(shurukHour, shurukMinute)
    }
    return LocalTime.of(adhan.hour, adhan.minute)
}

/** Ramadan's change to a prayer, under its row: «في رمضان: الإقامة بعد الأذان 20 د · مدة الصلاة 75 د». */
internal fun ramadanText(override: PrayerOverride): String = "${TvStrings.IN_RAMADAN}: " + listOfNotNull(
    override.iqamah?.let { rule ->
        val iqamah = when (rule) {
            is IqamahRule.AfterAdhan -> TvStrings.iqamahAfterAdhan(rule.minutes)
            is IqamahRule.FixedTime -> TvStrings.atTime(rule.time.hour, rule.time.minute)
        }
        "${TvStrings.IQAMAH_LABEL} $iqamah"
    },
    override.salahMinutes?.let { "${TvStrings.DURATION_LABEL} ${TvStrings.minutesShort(it)}" },
).joinToString(" · ")

/** The iqamah the wall uses today, «19:52»; «—» for a prayer not prayed today (Jumu'a on a weekday, an Eid). */
internal fun todayIqamahText(event: PrayerEvent?): String = event?.let { TvStrings.hm(it.iqamahAt.toLocalTime()) } ?: "—"

/** Why today's iqamah is not the rule's: moved to fit today's times (first, it needs the admin), or Ramadan's. */
internal fun todayMark(event: PrayerEvent?): String? = when {
    event == null -> null
    event.iqamahAdjusted -> TvStrings.IQAMAH_ADJUSTED
    event.ramadanSettings -> TvStrings.RAMADAN
    else -> null
}
