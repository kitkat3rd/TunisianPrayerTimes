package com.tunisianprayertimes.mosque

import android.content.Context
import android.os.SystemClock
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.mosque.presence.*
import com.tunisianprayertimes.silence.SilenceWindow
import java.util.UUID

internal data class MosquePeriods(val observation: WindowCoverage?, val silence: WindowCoverage?,
    val targets: List<SilenceWindow>) {
    companion object {
        const val LEAD = 600_000L
        fun earlyStart(w: SilenceWindow) = minOf(w.start.toEpochMilli(), w.adhan.toEpochMilli() - LEAD)
        fun from(windows: List<SilenceWindow>, now: Long): MosquePeriods {
            fun id(w: SilenceWindow) = "${w.prayer}@${w.prayerDay}"
            val silence = windows.map { PrayerWindow(id(it), it.start.toEpochMilli(), it.end.toEpochMilli()) }
            val watch = windows.flatMap { w ->
                val a = w.adhan.toEpochMilli()
                if (w.start.toEpochMilli() > a + LEAD) listOf(
                    PrayerWindow(id(w) + "/early", earlyStart(w), a),
                    PrayerWindow(id(w), w.start.toEpochMilli(), w.end.toEpochMilli()))
                else listOf(PrayerWindow(id(w), earlyStart(w), w.end.toEpochMilli()))
            }
            val observing = WindowScope.coverage(watch, emptySet(), now)
            val active = WindowScope.coverage(silence, emptySet(), now)
            val ids = active?.occurrences ?: observing?.occurrences.orEmpty().map { it.removeSuffix("/early") }.toSet()
            return MosquePeriods(observing, active, windows.filter { id(it) in ids }.sortedBy { it.start })
        }
    }
}

/** Called under the same lock as the Android DND write. No position or visit history is persisted. */
internal object AdaptiveMosqueMonitor {
    private data class Runtime(var start: Long, val areas: List<SilenceArea>, val token: String,
        val engine: AdaptiveObservation, var observedEnd: Long, var silenceStart: Long? = null)
    private var current: Runtime? = null
    private fun prefs(c: Context) = c.getSharedPreferences("mosque_presence", Context.MODE_PRIVATE)

    fun periods(c: Context): MosquePeriods {
        if (!MosqueSilenceSettings.combined(c) || !PrefsManager.isEnabled(c)) return MosquePeriods(null, null, emptyList())
        val now = SilenceScheduler.now()
        val windows = SilenceScheduler.planner(c).windows(now).filter { !SilenceScheduler.isDismissed(c, it.prayer, it.prayerDay) }
        return MosquePeriods.from(windows, now.toEpochMilli())
    }

    fun stop(c: Context, barrier: Boolean) {
        val r = current
        if (barrier && r != null) prefs(c).edit().putLong("adaptive_blocked_start", r.start).commit()
        current = null
    }
    fun dismiss(c: Context, expected: Set<String>) {
        val before = periods(c)
        val activeIds = before.targets.map(MosquePrayerActions::key).toSet()
        val chosen = expected.intersect(activeIds)
        if (chosen.isEmpty()) return
        MosqueSilenceMonitor.recordDismissals(c, chosen)
        val after = periods(c)
        val beforeWatch = before.observation?.occurrences.orEmpty()
        val afterWatch = after.observation
        if (afterWatch == null || beforeWatch.intersect(afterWatch.occurrences).isEmpty()) {
            MosqueSilenceMonitor.stopLocked(c, needsRearm = false)
        } else {
            // The same observation/active prayer still covers this instant after another prayer was skipped.
            current?.start = afterWatch.start
            if (before.silence?.occurrences.orEmpty().intersect(after.silence?.occurrences.orEmpty()).isNotEmpty())
                current?.silenceStart = after.silence?.start
            reconcile(c)
        }
    }

    fun serviceGone(c: Context, token: String?) = synchronized(SilenceCoordinator.serial) {
        val r = current
        if (r != null && token == r.token && r.engine.sampling != AdaptiveObservation.Sampling.PARKED) {
            // The provider is no longer collecting. Its 90-second request cannot prove an outage.
            failedStart(c)
            MosqueSilenceMonitor.stopLocked(c, needsRearm = false)
        }
    }

    fun failedStart(c: Context) {
        current?.let { prefs(c).edit().putLong("adaptive_failed_start", it.start).commit() }
    }
    private fun blocked(c: Context, periods: MosquePeriods): Boolean {
        val state = prefs(c)
        return state.getLong("adaptive_blocked_start", -1) == periods.observation?.start ||
            state.getLong("blocked_silence_start", -1) == periods.silence?.start
    }

