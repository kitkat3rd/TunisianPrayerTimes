package com.tunisianprayertimes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Where a prayer-linked alarm rings: before its prayer, at the prayer time, or after it. */
internal enum class WakePrayerOffsetSide { BEFORE, AT, AFTER }

internal const val WAKE_PRAYER_OFFSET_DEFAULT_MINUTES = 20

/** Half a day either way; an alarm farther than that is nearer another prayer. */
internal const val WAKE_PRAYER_OFFSET_MAX_HOURS = 12

/** One tap each; anything else is a turn of the wheels. */
internal val WAKE_PRAYER_OFFSET_PRESETS = listOf(10, 15, 20, 30, 45, 60)

internal fun wakePrayerOffsetSide(signedMinutes: Int): WakePrayerOffsetSide = when {
    signedMinutes < 0 -> WakePrayerOffsetSide.BEFORE
    signedMinutes > 0 -> WakePrayerOffsetSide.AFTER
    else -> WakePrayerOffsetSide.AT
}

internal fun WakePrayerOffsetSide.signedMinutes(minutes: Int): Int = when (this) {
    WakePrayerOffsetSide.BEFORE -> -minutes
    WakePrayerOffsetSide.AT -> 0
    WakePrayerOffsetSide.AFTER -> minutes
}

/** The hours wheel's last value, stretched for an older offset: the minutes field it replaces took up to 999. */
internal fun wakePrayerOffsetMaxHours(absoluteMinutes: Int): Int =
    maxOf(WAKE_PRAYER_OFFSET_MAX_HOURS, absoluteMinutes / 60)

private val WheelRowHeight = 44.dp
private val WheelNumberWidth = 52.dp
private const val WHEEL_VISIBLE_ROWS = 3

/** How long the wheels still count as in use once they stop, so turning the other one next isn't interrupted. */
private const val WHEEL_SETTLE_GRACE_MILLIS = 1_200L

/**
 * How far a prayer-linked alarm rings from its prayer: the side, then hours and minutes on two wheels,
 * with the common distances one tap away and the resulting ring time under them. [minutes] stays while
 * the alarm rings at the prayer time, so picking a side again brings it back.
 *
 * The wheels hand over a value once they come to rest. [onAdjustingChange] is true while they turn and
 * shortly after, so a dialog doesn't open between turning the hours and the minutes.
 */
@Composable
internal fun WakePrayerOffsetControls(
    prayerName: String,
    side: WakePrayerOffsetSide,
    onSideChange: (WakePrayerOffsetSide) -> Unit,
    minutes: Int,
    maxHours: Int,
    onMinutesChange: (Int) -> Unit,
    resultPrayerName: String,
    prayerTimeText: String?,
    ringTimeTextFor: (signedOffsetMinutes: Int) -> String?,
    onAdjustingChange: (Boolean) -> Unit,
) {
    val sides = WakePrayerOffsetSide.entries
    // What the wheels show while they turn, before they hand the value over.
    var turningMinutes by remember { mutableStateOf<Int?>(null) }
    val shownMinutes = turningMinutes ?: minutes

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Before on the right, after on the left: the order of time in an Arabic line.
        WakeSegmentedChoice(
            options = listOf(
                stringResource(R.string.wake_editor_offset_side_before, prayerName),
                stringResource(R.string.wake_editor_offset_side_at, prayerName),
                stringResource(R.string.wake_editor_offset_side_after, prayerName),
            ),
            selectedIndex = sides.indexOf(side),
            onSelect = { index -> onSideChange(sides[index]) },
        )

        AnimatedVisibility(visible = side != WakePrayerOffsetSide.AT) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WakeDurationWheels(
                    totalMinutes = minutes,
                    maxHours = maxHours,
                    minTotalMinutes = 1,
                    onTotalMinutesChange = onMinutesChange,
                    onTurningChange = { value -> turningMinutes = value },
                    onAdjustingChange = onAdjustingChange,
                )

                val ringTimeText = ringTimeTextFor(side.signedMinutes(shownMinutes))
                if (side != WakePrayerOffsetSide.AT && ringTimeText != null && prayerTimeText != null) {
                    Text(
                        text = stringResource(
                            R.string.wake_editor_offset_result,
                            "\u2066$ringTimeText\u2069",
                            resultPrayerName,
                            "\u2066$prayerTimeText\u2069",
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("wake_offset_result"),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GreenPrimaryDark,
                        textAlign = TextAlign.Center,
                        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                    )
                }

                WakeOffsetPresets(
                    selectedMinutes = shownMinutes,
                    onSelect = onMinutesChange,
                )
            }
        }
    }
}

