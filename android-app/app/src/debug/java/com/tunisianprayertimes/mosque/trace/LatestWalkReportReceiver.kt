package com.tunisianprayertimes.mosque.trace

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** ADB requests the committed session's filename before pulling the private file using run-as. */
class LatestWalkReportReceiver : BroadcastReceiver() {
    companion object {
        internal const val ACTION = "com.tunisianprayertimes.dev.LATEST_WALK_REPORT"

        internal fun latest(context: Context): String {
            val id = SilenceTraceStore.latestCompletedId(context) ?: return "none"
            val report = SilenceWalkReports.file(context, id)
            if (!report.isFile) SilenceWalkReports.scheduleSave(context, id)
            return report.name
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION || !isOrderedBroadcast) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                pending.resultData = latest(app)
                pending.resultCode = Activity.RESULT_OK
            } catch (error: Exception) {
                Log.w("SilenceTrace", "Could not query completed walks", error)
                pending.resultCode = Activity.RESULT_CANCELED
                pending.resultData = "error"
            } finally {
                pending.finish()
            }
        }
    }
}
