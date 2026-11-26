package com.tunisianprayertimes.mosque

import android.content.Context
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.presence.AdaptiveObservation
import com.tunisianprayertimes.mosque.presence.AdaptiveVisitDetector
import com.tunisianprayertimes.mosque.presence.PresenceFix
import com.tunisianprayertimes.mosque.presence.PresenceState
import com.tunisianprayertimes.mosque.trace.SilenceTraceStore
import kotlin.math.roundToInt

/** All callers hold SilenceCoordinator.serial. Capture reflects the monitor and never drives it. */
internal object MosqueObservationTrace {
    private var session: Long? = null
    private val previous = mutableMapOf<String, String>()
    private var confirmedArea: String? = null

    private fun state(c: Context, id: Long, kind: String, detail: String, fix: PresenceFix? = null, seed: Boolean = false) {
        if (session != id) {
            previous.clear()
            confirmedArea = null
            session = id
        }
        if (previous[kind] == detail) return
        if (seed || SilenceTraceStore.append(c, kind, detail, latitude = fix?.point?.latitude,
                longitude = fix?.point?.longitude, accuracyMeters = fix?.accuracyMeters, sessionId = id)) {
            previous[kind] = detail
        }
    }

    fun window(c: Context, periods: MosquePeriods, id: Long, seed: Boolean = false) {
        val prayers = periods.targets.joinToString("، ") { MosquePrayerActions.name(c, it) }
        val detail = when {
            !PrefsManager.isEnabled(c) -> c.getString(R.string.trace_mosque_disabled)
            !MosqueSilenceSettings.combined(c) -> c.getString(R.string.trace_mosque_time_only)
            periods.silence != null -> c.getString(R.string.trace_mosque_silence_window, prayers)
            periods.observation != null -> c.getString(R.string.trace_mosque_observe_window, prayers)
            else -> c.getString(R.string.mosque_status_waiting)
        }
        state(c, id, "mosque_window", detail, seed = seed)
    }

    fun inactive(c: Context, id: Long, detail: String) {
        state(c, id, "mosque_presence", detail)
        confirmedArea = null
        previous.remove("mosque_sampling")
        previous.remove("mosque_decision")
    }

    fun engine(c: Context, id: Long, engine: AdaptiveObservation, areas: List<SavedSilenceArea>, fix: PresenceFix? = null) {
        val areaId = engine.detector.confirmedAreaId
        val name = if (areaId == null) null else areas.firstOrNull { it.geometry.id == areaId }?.name ?: areaId
        val prior = confirmedArea.takeIf { session == id }
        if (prior != null && name == null) {
            val exit = fix != null && engine.detector.clearlyAway && !engine.isUnavailable
            val reason = if (exit) R.string.trace_mosque_departed else R.string.trace_mosque_lost_evidence
            SilenceTraceStore.append(c, "mosque_visit_ended", c.getString(reason, prior), latitude = fix?.point?.latitude,
                longitude = fix?.point?.longitude, accuracyMeters = fix?.accuracyMeters, sessionId = id)
        }
        val detail = when (engine.state) {
            PresenceState.VISIT_CONFIRMED -> {
                val reason = when (engine.detector.arrival) {
                    AdaptiveVisitDetector.Arrival.WALKED_IN -> R.string.trace_mosque_walked_in
                    AdaptiveVisitDetector.Arrival.INSIDE -> R.string.trace_mosque_inside
                    AdaptiveVisitDetector.Arrival.EDGE -> R.string.trace_mosque_edge
                    AdaptiveVisitDetector.Arrival.EARLIER_PRECISE -> R.string.trace_mosque_earlier_precise
                    AdaptiveVisitDetector.Arrival.NEARBY -> R.string.trace_mosque_nearby
                    null -> R.string.mosque_adaptive_hold
                }
                c.getString(R.string.trace_mosque_arrival, name, c.getString(reason))
            }
            PresenceState.EXITING -> c.getString(R.string.trace_mosque_exiting)
            PresenceState.SCHEDULE_FALLBACK -> c.getString(R.string.mosque_adaptive_fallback)
            PresenceState.ACQUIRING -> c.getString(R.string.mosque_status_checking)
            PresenceState.IDLE -> c.getString(R.string.mosque_status_no_match)
            PresenceState.INACTIVE_NEEDS_REARM -> c.getString(R.string.mosque_status_rearm)
            PresenceState.INACTIVE_NEEDS_SETUP -> c.getString(R.string.mosque_status_setup)
        }
        state(c, id, "mosque_presence", detail, fix)
        confirmedArea = name
        val sampling = when (engine.sampling) {
            AdaptiveObservation.Sampling.PARKED -> R.string.trace_mosque_sampling_parked
            AdaptiveObservation.Sampling.BALANCED -> R.string.trace_mosque_sampling_balanced
            AdaptiveObservation.Sampling.PRECISE -> R.string.trace_mosque_sampling_precise
        }
        state(c, id, "mosque_sampling", c.getString(sampling), fix)
        val decision = if (engine.desired) R.string.trace_mosque_eligible else R.string.trace_mosque_ineligible
        state(c, id, "mosque_decision", c.getString(decision), fix)
    }

    fun fix(c: Context, id: Long, fix: PresenceFix, time: Long, used: Boolean, distance: Double): Boolean {
        val detail = when {
            fix.isMock -> c.getString(R.string.trace_mosque_mock_fix)
            !used || !distance.isFinite() -> c.getString(R.string.trace_mosque_rejected_fix)
            else -> c.getString(if (distance < 0) R.string.trace_mosque_inside_fix else R.string.trace_mosque_outside_fix,
                kotlin.math.abs(distance).roundToInt())
        }
        return SilenceTraceStore.append(c, "monitor_fix", detail, at = time, latitude = fix.point.latitude,
            longitude = fix.point.longitude, accuracyMeters = fix.accuracyMeters, sessionId = id,
            elapsedMillis = fix.elapsedRealtimeMillis)
    }
}