@Composable
private fun WakeOffsetPresets(
    selectedMinutes: Int,
    onSelect: (Int) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        WAKE_PRAYER_OFFSET_PRESETS.forEach { preset ->
            val selected = preset == selectedMinutes
            val label = if (preset == 60) {
                stringResource(R.string.wake_editor_offset_preset_hour)
            } else {
                stringResource(R.string.wake_editor_minutes_short, preset)
            }
            val description = if (preset == 60) {
                stringResource(R.string.wake_editor_offset_preset_hour_desc)
            } else {
                pluralStringResource(R.plurals.prayer_silence_duration_minutes, preset, preset)
            }
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(shape)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(preset) })
                    .semantics { contentDescription = description }
                    .testTag("wake_offset_preset_$preset"),
                shape = shape,
                color = if (selected) GreenPrimary else Color.White,
                border = if (selected) null else BorderStroke(1.dp, Gold.copy(alpha = 0.35f)),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = if (selected) Color.White else GreenPrimaryDark,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 14.sp, stepSize = 0.5.sp),
                    )
                }
            }
        }
    }
}

/**
 * Hours and minutes as two snapping wheels over one highlighted row, read right to left: «1 ساعة 20 دقيقة».
 * A value set from outside (a preset) turns the wheels to it; a value turned on the wheels is handed to
 * [onTotalMinutesChange] once both rest, raised to [minTotalMinutes] first. [onTurningChange] follows the
 * wheels while a finger turns them, and gets null once they rest.
 */
@Composable
internal fun WakeDurationWheels(
    totalMinutes: Int,
    maxHours: Int,
    minTotalMinutes: Int,
    onTotalMinutesChange: (Int) -> Unit,
    onTurningChange: (Int?) -> Unit,
    onAdjustingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hourState = rememberLazyListState(initialFirstVisibleItemIndex = (totalMinutes / 60).coerceIn(0, maxHours))
    val minuteState = rememberLazyListState(initialFirstVisibleItemIndex = totalMinutes % 60)
    val rowHeightPx = with(LocalDensity.current) { WheelRowHeight.toPx() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentTotalMinutes by rememberUpdatedState(totalMinutes)
    val currentMaxHours by rememberUpdatedState(maxHours)
    val currentMinTotalMinutes by rememberUpdatedState(minTotalMinutes)
    val currentOnTotalMinutesChange by rememberUpdatedState(onTotalMinutesChange)
    val currentOnTurningChange by rememberUpdatedState(onTurningChange)
    val currentOnAdjustingChange by rememberUpdatedState(onAdjustingChange)

    val shownHour by remember(hourState, rowHeightPx) {
        derivedStateOf { hourState.centeredIndex(rowHeightPx).coerceIn(0, currentMaxHours) }
    }
    val shownMinute by remember(minuteState, rowHeightPx) {
        derivedStateOf { minuteState.centeredIndex(rowHeightPx).coerceIn(0, 59) }
    }
    val moving = hourState.isScrollInProgress || minuteState.isScrollInProgress
    // Turns to a value set from outside under way: no ticks, nothing to follow.
    var steers by remember { mutableIntStateOf(0) }
    val steering = steers > 0

    suspend fun steerTo(target: Int) {
        steers++
        try {
            coroutineScope {
                launch { hourState.animateScrollToItem((target / 60).coerceIn(0, currentMaxHours)) }
                launch { minuteState.animateScrollToItem(target % 60) }
            }
        } finally {
            steers--
        }
    }

    fun turnWheel(state: LazyListState, index: Int) {
        scope.launch { state.animateScrollToItem(index) }
    }

    LaunchedEffect(totalMinutes, maxHours) {
        val target = totalMinutes.coerceIn(0, maxHours * 60 + 59)
        if (shownHour * 60 + shownMinute != target) steerTo(target)
    }

    val turningValue = (shownHour * 60 + shownMinute).takeIf { moving && !steering }
    LaunchedEffect(turningValue) { currentOnTurningChange(turningValue) }

    LaunchedEffect(moving) {
        if (moving) {
            currentOnAdjustingChange(true)
            return@LaunchedEffect
        }
        val shown = shownHour * 60 + shownMinute
        val allowed = shown.coerceAtLeast(currentMinTotalMinutes)
        if (allowed != currentTotalMinutes) {
            // A new value turns the wheels to it (0:00 up to the minimum) through the effect above.
            currentOnTotalMinutesChange(allowed)
        } else if (allowed != shown) {
            // Same value as before, so nothing else turns them back. Turning restarts this effect: own scope.
            scope.launch { steerTo(allowed) }
        }
        delay(WHEEL_SETTLE_GRACE_MILLIS)
        currentOnAdjustingChange(false)
    }

    // Gone mid-turn («عند الصلاة», another alarm type): nothing is being adjusted any more.
    DisposableEffect(Unit) {
        onDispose {
            currentOnTurningChange(null)
            currentOnAdjustingChange(false)
        }
    }

    LaunchedEffect(hourState, minuteState) {
        snapshotFlow { shownHour * 60 + shownMinute }
            .drop(1)
            .collect {
                if (steers == 0 && (hourState.isScrollInProgress || minuteState.isScrollInProgress)) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                }
            }
    }

    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White)
            .border(1.dp, Gold.copy(alpha = 0.28f), shape)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(WheelRowHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(GreenPrimary.copy(alpha = 0.09f)),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WakeNumberWheel(
                state = hourState,
                count = maxHours + 1,
                shownValue = shownHour,
                rowHeightPx = rowHeightPx,
                label = stringResource(R.string.wake_editor_offset_hours_label),
                valueDescription = pluralStringResource(R.plurals.wake_editor_duration_hours, shownHour, shownHour),
                unit = stringResource(R.string.wake_editor_offset_unit_hour),
                onSelect = { index -> turnWheel(hourState, index) },
                modifier = Modifier.testTag("wake_offset_hours"),
            )
            WakeNumberWheel(
                state = minuteState,
                count = 60,
                shownValue = shownMinute,
                rowHeightPx = rowHeightPx,
                label = stringResource(R.string.wake_editor_offset_minutes_label),
                valueDescription = pluralStringResource(R.plurals.prayer_silence_duration_minutes, shownMinute, shownMinute),
                unit = stringResource(R.string.wake_editor_offset_unit_minute),
                onSelect = { index -> turnWheel(minuteState, index) },
                modifier = Modifier.testTag("wake_offset_minutes"),
            )
        }
    }
}

