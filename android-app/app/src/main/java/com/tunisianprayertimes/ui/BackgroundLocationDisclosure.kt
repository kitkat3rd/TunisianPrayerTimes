package com.tunisianprayertimes.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.DelegationLocator
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.PrayerNameColor
import com.tunisianprayertimes.ui.theme.TextDark

/** Travel has its own disclosure even if the mosque feature already holds background access. */
@Composable
internal fun BackgroundLocationDisclosure(onDone: (granted: Boolean) -> Unit) {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onDone(DelegationLocator.hasBackgroundLocationPermission(context)) else denied = true
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onDone(DelegationLocator.hasBackgroundLocationPermission(context))
    }
    val hasBackground = DelegationLocator.hasBackgroundLocationPermission(context)
    val needsSettings = !hasBackground && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || denied)
    val settingsHint = if (needsSettings && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        stringResource(R.string.travel_permission_settings_option, context.packageManager.backgroundPermissionOptionLabel)
    } else if (needsSettings) stringResource(R.string.travel_allow_in_settings) else null
    AlertDialog(
        onDismissRequest = { onDone(false) },
        shape = RoundedCornerShape(16.dp),
        containerColor = Color.White,
        title = {
            Text(
                text = stringResource(R.string.background_location_title),
                color = PrayerNameColor,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = listOfNotNull(stringResource(R.string.travel_background_disclosure), settingsHint).joinToString("\n\n"),
                color = TextDark,
                lineHeight = 20.sp,
            )
        },
        dismissButton = {
            TextButton(onClick = { onDone(false) }) {
                Text(text = stringResource(R.string.background_location_later))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        hasBackground -> onDone(true)
                        needsSettings -> settings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> request.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        else -> onDone(false)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(text = stringResource(if (needsSettings) R.string.prayer_open_app_settings else R.string.background_location_continue))
            }
        },
    )
}
