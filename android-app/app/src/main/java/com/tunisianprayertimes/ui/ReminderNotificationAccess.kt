package com.tunisianprayertimes.ui

import android.app.Activity
import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tunisianprayertimes.NotificationAccess

internal enum class ReminderNotificationAction { None, Request, AppSettings, GroupSettings, ChannelSettings }

internal class ReminderNotificationAccess(val action: ReminderNotificationAction, val perform: () -> Unit)

private data class NotificationRecovery(val action: ReminderNotificationAction, val channel: String? = null)

private fun notificationRecovery(context: Context, activity: Activity?, channels: List<String>): NotificationRecovery {
    for (channel in channels) {
        val issue = NotificationAccess.issue(context, channel) ?: continue
        val action = when {
            issue == NotificationAccess.Issue.APP_BLOCKED && activity != null && NotificationAccess.canRequest(activity) ->
                ReminderNotificationAction.Request
            issue == NotificationAccess.Issue.APP_BLOCKED -> ReminderNotificationAction.AppSettings
            issue == NotificationAccess.Issue.GROUP_BLOCKED -> ReminderNotificationAction.GroupSettings
            else -> ReminderNotificationAction.ChannelSettings
        }
        return NotificationRecovery(action, channel)
    }
    return NotificationRecovery(ReminderNotificationAction.None)
}

/** The first blocked reminder channel, refreshed after an Android request or a return from Settings. */
@Composable
internal fun rememberReminderNotificationAccess(
    channels: List<String>,
    onAccessChanged: () -> Unit = {},
): ReminderNotificationAccess {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val changed by rememberUpdatedState(onAccessChanged)
    var access by remember(context, channels) { mutableStateOf(notificationRecovery(context, activity, channels)) }
    fun refresh(notify: Boolean) {
        val updated = notificationRecovery(context, activity, channels)
        val differs = access != updated
        access = updated
        if (notify || differs) changed()
    }
    val act = rememberNotificationAccessAction { refresh(notify = true) }
    LifecycleResumeEffect(context, channels) {
        refresh(notify = false)
        onPauseOrDispose { }
    }
    val displayed = access
    return ReminderNotificationAccess(displayed.action) {
        val current = notificationRecovery(context, activity, channels)
        if (current != displayed) refresh(notify = false)
        else if (current.action != ReminderNotificationAction.None) act(current.channel)
    }
}
