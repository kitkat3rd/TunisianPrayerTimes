package com.tunisianprayertimes.mosque

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import com.tunisianprayertimes.mosque.presence.PresenceState
import com.tunisianprayertimes.ui.AppInfoButton
import com.tunisianprayertimes.ui.PrayerSettingsGroup
import com.tunisianprayertimes.ui.PrayerSilencePalette

@Composable
internal fun MosqueSilenceStatus(
    mode: AutomaticSilenceMode?,
    enabled: Boolean,
    issues: List<Int>?,
    state: PresenceState?,
    periods: MosquePeriods,
    phoneSilent: Boolean?,
    releasePending: Boolean,
    canUndo: Boolean,
    onRecheck: () -> Unit,
    onSkip: (Set<String>) -> Unit,
    onManual: (String) -> Unit,
    onUndo: () -> Unit,
) {
    val context = LocalContext.current
    val inWindow = periods.silence != null
    val watchingMosques = enabled && mode == AutomaticSilenceMode.TIME_AND_MOSQUE
    val observationStatus = when {
        !watchingMosques -> null
        periods.continuationEnd != null -> MosquePrayerActions.continuationLabel(context, periods.continuationEnd)
        state == PresenceState.SCHEDULE_FALLBACK -> stringResource(R.string.mosque_adaptive_fallback)
        issues == null -> null
        issues.isNotEmpty() || state == PresenceState.INACTIVE_NEEDS_SETUP -> stringResource(R.string.mosque_status_setup)
        state == PresenceState.INACTIVE_NEEDS_REARM -> stringResource(R.string.mosque_status_rearm)
        !inWindow && periods.observation != null -> stringResource(R.string.mosque_adaptive_early)
        !inWindow -> stringResource(R.string.mosque_status_waiting)
        state == PresenceState.ENTERING -> stringResource(R.string.mosque_status_entering)
        state == PresenceState.VISIT_CONFIRMED -> stringResource(R.string.mosque_adaptive_hold)
        state == PresenceState.EXITING -> stringResource(R.string.mosque_status_uncertain)
        state == PresenceState.ACQUIRING -> stringResource(R.string.mosque_status_checking)
        else -> stringResource(R.string.mosque_status_no_match)
    }

    PrayerSettingsGroup(title = stringResource(R.string.mosque_phone_status_title)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (phoneSilent != null) {
                    Icon(
                        painter = painterResource(if (phoneSilent) R.drawable.ic_volume_off else R.drawable.ic_volume_on),
                        contentDescription = null,
                        tint = PrayerSilencePalette.SecondaryText,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = stringResource(when (phoneSilent) {
                        true -> R.string.mosque_phone_status_silent
                        false -> R.string.mosque_phone_status_not_silent
                        null -> R.string.mosque_phone_status_loading
                    }),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PrayerSilencePalette.PrimaryText,
                    modifier = Modifier.weight(1f),
                )
                if (phoneSilent == true) {
                    AppInfoButton(
                        title = stringResource(R.string.mosque_phone_status_silent),
                        text = stringResource(R.string.mosque_status_other_dnd),
                    )
                }
            }
            if (releasePending) {
                Text(stringResource(R.string.mosque_release_pending), color = MaterialTheme.colorScheme.error)
            }
            if (observationStatus != null) {
                Text(observationStatus, color = PrayerSilencePalette.SecondaryText)
            }
            if (watchingMosques && periods.continuationEnd == null &&
                issues?.isEmpty() == true && (inWindow || periods.observation != null)) {
                OutlinedButton(onClick = onRecheck) { Text(stringResource(R.string.mosque_recheck)) }
            }
            if (watchingMosques) {
                periods.targets.forEach { window ->
                    val key = MosquePrayerActions.key(window)
                    OutlinedButton(onClick = { onSkip(setOf(key)) }) {
                        Text(stringResource(R.string.mosque_adaptive_skip, MosquePrayerActions.name(context, window)))
                    }
                    if (inWindow) {
                        TextButton(onClick = { onManual(key) }) {
                            Text(stringResource(R.string.mosque_adaptive_manual, MosquePrayerActions.label(context, window)))
                        }
                    }
                }
            }
            if (canUndo) {
                TextButton(onClick = onUndo) { Text(stringResource(R.string.mosque_adaptive_undo)) }
            }
        }
    }
}