/**
 * One wheel and its unit beside the highlighted row. Rows shrink and fade with their distance from the
 * middle; tapping one turns the wheel to it. To TalkBack the wheel is one adjustable value.
 */
@Composable
private fun WakeNumberWheel(
    state: LazyListState,
    count: Int,
    shownValue: Int,
    rowHeightPx: Float,
    label: String,
    valueDescription: String,
    unit: String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lastIndex = count - 1
    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = label
            stateDescription = valueDescription
            progressBarRangeInfo = ProgressBarRangeInfo(
                current = shownValue.toFloat(),
                range = 0f..lastIndex.toFloat(),
                steps = (count - 2).coerceAtLeast(0),
            )
            setProgress { target ->
                val next = target.roundToInt().coerceIn(0, lastIndex)
                if (next != shownValue) onSelect(next)
                next != shownValue
            }
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyColumn(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            // One empty row above and below, so the first and last values can reach the middle.
            contentPadding = PaddingValues(vertical = WheelRowHeight * (WHEEL_VISIBLE_ROWS / 2)),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(WheelNumberWidth)
                .height(WheelRowHeight * WHEEL_VISIBLE_ROWS),
        ) {
            items(count) { value ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WheelRowHeight)
                        .graphicsLayer {
                            val middle = state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset / rowHeightPx
                            val distance = abs(value - middle)
                            val near = distance.coerceAtMost(1f)
                            val scale = 1f - 0.3f * near
                            scaleX = scale
                            scaleY = scale
                            alpha = (1f - 0.62f * near - 0.3f * (distance - 1f).coerceIn(0f, 1f)).coerceAtLeast(0f)
                        }
                        .clickable(interactionSource = null, indication = null) { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = value.toString(),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = GreenPrimaryDark,
                        maxLines = 1,
                        softWrap = false,
                        style = LocalTextStyle.current.copy(
                            textDirection = TextDirection.Ltr,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                }
            }
        }
        Text(
            text = unit,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = GreenPrimaryDark.copy(alpha = 0.85f),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The row in the middle of the wheel: the first one past the top padding, or the next once it is half scrolled. */
private fun LazyListState.centeredIndex(rowHeightPx: Float): Int =
    firstVisibleItemIndex + if (firstVisibleItemScrollOffset > rowHeightPx / 2f) 1 else 0
