package com.tunisianprayertimes.access

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.NotificationAccess
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceAccessSettings
import com.tunisianprayertimes.mosque.MosqueSettingsActivity
import com.tunisianprayertimes.mosque.SilenceOwner
import com.tunisianprayertimes.ui.AppModalBottomSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeatureAccessNotice(issues: List<FeatureAccessIssue>, modifier: Modifier = Modifier) {
    if (issues.isEmpty()) return
    val context = LocalContext.current
    var reviewing by rememberSaveable { mutableStateOf(false) }
    val features = remember(issues) { issues.map { it.feature }.distinct() }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            Modifier.clickable(role = Role.Button) { reviewing = true }
                .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                if (features.size == 1) stringResource(R.string.feature_access_review_one, stringResource(features.first()))
                else stringResource(R.string.feature_access_review),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (!reviewing) return
    AppModalBottomSheet(
        onDismissRequest = { reviewing = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.feature_access_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            val repairs = remember(issues) { issues.distinct().groupBy { it.action to it.channelId } }
            for ((destination, affected) in repairs) {
                HorizontalDivider()
                for ((message, records) in affected.groupBy { it.message }) {
                    val names = records.map { it.feature }.distinct().map { stringResource(it) }
                    Text(
                        names.joinToString("، "),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
                }
                val (action, channelId) = destination
                TextButton(onClick = { openFeatureAccess(context, action, channelId) }) {
                    Text(stringResource(action.label))
                }
            }
            TextButton(onClick = { reviewing = false }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.worship_times_close))
            }
        }
    }
}

private val AccessAction.label: Int get() = when (this) {
    AccessAction.MOSQUE_SETUP -> R.string.feature_access_silence_setup
    AccessAction.WAKE_RULE -> R.string.manual_rule_settings
    AccessAction.DND -> R.string.manual_dnd_open_settings
    AccessAction.DEVICE_LOCATION -> R.string.mosque_location_settings
    AccessAction.EXACT_ALARMS -> R.string.wake_access_exact_action
    AccessAction.FULL_SCREEN -> R.string.feature_access_full_screen_settings
    AccessAction.NOTIFICATIONS -> R.string.feature_access_notification_settings
    AccessAction.APP_DETAILS -> R.string.prayer_open_app_settings
}

private fun openFeatureAccess(context: Context, action: AccessAction, channelId: String?) {
    val app = Uri.fromParts("package", context.packageName, null)
    when (action) {
        AccessAction.MOSQUE_SETUP -> SilenceAccessSettings.openAppSettingsPage(context, MosqueSettingsActivity.setupIntent(context))
        AccessAction.WAKE_RULE -> SilenceAccessSettings.openRules(context, SilenceOwner.WAKE)
        AccessAction.DND -> SilenceAccessSettings.openDnd(context)
        AccessAction.EXACT_ALARMS -> SilenceAccessSettings.openExactAlarms(context)
        AccessAction.NOTIFICATIONS -> SilenceAccessSettings.openAppSettingsPage(context, NotificationAccess.settingsIntent(context, channelId))
        AccessAction.APP_DETAILS -> SilenceAccessSettings.openAppSettingsPage(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app))
        AccessAction.DEVICE_LOCATION -> SilenceAccessSettings.openAppSettingsPage(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        AccessAction.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            SilenceAccessSettings.openAppSettingsPage(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, app))
        }
    }
}
