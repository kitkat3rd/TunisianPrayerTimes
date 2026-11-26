package com.tunisianprayertimes.mosque

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.PrayerSettingsDivider
import com.tunisianprayertimes.ui.PrayerSettingsGroup
import com.tunisianprayertimes.ui.PrayerSettingsLink
import com.tunisianprayertimes.ui.PrayerSettingsSwitch
import com.tunisianprayertimes.ui.PrayerSilencePalette

@Composable
internal fun MosqueAreasSettings(
    areas: SilenceAreaRead,
    combined: Boolean,
    hasDraft: Boolean,
    onAdd: () -> Unit,
    onResumeDraft: () -> Unit,
    onEdit: (SavedSilenceArea) -> Unit,
    onDelete: (SavedSilenceArea) -> Unit,
    onEnabledChange: (SavedSilenceArea, Boolean) -> Unit,
    onStrictChange: (SavedSilenceArea, Boolean) -> Unit,
    onExport: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = PrayerSilencePalette.TintedStrip,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (areas.available && areas.areas.isEmpty() && areas.rejected == 0) {
                    Text(
                        stringResource(R.string.mosque_saved_areas_empty),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PrayerSilencePalette.PrimaryText,
                    )
                }
                Text(
                    stringResource(if (areas.available) R.string.mosque_areas_private else R.string.mosque_store_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (areas.available) PrayerSilencePalette.SecondaryText else MaterialTheme.colorScheme.error,
                )
                if (areas.rejected > 0) {
                    Text(
                        stringResource(R.string.mosque_invalid_records, areas.rejected.toString()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = onAdd,
                    enabled = areas.available,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrayerSilencePalette.InteractiveTeal,
                        contentColor = PrayerSilencePalette.OnAccent,
                    ),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_add), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.mosque_add_area))
                }
            }
        }
        if (hasDraft) {
            PrayerSettingsGroup {
                PrayerSettingsLink(title = stringResource(R.string.mosque_restore_draft), onClick = onResumeDraft)
            }
        }
        areas.areas.forEach { area ->
            key(area.geometry.id) {
                PrayerSettingsGroup {
                    PrayerSettingsSwitch(
                        title = area.name,
                        checked = area.geometry.enabled,
                        onCheckedChange = { onEnabledChange(area, it) },
                    )
                    if (combined) {
                        PrayerSettingsDivider()
                        PrayerSettingsSwitch(
                            title = stringResource(R.string.mosque_adaptive_strict),
                            checked = area.geometry.requireStrongerEvidence,
                            onCheckedChange = { onStrictChange(area, it) },
                        )
                    }
                    PrayerSettingsDivider()
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val editLabel = stringResource(R.string.mosque_saved_area_edit, area.name)
                        TextButton(onClick = { onEdit(area) }, modifier = Modifier.semantics { contentDescription = editLabel }) {
                            Icon(painterResource(R.drawable.ic_edit), null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.mosque_edit))
                        }
                        val deleteLabel = stringResource(R.string.mosque_saved_area_delete, area.name)
                        TextButton(
                            onClick = { onDelete(area) },
                            modifier = Modifier.semantics { contentDescription = deleteLabel },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(painterResource(R.drawable.ic_delete), null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.mosque_delete))
                        }
                    }
                }
            }
        }
        if (areas.available && areas.areas.isNotEmpty()) {
            PrayerSettingsGroup {
                PrayerSettingsLink(title = stringResource(R.string.mosque_export), onClick = onExport)
            }
        }
        Text(
            stringResource(R.string.mosque_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = PrayerSilencePalette.SecondaryText,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
