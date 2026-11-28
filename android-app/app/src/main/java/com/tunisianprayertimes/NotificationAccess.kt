package com.tunisianprayertimes

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Android can allow an app to notify while silencing one channel or its whole group. */
internal object NotificationAccess {
    enum class Issue { APP_BLOCKED, GROUP_BLOCKED, CHANNEL_BLOCKED, LOW_IMPORTANCE }

    fun issue(
        context: Context,
        channelId: String?,
        minimumImportance: Int = NotificationManager.IMPORTANCE_MIN,
    ): Issue? {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (missingRuntimePermission(context) || !manager.areNotificationsEnabled()) return Issue.APP_BLOCKED
        // Some channels are created on first delivery. Their configured default applies until then.
        val channel = channelId?.let(manager::getNotificationChannel) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && channel.group != null &&
            manager.getNotificationChannelGroup(channel.group)?.isBlocked == true
        ) return Issue.GROUP_BLOCKED
        return when {
            channel.importance == NotificationManager.IMPORTANCE_NONE -> Issue.CHANNEL_BLOCKED
            channel.importance < minimumImportance -> Issue.LOW_IMPORTANCE
            else -> null
        }
    }

    fun canRequest(activity: Activity): Boolean =
        missingRuntimePermission(activity) && (!PrefsManager.hasSeenNotificationRationale(activity) ||
            activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))

    // A dismissed first dialog and a policy-fixed denial can both report DENIED without a rationale.
    // On the next deliberate tap, let the person retry or open Settings.
    fun offerRequestOrSettings(activity: Activity): Boolean =
        missingRuntimePermission(activity) && PrefsManager.hasRequestedNotifications(activity) &&
            !PrefsManager.hasSeenNotificationRationale(activity) &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)

    private fun missingRuntimePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun settingsIntent(context: Context, channelId: String?): Intent {
        val channel = repairChannel(channelId, issue(context, channelId))
        return if (channel != null && context.getSystemService(NotificationManager::class.java).getNotificationChannel(channel) != null) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    }

    fun repairChannel(channelId: String?, issue: Issue?): String? =
        if (issue == Issue.APP_BLOCKED || issue == Issue.GROUP_BLOCKED) null else channelId
}
