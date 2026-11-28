package com.tunisianprayertimes.mosque

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import com.tunisianprayertimes.ui.AppInfoButton
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
            val title = stringResource(if (timeOnly) R.string.mosque_mode_time else R.string.mosque_mode_combined)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = if (selected) PrayerSilencePalette.TintedStrip else PrayerSilencePalette.Surface,
                border = BorderStroke(1.dp, if (selected) PrayerSilencePalette.InteractiveTeal else PrayerSilencePalette.SoftBorder),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f)
                            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(option) })
                            .testTag(if (timeOnly) "silence_method_time" else "silence_method_mosque")
                            .heightIn(min = 56.dp).padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected, onClick = null, enabled = enabled,
                            colors = RadioButtonDefaults.colors(selectedColor = PrayerSilencePalette.InteractiveTeal),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                color = PrayerSilencePalette.PrimaryText,
                            )
                            if (!timeOnly) {
                                Text(
                                    stringResource(if (supportsMosque) R.string.mosque_method_arrival_summary else R.string.mosque_needs_android),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PrayerSilencePalette.SecondaryText,
                                )
                            }
                        }
                    }
                    if (!timeOnly && supportsMosque) {
                        AppInfoButton(
                            title = title,
                            text = stringResource(R.string.mosque_method_arrival_detail),
                            modifier = Modifier.testTag("mosque_method_info"),
                        )
                    }
                }
            }
        }
    }
}
