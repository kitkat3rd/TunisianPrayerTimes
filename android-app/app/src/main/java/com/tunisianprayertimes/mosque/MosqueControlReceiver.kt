package com.tunisianprayertimes.mosque

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.GeofencingEvent
import com.tunisianprayertimes.PrefsManager

/** Private PendingIntent receiver. A circle transition is a request to check, never polygon evidence. */
class MosqueControlReceiver : BroadcastReceiver() {
    companion object {
        const val DEADLINE = "com.tunisianprayertimes.mosque.DEADLINE"
        const val GEOFENCE = "com.tunisianprayertimes.mosque.GEOFENCE"
        const val MANUAL = "com.tunisianprayertimes.mosque.MANUAL"
        const val UNDO_MANUAL = "com.tunisianprayertimes.mosque.UNDO_MANUAL"
        const val SKIP = "com.tunisianprayertimes.mosque.SKIP"
    }
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SKIP -> {
                // An older notification without prayer IDs must never dismiss today's prayer.
                val keys = intent.getStringArrayListExtra("occurrences")?.toSet().orEmpty()
                if (keys.isNotEmpty() && MosqueSilenceSettings.combined(context)) MosqueSilenceMonitor.skip(context, keys)
            }
            MANUAL -> intent.getStringExtra("occurrence")?.let { MosquePrayerActions.manual(context, it) }
            UNDO_MANUAL -> if (intent.getLongExtra("end", -1) == PrefsManager.getManualSilenceEndsAtMillis(context)) MosquePrayerActions.undo(context)
            DEADLINE -> MosqueSilenceMonitor.reconcile(context)
            GEOFENCE -> {
                val token = intent.data?.lastPathSegment ?: return
                if (!MosqueObservationService.validGeofenceToken(context, token)) return
                if (!MosqueSilenceMonitor.canObserve(context)) { MosqueSilenceMonitor.reconcile(context); return }
                val event = GeofencingEvent.fromIntent(intent) ?: return
                if (event.hasError()) {
                    MosqueObservationService.observationFailed(context)
                    return
                }
                // ENTER, EXIT and DWELL all request fresh, window-gated evidence. EXIT never clears DND.
                activeMosqueDiagnostics(context)?.geofence(event)
                MosqueSilenceMonitor.reconcile(context, canStart = true, transition = event.geofenceTransition)
            }
        }
    }
}
