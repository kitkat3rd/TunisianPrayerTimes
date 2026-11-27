package com.tunisianprayertimes.mosque.trace

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.tunisianprayertimes.MainTabNavigation
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.MosqueSilenceSettings

private const val PERMISSION = Manifest.permission.ACTIVITY_RECOGNITION
private fun preferences(c: Context) = c.getSharedPreferences("debug_silence_walk", Context.MODE_PRIVATE)
private const val ASKED = "step_permission_requested"

internal fun canReadWalkSteps(c: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
    ContextCompat.checkSelfPermission(c, PERMISSION) == PackageManager.PERMISSION_GRANTED

internal enum class WalkStepAccess { NONE, ASK, SETTINGS }

internal fun walkStepAccess(activity: Activity, onLauncher: Boolean): WalkStepAccess {
    if (canReadWalkSteps(activity)) return WalkStepAccess.NONE
    val asked = preferences(activity).getBoolean(ASKED, false)
    if (onLauncher && (asked || activity.intent?.action != Intent.ACTION_MAIN ||
        !activity.intent.hasCategory(Intent.CATEGORY_LAUNCHER) ||
        activity.intent.hasExtra(MainTabNavigation.EXTRA_DESTINATION) ||
        !MosqueSilenceSettings.combined(activity) || !PrefsManager.isEnabled(activity))) return WalkStepAccess.NONE
    val pm = activity.packageManager
    val sensors = activity.getSystemService(SensorManager::class.java)
    if (!pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_DETECTOR) &&
        !pm.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER) &&
        sensors?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) == null &&
        sensors?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) == null) return WalkStepAccess.NONE
    return if (asked && !activity.shouldShowRequestPermissionRationale(PERMISSION)) WalkStepAccess.SETTINGS else WalkStepAccess.ASK
}

/** The launcher asks once. The reports screen lets the tester grant access later. */
@Composable
internal fun WalkStepPermission(onLauncher: Boolean) {
    val activity = LocalActivity.current ?: return
    var access by remember(activity, onLauncher) { mutableStateOf(walkStepAccess(activity, onLauncher)) }
    fun refresh() {
        if (canReadWalkSteps(activity)) SilenceWalkSensors.refreshStepPermission(activity)
        access = walkStepAccess(activity, onLauncher)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    fun ask() {
        preferences(activity).edit().putBoolean(ASKED, true).apply()
        permission.launch(PERMISSION)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refresh()
        if (onLauncher && access == WalkStepAccess.ASK) ask()
    }
    if (!onLauncher && access != WalkStepAccess.NONE) {
        Text(stringResource(R.string.walk_steps_permission_reason))
        OutlinedButton(onClick = {
            if (access == WalkStepAccess.SETTINGS) {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null)))
            } else ask()
        }) {
            Text(stringResource(if (access == WalkStepAccess.SETTINGS) R.string.walk_steps_permission_settings
                else R.string.walk_steps_permission_allow))
        }
    }
}
