package com.tunisianprayertimes

import android.app.Notification
import android.app.NotificationManager
import android.content.Context

/** Android can silently drop a blocked notification even when notify() returns normally. */
internal fun NotificationManager.postReminderIfAllowed(
    context: Context,
    id: Int,
    reminder: Notification,
    tag: String? = null,
): Boolean {
    if (NotificationAccess.issue(context, reminder.channelId) != null) return false
    return try {
        notify(tag, id, reminder)
        // Permission or channel access can change between building the reminder and posting it.
        NotificationAccess.issue(context, reminder.channelId) == null
    } catch (_: SecurityException) {
        false
    }
}