    fun reconcile(c: Context, canStart: Boolean = false, deliberate: Boolean = false, burst: Boolean = false, transition: Int? = null): MosqueObservationPlan? {
        val periods = periods(c)
        val scope = periods.observation
        val now = SystemClock.elapsedRealtime()
        val savedAreas = if (scope == null) null else PrivateSilenceAreaStore.read(c)
        val issues = savedAreas?.let { MosqueReadiness.issues(c, it) }.orEmpty()
        val hardLoss = issues.any(MosqueReadiness::locationIssue)
        if (scope == null || issues.any { !MosqueReadiness.locationIssue(it) }) {
            if (current != null) MosqueSilenceMonitor.stopLocked(c, needsRearm = false)
            else SilenceCoordinator.set(c, SilenceOwner.MOSQUE, false)
            return null
        }
        if (deliberate && canStart) prefs(c).edit().remove("adaptive_blocked_start").remove("blocked_silence_start").commit()
        val areas = requireNotNull(savedAreas).areas.map { it.geometry }.filter { it.enabled }
        var r = current
        if (r != null && (r.start != scope.start || r.areas != areas)) {
            MosqueSilenceMonitor.stopLocked(c, needsRearm = false)
            r = null
        }
        val failed = prefs(c).getLong("adaptive_failed_start", -1) == scope.start
        if (failed && canStart) prefs(c).edit().remove("adaptive_failed_start").commit()
        if (blocked(c, periods) || failed && !canStart) {
            if (r != null) MosqueSilenceMonitor.stopLocked(c, needsRearm = false)
            else SilenceCoordinator.set(c, SilenceOwner.MOSQUE, false)
            return null
        }
        if (r == null) {
            if (!canStart && !hardLoss) {
                SilenceCoordinator.set(c, SilenceOwner.MOSQUE, false)
                return null
            }
            r = Runtime(scope.start, areas, UUID.randomUUID().toString(), AdaptiveObservation(areas, now), scope.activeEnd)
            current = r
            MosqueSilenceMonitor.rememberObservation(c, scope.activeEnd)
        } else if (r.observedEnd != scope.activeEnd) {
            r.observedEnd = scope.activeEnd
            MosqueSilenceMonitor.rememberObservation(c, scope.activeEnd)
        }
        val engine = r.engine
        // Invalidate pre-gap authority even if the end broadcast arrived late.
        if (r.silenceStart != periods.silence?.start) engine.period(false, now)
        r.silenceStart = periods.silence?.start
        engine.period(periods.silence != null, now)
        val previouslyDisabled = engine.isUnavailable
        engine.locationDisabled(hardLoss, now)
        if (hardLoss && !previouslyDisabled) MosqueObservationService.cancelNative(c)
        if (canStart && !hardLoss) {
            if (transition != null) engine.geofence(now, transition)
            else engine.start(now, deliberate || burst && engine.detector.arrival == null)
        }
        engine.tick(now)
        if (!SilenceCoordinator.set(c, SilenceOwner.MOSQUE, engine.desired) && engine.desired) {
            MosqueSilenceMonitor.stopLocked(c, needsRearm = true)
            return null
        }
        val wall = SilenceScheduler.now().toEpochMilli()
        val observeEnd = now + scope.activeEnd - wall
        val silenceEnd = periods.silence?.let { now + it.activeEnd - wall }
        val deadline = listOfNotNull(observeEnd, silenceEnd, engine.deadline).min()
        MosqueSilenceMonitor.armDeadline(c, deadline)
        if (hardLoss || engine.sampling == AdaptiveObservation.Sampling.PARKED) return null
        return MosqueObservationPlan(r.token, observeEnd, deadline,
            engine.sampling == AdaptiveObservation.Sampling.PRECISE, engine.state, areas,
            engine.generation, minOf(observeEnd, engine.requestEnd ?: observeEnd))
    }

    fun canActivate(c: Context): Boolean {
        val r = current ?: return false
        val p = periods(c)
        if (p.silence == null || p.silence.start != r.silenceStart || p.observation?.start != r.start || blocked(c, p)) return false
        val issues = MosqueReadiness.issues(c)
        if (issues.any { !MosqueReadiness.locationIssue(it) }) return false
        r.engine.locationDisabled(issues.any(MosqueReadiness::locationIssue), SystemClock.elapsedRealtime())
        r.engine.tick(SystemClock.elapsedRealtime())
        return r.engine.desired
    }

    fun plan(c: Context, token: String): MosqueObservationPlan? =
        if (current?.token == token) reconcile(c) else null

    fun accepted(c: Context, token: String, generation: Long) {
        val r = current ?: return
        if (r.token == token) r.engine.accepted(generation, SystemClock.elapsedRealtime())
    }

    fun observe(c: Context, token: String, generation: Long, fix: PresenceFix) {
        val plan = plan(c, token) ?: return
        if (plan.generation != generation) return
        current?.engine?.observe(fix, SystemClock.elapsedRealtime())
        reconcile(c)
    }
    fun fallbackRequested(c: Context): Boolean = synchronized(SilenceCoordinator.serial) {
        current?.engine?.fallback == true && SilenceCoordinator.requested(c, SilenceOwner.MOSQUE)
    }

    fun status(c: Context): PresenceState? {
        reconcile(c)
        current?.let { return it.engine.state }
        val p = periods(c)
        return p.observation?.let {
            if (blocked(c, p)) PresenceState.INACTIVE_NEEDS_REARM
            else if (prefs(c).getLong("adaptive_failed_start", -1) == it.start) PresenceState.INACTIVE_NEEDS_SETUP else null
        }
    }
}
