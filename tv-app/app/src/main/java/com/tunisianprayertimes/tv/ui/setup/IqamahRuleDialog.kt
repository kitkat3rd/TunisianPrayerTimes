package com.tunisianprayertimes.tv.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.tv.data.IqamahConfig
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.Digits
import com.tunisianprayertimes.tv.ui.common.FocusableButton
import com.tunisianprayertimes.tv.ui.common.FocusableListItem
import com.tunisianprayertimes.tv.ui.common.FocusableSurface
import com.tunisianprayertimes.tv.ui.common.Stepper
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.common.onSurfaceMuted
import com.tunisianprayertimes.tv.ui.common.onSurfaceText
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.midadStyle
import java.time.LocalTime

@Composable
internal fun IqamahRuleDialog(
    prayerName: String,
    config: IqamahConfig,
    anchor: LocalTime?,
    afterSunrise: Boolean,
    onInteraction: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (IqamahConfig) -> Unit,
) {
    var draft by remember { mutableStateOf(config) }
    val initiallyFixed = remember { config.isFixed }
    val shape = RoundedCornerShape(18.dp)

    fun change(next: IqamahConfig) {
        onInteraction()
        draft = next
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier.width(424.dp)
                .background(Midad.Surface, shape)
                .border(1.dp, Midad.Keyline, shape)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) onInteraction()
                    false
                }
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(prayerName, style = midadStyle(25.sp, FontWeight.SemiBold), modifier = Modifier.align(Alignment.Start))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeChoice(
                    label = if (afterSunrise) TvStrings.SWITCH_TO_SUNRISE else TvStrings.SWITCH_TO_ADHAN,
                    active = !draft.isFixed,
                    onClick = { if (draft.isFixed) change(draft.switched(anchor)) },
                    modifier = Modifier.weight(1f).initialFocus(!initiallyFixed),
                )
                ModeChoice(
                    label = TvStrings.SWITCH_TO_FIXED,
                    active = draft.isFixed,
                    onClick = { if (!draft.isFixed) change(draft.switched(anchor)) },
                    modifier = Modifier.weight(1f).initialFocus(initiallyFixed),
                )
            }
            Box(Modifier.height(196.dp), contentAlignment = Alignment.Center) {
                if (draft.isFixed) {
                    val time = TvStrings.hm(LocalTime.of(draft.fixedHour, draft.fixedMinute))
                    FixedTimeControls(time) { change(draft.shiftFixed(it)) }
                } else {
                    val range = MosqueSchedule.IQAMAH_MINUTES
                    Stepper(
                        value = TvStrings.minutesShort(draft.delayMinutes),
                        onMinus = { change(draft.copy(delayMinutes = (draft.delayMinutes - 1).coerceIn(range))) },
                        onPlus = { change(draft.copy(delayMinutes = (draft.delayMinutes + 1).coerceIn(range))) },
                        keySize = 44.dp,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusableListItem(
                    text = TvStrings.SAVE,
                    onClick = { onSave(draft) },
                    modifier = Modifier.weight(1f),
                    centred = true,
                )
                FocusableListItem(TvStrings.CANCEL, onDismiss, Modifier.weight(1f), centred = true)
            }
        }
    }
}

@Composable
private fun ModeChoice(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier) {
    FocusableSurface(
        onClick = onClick,
        modifier = modifier.semantics {
            selected = active
            role = Role.RadioButton
        },
        rest = if (active) Midad.ArchLine else Midad.SurfaceRaised,
    ) { focused ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioButton(
                selected = active,
                onClick = null,
                modifier = Modifier.size(20.dp),
                colors = RadioButtonDefaults.colors(
                    selectedColor = onSurfaceText(focused),
                    unselectedColor = onSurfaceMuted(focused),
                ),
            )
            Text(label, style = midadStyle(16.sp, FontWeight.Medium, onSurfaceText(focused)))
        }
    }
}

@Composable
private fun FixedTimeControls(time: String, onStep: (Int) -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row {
                val style = midadStyle(14.sp, color = Midad.Muted)
                Text(TvStrings.CLOCK_HOUR, Modifier.width(76.dp), style = style, textAlign = TextAlign.Center)
                Spacer(Modifier.width(20.dp))
                Text(TvStrings.CLOCK_MINUTE, Modifier.width(76.dp), style = style, textAlign = TextAlign.Center)
            }
            TimeArrows("▲", 1, onStep)
            Digits(time, midadStyle(54.sp, FontWeight.SemiBold), Modifier.semantics { contentDescription = time })
            TimeArrows("▼", -1, onStep)
        }
    }
}

@Composable
private fun TimeArrows(arrow: String, direction: Int, onStep: (Int) -> Unit) {
    val adjustment = if (direction > 0) "زيادة" else "إنقاص"
    Row(horizontalArrangement = Arrangement.spacedBy(52.dp)) {
        FocusableButton(
            arrow, { onStep(direction * 60) },
            modifier = Modifier.semantics { contentDescription = "$adjustment ${TvStrings.CLOCK_HOUR}" },
            repeats = true,
        )
        FocusableButton(
            arrow, { onStep(direction) },
            modifier = Modifier.semantics { contentDescription = "$adjustment ${TvStrings.CLOCK_MINUTE}" },
            repeats = true,
        )
    }
}
