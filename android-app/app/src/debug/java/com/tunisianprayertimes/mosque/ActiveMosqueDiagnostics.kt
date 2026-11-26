package com.tunisianprayertimes.mosque

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.presence.AdaptiveObservation
import com.tunisianprayertimes.mosque.presence.PresenceFix
import com.tunisianprayertimes.mosque.trace.SilenceTraceStore
import com.tunisianprayertimes.wake.displayName

internal fun activeMosqueDiagnostics(context: Context): MosqueObservationDiagnostics? =
    SilenceTraceStore.activeId(context)?.let { ActiveMosqueDiagnostics(context, it) }

private class ActiveMosqueDiagnostics(
    private val context: Context,
    override val sessionId: Long,
) : MosqueObservationDiagnostics {
    override fun stopped(barrier: Boolean) {
        val reason = if (barrier) R.string.mosque_status_rearm else R.string.trace_mosque_stopped
        MosqueObservationTrace.inactive(context, sessionId, context.getString(reason))
    }

    override fun skipped(before: MosquePeriods, chosen: Set<String>) {
        val names = before.targets.filter { MosquePrayerActions.key(it) in chosen }
            .joinToString("، ") { MosquePrayerActions.name(context, it) }
        SilenceTraceStore.append(context, "mosque_skipped", context.getString(R.string.mosque_adaptive_skip, names), sessionId = sessionId)
    }

    override fun period(periods: MosquePeriods, seed: Boolean) =
        MosqueObservationTrace.window(context, periods, sessionId, seed)

    override fun failedStart() {
        SilenceTraceStore.append(context, "mosque_observation_failed", context.getString(R.string.trace_mosque_failed), sessionId = sessionId)
    }

    override fun unavailable(issues: List<Int>) {
        if (issues.isNotEmpty()) MosqueObservationTrace.inactive(context, sessionId, issues.joinToString(" · ") { context.getString(it) })
    }

    override fun blocked(startFailed: Boolean) {
        val reason = if (startFailed) R.string.trace_mosque_failed else R.string.mosque_status_rearm
        MosqueObservationTrace.inactive(context, sessionId, context.getString(reason))
    }

    override fun engine(engine: AdaptiveObservation, areas: List<SavedSilenceArea>, fix: PresenceFix?) =
        MosqueObservationTrace.engine(context, sessionId, engine, areas, fix)

    override fun fix(fix: PresenceFix, time: Long, used: Boolean, distance: Double): Boolean =
        MosqueObservationTrace.fix(context, sessionId, fix, time, used, distance)

    override fun geofence(event: GeofencingEvent) {
        val reason = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> R.string.trace_mosque_geofence_enter
            Geofence.GEOFENCE_TRANSITION_EXIT -> R.string.trace_mosque_geofence_exit
            Geofence.GEOFENCE_TRANSITION_DWELL -> R.string.trace_mosque_geofence_dwell
            else -> R.string.trace_mosque_geofence_check
        }
        val location = event.triggeringLocation
        SilenceTraceStore.append(context, "mosque_geofence", context.getString(reason),
            at = location?.time?.takeIf { it > 0 } ?: System.currentTimeMillis(),
            latitude = location?.latitude, longitude = location?.longitude,
            accuracyMeters = location?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(), sessionId = sessionId,
            elapsedMillis = (location?.elapsedRealtimeNanos ?: SystemClock.elapsedRealtimeNanos()) / 1_000_000)
    }

    override fun prayerAlarm(prayerName: String, start: Boolean) {
        val prayer = runCatching { Prayer.valueOf(prayerName) }.getOrNull()
        val name = prayer?.displayName(context) ?: context.getString(R.string.mosque_rule_prayer)
        val message = if (start) R.string.trace_prayer_start_alarm else R.string.trace_prayer_end_alarm
        SilenceTraceStore.append(context, "prayer_alarm", context.getString(message, name), sessionId = sessionId)
    }
}
