package com.tunisianprayertimes.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.tunisianprayertimes.AnalyticsTracker
import com.tunisianprayertimes.NotificationAccess
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R

/** The caller invokes this on a person's tap. A permission result only refreshes the feature. */
@Composable
internal fun rememberNotificationAccessAction(
    entryPoint: String? = null,
    onReturn: () -> Unit = {},
): (String?) -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val returned by rememberUpdatedState(onReturn)
    var choosingRecovery by rememberSaveable { mutableStateOf(false) }
    var rationaleAtRequest by rememberSaveable { mutableStateOf(false) }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { returned() }
    // A cancelled/interrupted system request can return no grant result at all. Keep the runtime
    // request available in that case. RequestPermission would collapse this into a denial.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = grants[Manifest.permission.POST_NOTIFICATIONS]
        if (granted != null) {
            val rationaleNow = activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true
            PrefsManager.recordNotificationResponse(context, granted, rationaleAtRequest || rationaleNow)
            entryPoint?.let { AnalyticsTracker.permissionStepResult(context, "notifications", if (granted) "granted" else "denied", it) }
        }
        returned()
    }
    fun request() {
        rationaleAtRequest = activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true
        entryPoint?.let { AnalyticsTracker.permissionStepResult(context, "notifications", "request_opened", it) }
        permission.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }
    fun openSettings(channelId: String?) {
        entryPoint?.let { AnalyticsTracker.permissionStepResult(context, "notifications", "request_opened", it) }
        try {
            settings.launch(NotificationAccess.settingsIntent(context, channelId))
        } catch (_: ActivityNotFoundException) {
            val app = Uri.fromParts("package", context.packageName, null)
            settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app))
        }
    }
    if (choosingRecovery) {
        AlertDialog(
            onDismissRequest = { choosingRecovery = false },
            title = { Text(stringResource(R.string.notification_access_title)) },
            text = { Text(stringResource(R.string.notification_access_retry_explanation)) },
            confirmButton = { TextButton(onClick = { choosingRecovery = false; request() }) {
                Text(stringResource(R.string.notification_access_retry))
            } },
            dismissButton = { TextButton(onClick = { choosingRecovery = false; openSettings(null) }) {
                Text(stringResource(R.string.notification_access_open_settings))
            } },
        )
    }
    return { channelId ->
        when {
            activity != null && NotificationAccess.offerRequestOrSettings(activity) -> choosingRecovery = true
            activity != null && NotificationAccess.canRequest(activity) -> request()
            else -> openSettings(channelId)
        }
    }
}
