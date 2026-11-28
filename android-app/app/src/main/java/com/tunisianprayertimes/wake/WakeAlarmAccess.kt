package com.tunisianprayertimes.wake

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.tunisianprayertimes.NotificationAccess

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
