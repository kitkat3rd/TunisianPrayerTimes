package com.tunisianprayertimes.mosque

import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.PrayerSettingsDivider
import com.tunisianprayertimes.ui.PrayerSettingsGroup
import com.tunisianprayertimes.ui.PrayerSettingsLink
import com.tunisianprayertimes.ui.PrayerSilencePalette

@Composable
internal fun MosqueSetupSettings(
    combined: Boolean,
    issues: List<Int>?,
    enrollmentFailed: Boolean,
    onPrepareRules: () -> Unit,
    onDnd: () -> Unit,
    onPrecise: () -> Unit,
    onBackground: () -> Unit,
    onExact: () -> Unit,
    onNotifications: () -> Unit,
    onLocation: () -> Unit,
    onAppSettings: () -> Unit,
    onAreas: () -> Unit,
) {
    val supportsRules = Build.VERSION.SDK_INT >= 29
    if (enrollmentFailed) {
        PrayerSettingsGroup(title = stringResource(R.string.mosque_setup_enrollment_failed)) {
            if (supportsRules) {
                PrayerSettingsLink(
                    title = stringResource(R.string.mosque_allow_dnd),
                    summary = stringResource(R.string.mosque_setup_dnd_recovery),
                    onClick = onDnd,
                )
                PrayerSettingsDivider()
                PrayerSettingsLink(
                    title = stringResource(R.string.mosque_prepare_rules),
                    summary = stringResource(R.string.mosque_setup_rules_recovery),
                    onClick = onPrepareRules,
                )
            } else {
                SetupMessage(R.string.mosque_needs_android)
            }
        }
    }
    if (issues == null) {
        SetupMessage(R.string.mosque_setup_checking)
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = PrayerSilencePalette.InteractiveTeal)
        return
    }

    val required = buildList {
        for (issue in issues) {
            val row = when (issue) {
                R.string.mosque_needs_dnd -> {
                    if (enrollmentFailed && supportsRules) continue
                    SetupRow(R.string.mosque_allow_dnd, issue, onDnd)
                }
                R.string.mosque_needs_rules -> {
                    if (enrollmentFailed || !supportsRules) continue
                    SetupRow(R.string.mosque_prepare_rules, issue, onPrepareRules)
                }
                R.string.mosque_needs_precise -> SetupRow(R.string.mosque_allow_precise, onClick = onPrecise)
                R.string.mosque_needs_disclosure, R.string.mosque_needs_background ->
                    SetupRow(R.string.mosque_allow_background, issue, onBackground)
                R.string.mosque_needs_exact -> SetupRow(R.string.mosque_allow_exact, issue, onExact)
                R.string.mosque_needs_notifications -> SetupRow(
                    R.string.mosque_allow_notifications, issue,
                    if (Build.VERSION.SDK_INT >= 33) onNotifications else onAppSettings,
                )
                R.string.mosque_needs_location -> SetupRow(R.string.mosque_location_settings, issue, onLocation)
                R.string.mosque_background_restricted -> SetupRow(R.string.mosque_app_settings, issue, onAppSettings)
                R.string.mosque_no_areas, R.string.mosque_too_many_areas, R.string.mosque_store_unavailable ->
                    SetupRow(R.string.mosque_areas_title, issue, onAreas)
                R.string.mosque_needs_android -> {
                    if (enrollmentFailed) continue
                    SetupRow(issue)
                }
                else -> SetupRow(issue)
            }
            add(row)
        }
    }.distinctBy { it.title }.sortedBy { if (it.title == R.string.mosque_allow_dnd) 0 else 1 }
    if (required.isNotEmpty()) {
        SetupGroup(R.string.mosque_status_setup, required)
    } else if (issues.isEmpty() && !enrollmentFailed) {
        PrayerSettingsGroup {
            SetupMessage(if (combined) R.string.mosque_setup_mosque_ready else R.string.mosque_setup_time_ready)
        }
    }

    // Keep system settings accessible when a permission prompt cannot be shown again.
    val system = buildList {
        if (!enrollmentFailed || !supportsRules) add(SetupRow(R.string.mosque_allow_dnd, onClick = onDnd))
        if (supportsRules && !enrollmentFailed) add(SetupRow(R.string.mosque_prepare_rules, onClick = onPrepareRules))
        add(SetupRow(R.string.mosque_app_settings, onClick = onAppSettings))
    }.filter { row -> required.none { it.title == row.title } }
    if (system.isNotEmpty()) SetupGroup(R.string.mosque_setup_phone_settings, system)
    if (combined) SetupMessage(R.string.mosque_provider_note)
}

private data class SetupRow(val title: Int, val detail: Int? = null, val onClick: (() -> Unit)? = null)

@Composable
private fun SetupGroup(title: Int, rows: List<SetupRow>) {
    PrayerSettingsGroup(title = stringResource(title)) {
        rows.forEachIndexed { index, row ->
            if (index > 0) PrayerSettingsDivider()
            if (row.onClick == null) {
                SetupMessage(row.title)
            } else {
                PrayerSettingsLink(
                    title = stringResource(row.title),
                    summary = row.detail?.let { stringResource(it) },
                    onClick = row.onClick,
                )
            }
        }
    }
}

@Composable
private fun SetupMessage(text: Int) {
    Text(
        text = stringResource(text),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = PrayerSilencePalette.SecondaryText,
    )
}
