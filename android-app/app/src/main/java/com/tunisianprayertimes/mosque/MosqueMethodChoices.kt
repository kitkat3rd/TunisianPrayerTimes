package com.tunisianprayertimes.mosque

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import com.tunisianprayertimes.ui.PrayerSilencePalette

@Composable
internal fun MosqueMethodChoices(
    mode: AutomaticSilenceMode?,
    supportsMosque: Boolean,
    onSelect: (AutomaticSilenceMode) -> Unit,
) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AutomaticSilenceMode.entries.forEach { option ->
            val timeOnly = option == AutomaticSilenceMode.TIME_ONLY
            val selected = option == mode
            val enabled = timeOnly || supportsMosque
            val summary = when {
                timeOnly -> R.string.mosque_method_time_detail
                !supportsMosque -> R.string.mosque_needs_android
                else -> R.string.mosque_method_arrival_detail
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = if (selected) PrayerSilencePalette.TintedStrip else PrayerSilencePalette.Surface,
                border = BorderStroke(1.dp, if (selected) PrayerSilencePalette.InteractiveTeal else PrayerSilencePalette.SoftBorder),
            ) {
                Column(
                    Modifier.selectable(
                        selected = selected, enabled = enabled, role = Role.RadioButton,
                        onClick = { onSelect(option) },
                    ).padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(36.dp).background(
                                if (selected) PrayerSilencePalette.InteractiveTeal else PrayerSilencePalette.TintedStrip,
                                RoundedCornerShape(11.dp),
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(if (timeOnly) R.drawable.ic_adhkar_clock else R.drawable.ic_adhkar_mosque),
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = if (selected) PrayerSilencePalette.OnAccent else PrayerSilencePalette.SecondaryText,
                            )
                        }
                        Text(
                            stringResource(if (timeOnly) R.string.mosque_mode_time else R.string.mosque_mode_combined),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            color = PrayerSilencePalette.PrimaryText,
                        )
                        RadioButton(
                            selected = selected, onClick = null, enabled = enabled,
                            colors = RadioButtonDefaults.colors(selectedColor = PrayerSilencePalette.InteractiveTeal),
                        )
                    }
                    Text(
                        stringResource(summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PrayerSilencePalette.SecondaryText,
                    )
                }
            }
        }
    }
}
