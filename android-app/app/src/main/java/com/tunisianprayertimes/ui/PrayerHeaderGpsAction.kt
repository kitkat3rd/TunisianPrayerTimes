package com.tunisianprayertimes.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.AnalyticsTracker
import com.tunisianprayertimes.DelegationLocator
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.GreenPrimary

@Composable
internal fun PrayerHeaderGpsAction(
    placeChoice: Int,
    locating: Boolean,
    preparingPlace: Boolean,
    onLocate: () -> Unit,
    onChooseCity: () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var deniedBefore by rememberSaveable { mutableStateOf(false) }
    var recoveryOpen by rememberSaveable(placeChoice) { mutableStateOf(false) }
    var requestedChoice by rememberSaveable { mutableStateOf<Int?>(null) }

    fun finishRequest() {
        val choice = requestedChoice
        requestedChoice = null
        if (choice != placeChoice) return
        val granted = DelegationLocator.hasLocationPermission(context)
        deniedBefore = !granted
        recoveryOpen = !granted
        AnalyticsTracker.permissionStepResult(context, "location", if (granted) "granted" else "denied", "delegation_picker")
        if (granted) onLocate()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { finishRequest() }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { finishRequest() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(48.dp).clip(CircleShape).background(GreenPrimary.copy(alpha = 0.08f))
            .clickable(enabled = !locating && !preparingPlace && requestedChoice == null, role = Role.Button) {
                when {
                    DelegationLocator.hasLocationPermission(context) -> {
                        deniedBefore = false
                        onLocate()
                    }
                    deniedBefore && activity != null &&
                        DelegationLocator.requestedPermissions.none(activity::shouldShowRequestPermissionRationale) -> recoveryOpen = true
                    else -> {
                        requestedChoice = placeChoice
                        AnalyticsTracker.permissionStepResult(context, "location", "request_opened", "delegation_picker")
                        permission.launch(DelegationLocator.requestedPermissions)
                    }
                }
            },
    ) {
        if (locating || preparingPlace) {
            val label = stringResource(if (locating) R.string.onboarding_location_locating else R.string.gps_auto_detect)
            CircularProgressIndicator(
                color = GreenPrimary, strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp).semantics { contentDescription = label },
            )
        } else {
            Icon(painterResource(R.drawable.ic_my_location), stringResource(R.string.gps_auto_detect),
                Modifier.size(22.dp), tint = GreenPrimary)
        }
    }
    if (recoveryOpen) {
        AlertDialog(
            onDismissRequest = { recoveryOpen = false },
            title = { Text(stringResource(R.string.gps_auto_detect)) },
            text = { Text(stringResource(R.string.prayer_header_gps_denied_help)) },
            confirmButton = {
                TextButton(onClick = {
                    recoveryOpen = false
                    onChooseCity()
                }) { Text(stringResource(R.string.onboarding_location_pick)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    requestedChoice = placeChoice
                    recoveryOpen = false
                    try {
                        settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    } catch (_: ActivityNotFoundException) {
                        requestedChoice = null
                        recoveryOpen = true
                    }
                }) { Text(stringResource(R.string.prayer_open_app_settings)) }
            },
        )
    }
}
