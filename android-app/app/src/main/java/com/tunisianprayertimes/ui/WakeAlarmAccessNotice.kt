package com.tunisianprayertimes.ui

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tunisianprayertimes.AnalyticsTracker
import com.tunisianprayertimes.NotificationAccess
import com.tunisianprayertimes.R
import com.tunisianprayertimes.wake.AwakeCheckService
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import com.tunisianprayertimes.wake.WakePlaybackService

internal data class WakeAlarmAccess(
    val exact: Boolean,
    val ringingIssue: NotificationAccess.Issue?,
    val awakeCheckIssue: NotificationAccess.Issue?,
    val fullScreen: Boolean,
) {
    val ready: Boolean get() = exact && ringingIssue == null && awakeCheckIssue == null && fullScreen
}

internal fun wakeAlarmAccess(context: Context, awakeCheckEnabled: Boolean = false): WakeAlarmAccess {
    val manager = context.getSystemService(NotificationManager::class.java)
    return WakeAlarmAccess(
        exact = WakeAlarmScheduler.canScheduleExactAlarms(context),
        ringingIssue = NotificationAccess.issue(context, WakePlaybackService.CHANNEL_ID, NotificationManager.IMPORTANCE_HIGH),
        awakeCheckIssue = if (awakeCheckEnabled) NotificationAccess.issue(context, AwakeCheckService.CHANNEL_ID) else null,
        fullScreen = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || manager.canUseFullScreenIntent(),
    )
}

@Composable
internal fun WakeAlarmAccessNotice(
    activity: AppCompatActivity,
    saved: Boolean,
    modifier: Modifier = Modifier,
    awakeCheckEnabled: Boolean = false,
) {
    var access by remember(activity, awakeCheckEnabled) { mutableStateOf(wakeAlarmAccess(activity, awakeCheckEnabled)) }
    LifecycleResumeEffect(activity, awakeCheckEnabled) {
        access = wakeAlarmAccess(activity, awakeCheckEnabled)
        onPauseOrDispose { }
    }
    if (access.ready) return

    val packageUri = Uri.fromParts("package", activity.packageName, null)
    val repairNotifications = rememberNotificationAccessAction(entryPoint = "wake_alarm") {
        access = wakeAlarmAccess(activity, awakeCheckEnabled)
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
            if (access.ringingIssue == NotificationAccess.Issue.LOW_IMPORTANCE) {
                WakeAccessAction(R.string.wake_access_low_ringing_channel, R.string.wake_access_ringing_channel_action) {
                    repairNotifications(WakePlaybackService.CHANNEL_ID)
                }
            } else if (access.ringingIssue != null) {
                WakeAccessAction(R.string.wake_access_notifications_detail, R.string.wake_access_notifications_action) {
                    repairNotifications(WakePlaybackService.CHANNEL_ID)
                }
            }
            if (access.awakeCheckIssue != null && access.ringingIssue != NotificationAccess.Issue.APP_BLOCKED) {
                WakeAccessAction(R.string.wake_access_awake_check_detail, R.string.wake_access_awake_check_action) {
                    repairNotifications(AwakeCheckService.CHANNEL_ID)
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
