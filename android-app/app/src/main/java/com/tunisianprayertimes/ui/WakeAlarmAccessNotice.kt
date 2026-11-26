package com.tunisianprayertimes.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tunisianprayertimes.AnalyticsTracker
import com.tunisianprayertimes.R
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import com.tunisianprayertimes.wake.WakePlaybackService

private data class WakeAlarmAccess(
    val exact: Boolean,
    val appNotifications: Boolean,
    val channelNotifications: Boolean,
    val fullScreen: Boolean,
)

private fun wakeAlarmAccess(context: Context): WakeAlarmAccess {
    val manager = context.getSystemService(NotificationManager::class.java)
    return WakeAlarmAccess(
        exact = WakeAlarmScheduler.canScheduleExactAlarms(context),
        appNotifications = manager.areNotificationsEnabled() && (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ),
        // The channel is created when an alarm first rings.
        channelNotifications = manager.getNotificationChannel(WakePlaybackService.CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE,
        fullScreen = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || manager.canUseFullScreenIntent(),
    )
}

@Composable
internal fun WakeAlarmAccessNotice(activity: AppCompatActivity, saved: Boolean, modifier: Modifier = Modifier) {
    var access by remember(activity) { mutableStateOf(wakeAlarmAccess(activity)) }
    LifecycleResumeEffect(Unit) {
        access = wakeAlarmAccess(activity)
        onPauseOrDispose { }
    }
    val notifications = access.appNotifications && access.channelNotifications
    if (access.exact && notifications && access.fullScreen) return

    val packageUri = Uri.fromParts("package", activity.packageName, null)
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        AnalyticsTracker.permissionStepResult(activity, "notifications", if (granted) "granted" else "denied", "wake_alarm")
        access = wakeAlarmAccess(activity)
    }
    fun openSettings(intent: Intent, permission: String) {
        AnalyticsTracker.permissionStepResult(activity, permission, "request_opened", "wake_alarm")
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }
    }

    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(
                    when {
                        !access.exact && saved -> R.string.wake_access_saved_needs_exact
                        !access.exact -> R.string.wake_access_draft_needs_exact
                        else -> R.string.wake_access_presentation
                    },
                ),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            if (!access.exact) {
                WakeAccessAction(R.string.wake_access_exact_detail, R.string.wake_access_exact_action) {
                    openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri), "exact_alarm")
                }
            }
            if (!notifications) {
                WakeAccessAction(R.string.wake_access_notifications_detail, R.string.wake_access_notifications_action) {
                    val asked = activity.getPreferences(Context.MODE_PRIVATE)
                    val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                        (!asked.getBoolean("wake_notifications_requested", false) ||
                            activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
                    if (canAsk) {
                        asked.edit().putBoolean("wake_notifications_requested", true).apply()
                        AnalyticsTracker.permissionStepResult(activity, "notifications", "request_opened", "wake_alarm")
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        val settings = if (access.appNotifications) {
                            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_CHANNEL_ID, WakePlaybackService.CHANNEL_ID)
                        } else {
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        }
                        openSettings(settings.putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName), "notifications")
                    }
                }
            }
            if (!access.fullScreen) {
                WakeAccessAction(R.string.wake_access_full_screen_detail, R.string.wake_access_full_screen_action) {
                    openSettings(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri), "full_screen_intent")
                }
            }
        }
    }
}

@Composable
private fun WakeAccessAction(@StringRes detail: Int, @StringRes action: Int, onClick: () -> Unit) {
    Text(stringResource(detail))
    TextButton(onClick = onClick) { Text(stringResource(action)) }
}
