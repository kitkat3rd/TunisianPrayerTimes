package com.tunisianprayertimes.mosque

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.location.Geofence
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.mosque.presence.*
import com.tunisianprayertimes.silence.SilenceWindow
import java.time.Instant
import java.util.UUID

internal data class MosquePeriods(val observation: WindowCoverage?, val silence: WindowCoverage?,
    val targets: List<SilenceWindow>, val continuationEnd: Long? = null) {
    companion object {
        const val LEAD = 600_000L
        const val CONTINUATION_MILLIS = 15 * 60_000L
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

/** Called under the same lock as the Android DND write. */
internal object AdaptiveMosqueMonitor {
    /** Granted only after Android accepts silence for a confirmed visit in a nominal window. */
    private data class HeldVisit(val areaId: String, val windows: List<SilenceWindow>, val expiresElapsed: Long)
    private data class Runtime(var start: Long, val areas: List<SilenceArea>, val token: String,
        val engine: AdaptiveObservation, var observedEnd: Long, var silenceStart: Long? = null,
        var held: HeldVisit? = null, var continuing: Boolean = false,
        var targets: List<SilenceWindow> = emptyList(), var journal: MosqueObservationInterruption? = null)
    private data class Schedule(val periods: MosquePeriods, val carriesVisit: Boolean = false)
    private var current: Runtime? = null
    private fun prefs(c: Context) = c.getSharedPreferences("mosque_presence", Context.MODE_PRIVATE)

    fun periods(c: Context): MosquePeriods = synchronized(SilenceCoordinator.serial) {
        schedule(c, SilenceScheduler.now(), SystemClock.elapsedRealtime()).periods
    }

    private fun schedule(c: Context, instant: Instant, elapsed: Long): Schedule {
        if (!MosqueSilenceSettings.combined(c) || !PrefsManager.isEnabled(c)) return Schedule(MosquePeriods(null, null, emptyList()))
        val windows = SilenceScheduler.planner(c).windows(instant).filter { !SilenceScheduler.isDismissed(c, it.prayer, it.prayerDay) }
        val wall = instant.toEpochMilli()
        val nominal = MosquePeriods.from(windows, wall)
        val r = current ?: return Schedule(nominal)
        val held = r.held ?: return Schedule(nominal)
        if (elapsed >= held.expiresElapsed || r.engine.fallback || !r.engine.desired ||
            r.engine.detector.confirmedAreaId != held.areaId || elapsed >= (r.engine.detector.expiry ?: 0L)) return Schedule(nominal)
        // An edited or dismissed occurrence cannot lend its old end to a different prayer.
        val tails = held.windows.filter { window ->
            window in windows && wall >= window.end.toEpochMilli() && wall < window.end.toEpochMilli() + MosquePeriods.CONTINUATION_MILLIS
        }
        if (tails.isEmpty() || !SilenceCoordinator.requested(c, SilenceOwner.MOSQUE)) return Schedule(nominal)
        // A new nominal window takes over a still-live visit without toggling its Android rule.
        if (nominal.silence != null) return Schedule(nominal, carriesVisit = true)
        val until = minOf(tails.maxOf { it.end.toEpochMilli() } + MosquePeriods.CONTINUATION_MILLIS,
            wall + (held.expiresElapsed - elapsed))
        val watch = nominal.observation
        val observation = WindowCoverage(watch?.start ?: r.start, maxOf(watch?.activeEnd ?: until, until),
            watch?.occurrences.orEmpty() + tails.map(MosquePrayerActions::key))
        return Schedule(MosquePeriods(observation, null, tails, continuationEnd = until), carriesVisit = true)
    }

    fun stop(c: Context, barrier: Boolean) {
        val r = current
        if (barrier && r != null) prefs(c).edit().putLong("adaptive_blocked_start", r.start).commit()
        if (r != null) activeMosqueDiagnostics(c)?.stopped(barrier)
        if (r?.journal != null) MosqueObservationInterruptions.finished(c)
        current = null
    }
    fun dismiss(c: Context, expected: Set<String>) {
        val before = periods(c)
        val activeIds = before.targets.map(MosquePrayerActions::key).toSet()
        val chosen = expected.intersect(activeIds)
        if (chosen.isEmpty()) return
        MosqueSilenceMonitor.recordDismissals(c, chosen)
        activeMosqueDiagnostics(c)?.skipped(before, chosen)
        val after = periods(c)
        activeMosqueDiagnostics(c)?.period(after)
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
            failedStart(c, serviceLost = true)
            MosqueSilenceMonitor.stopLocked(c, needsRearm = false)
        }
    }

    fun failedStart(c: Context, serviceLost: Boolean) {
        val r = current
        val now = SilenceScheduler.now().toEpochMilli()
        // A service or provider callback can arrive before the end alarm processes the cutoff.
        if (r != null && now >= r.observedEnd) return
        if (serviceLost) MosqueMetrics.flag(c, "interrupted")
        MosqueMetrics.fault(c, "monitor_start_failed", now)
        activeMosqueDiagnostics(c)?.failedStart()
        if (r != null) {
            if (r.journal != null) {
                MosqueObservationInterruptions.interrupted(c)
                r.journal = null
            }
            prefs(c).edit().putLong("adaptive_failed_start", r.start).commit()
        }
    }
    private fun blocked(c: Context, periods: MosquePeriods): Boolean {
        val state = prefs(c)
        return state.getLong("adaptive_blocked_start", -1) == periods.observation?.start ||
            state.getLong("blocked_silence_start", -1) == periods.silence?.start
    }

    fun reconcile(c: Context, canStart: Boolean = false, deliberate: Boolean = false, burst: Boolean = false,
        transition: Int? = null, evidenceFix: PresenceFix? = null, evidenceSession: Long? = null): MosqueObservationPlan? {
        val now = SystemClock.elapsedRealtime()
        val instant = SilenceScheduler.now()
        val wall = instant.toEpochMilli()
        val schedule = schedule(c, instant, now)
        val periods = schedule.periods
        val scope = periods.observation
        val continuing = periods.continuationEnd != null
        val savedAreas = if (scope == null) null else PrivateSilenceAreaStore.read(c)
        val issues = savedAreas?.let { MosqueReadiness.issues(c, it) }.orEmpty()
        val hardLoss = issues.any(MosqueReadiness::locationIssue)
        MosqueMetrics.scope(c, periods, wall, canStart, issues)
        MosqueWalkSharing.scope(c, periods)
        var diagnostics = activeMosqueDiagnostics(c)
        diagnostics?.period(periods)
        if (scope == null || issues.any { !MosqueReadiness.locationIssue(it) } || continuing && hardLoss) {
            diagnostics?.unavailable(issues)
            if (current != null) MosqueSilenceMonitor.stopLocked(c, needsRearm = false)
            else SilenceCoordinator.set(c, SilenceOwner.MOSQUE, false)
            return null
        }
        if (deliberate && canStart) prefs(c).edit().remove("adaptive_blocked_start").remove("blocked_silence_start").commit()
        val areas = requireNotNull(savedAreas).areas.map { it.geometry }.filter { it.enabled }
        var r = current
        if (r != null && schedule.carriesVisit) r.start = scope.start
        if (r != null && (r.start != scope.start || r.areas != areas)) {
            MosqueSilenceMonitor.stopLocked(c, needsRearm = false)
            r = null
        }
        val failed = prefs(c).getLong("adaptive_failed_start", -1) == scope.start
        if (failed && canStart) prefs(c).edit().remove("adaptive_failed_start").commit()
        if (blocked(c, periods) || failed && !canStart) {
            diagnostics?.blocked(startFailed = failed && !canStart)
            if (r != null) MosqueSilenceMonitor.stopLocked(c, needsRearm = false)
            else SilenceCoordinator.set(c, SilenceOwner.MOSQUE, false)
            return null
        }
        if (r == null) {
            if (continuing || !canStart && !hardLoss) {
                SilenceCoordinator.set(c, SilenceOwner.MOSQUE, false)
                return null
            }
            r = Runtime(scope.start, areas, UUID.randomUUID().toString(), AdaptiveObservation(areas, now), scope.activeEnd)
            current = r
            MosqueSilenceMonitor.rememberObservation(c, scope.activeEnd)
        }
        val engine = r.engine
        r.targets = periods.targets
        // A held visit bridges the cutoff, including a newer nominal window becoming active.
        // All other changes still invalidate pre-gap authority even on a late alarm.
        if (!schedule.carriesVisit && r.silenceStart != periods.silence?.start) engine.period(false, now)
        r.silenceStart = periods.silence?.start
        r.continuing = continuing
        engine.period(periods.silence != null || continuing, now)
        val previouslyDisabled = engine.isUnavailable
        engine.locationDisabled(hardLoss, now)
        if (hardLoss && !previouslyDisabled) MosqueObservationService.cancelNative(c)
        var recheck = canStart && deliberate
        if (canStart && !hardLoss) {
            if (transition != null) {
                val accepted = engine.geofence(now, transition)
                recheck = accepted && (transition == Geofence.GEOFENCE_TRANSITION_ENTER ||
                    transition == Geofence.GEOFENCE_TRANSITION_DWELL)
            }
            else engine.start(now, deliberate || burst && engine.detector.arrival == null)
        }
        engine.tick(now)
        MosqueMetrics.decision(c, engine, wall)
        DetectionRecording.acquiring(c, r.token, engine, periods = periods, recheck = recheck,
            prayerStart = canStart && burst)
        diagnostics = activeMosqueDiagnostics(c)
        diagnostics?.engine(engine, savedAreas.areas, evidenceFix.takeIf { diagnostics.sessionId == evidenceSession })
        if (continuing && (engine.detector.arrival == null || engine.fallback || !engine.desired)) {
            MosqueSilenceMonitor.stopLocked(c, needsRearm = false)
            return null
        }
        // Android's rule survives this process. Install its wake-up before requesting silence,
        // including the first provisional crossing, while the nominal cutoff still bounds it.
        val scopeEnd = now + scope.activeEnd - wall
        val silenceEnd = periods.silence?.let { now + it.activeEnd - wall }
        val graceEnd = periods.continuationEnd?.let { now + it - wall }
        val deadline = listOfNotNull(scopeEnd, silenceEnd, graceEnd, engine.deadline).min()
        if (engine.desired && !MosqueSilenceMonitor.armDeadline(c, deadline)) {
            MosqueMetrics.fault(c, "deadline_failed", wall)
            MosqueSilenceMonitor.stopLocked(c, needsRearm = false)
            return null
        }
        if (!SilenceCoordinator.set(c, SilenceOwner.MOSQUE, engine.desired) && engine.desired) {
            MosqueSilenceMonitor.stopLocked(c, needsRearm = true)
            return null
        }
        DetectionRecording.settled(c, r.token, engine)
        val area = engine.detector.confirmedAreaId
        if (!continuing) {
            r.held = if (periods.silence != null && area != null && engine.desired && !engine.fallback &&
                SilenceCoordinator.requested(c, SilenceOwner.MOSQUE)) {
                r.held?.takeIf { it.areaId == area && it.windows == periods.targets } ?: HeldVisit(area, periods.targets,
                    now + (periods.silence.activeEnd - wall) + MosquePeriods.CONTINUATION_MILLIS)
            } else null
        }
        // Enlarge the existing native request before the cutoff; its lifetime must not depend on
        // the end alarm being delivered at the exact instant the old request would have expired.
        val hold = r.held
        MosqueMetrics.hold(c, hold != null, periods.continuationEnd)
        val nativeEnd = if (hold == null || periods.silence == null) scope.activeEnd else maxOf(scope.activeEnd,
            minOf(periods.silence.activeEnd + MosquePeriods.CONTINUATION_MILLIS, wall + (hold.expiresElapsed - now)))
        if (r.observedEnd != nativeEnd) {
            r.observedEnd = nativeEnd
            MosqueSilenceMonitor.rememberObservation(c, nativeEnd)
        }
        val observeEnd = now + nativeEnd - wall
        engine.observationEnd = observeEnd
        if (!engine.desired) MosqueSilenceMonitor.armDeadline(c, deadline)
        if (hardLoss || engine.sampling == AdaptiveObservation.Sampling.PARKED) {
            if (r.journal != null) {
                MosqueObservationInterruptions.finished(c)
                r.journal = null
            }
            return null
        }
        if (r.journal != null) {
            recordObservation(c, r)
            MosqueMetrics.started(c, wall)
        }
        return MosqueObservationPlan(r.token, observeEnd, deadline,
            engine.sampling == AdaptiveObservation.Sampling.PRECISE, engine.state, areas,
            engine.generation, minOf(observeEnd, engine.requestEnd ?: observeEnd))
    }

    fun canActivate(c: Context, alreadyRequested: Boolean): Boolean {
        val r = current ?: return false
        val now = SystemClock.elapsedRealtime()
        val schedule = schedule(c, SilenceScheduler.now(), now)
        val p = schedule.periods
        val continuing = p.continuationEnd != null
        // A binder recheck can cross the boundary after reconcile took its schedule snapshot.
        // Only the already-owned, same visit can bridge that change of prayer or observation.
        if (!(alreadyRequested && schedule.carriesVisit)) {
            if (p.silence == null || p.silence.start != r.silenceStart || p.observation?.start != r.start) return false
        }
        if (blocked(c, p)) return false
        val issues = MosqueReadiness.issues(c)
        if (issues.any { !MosqueReadiness.locationIssue(it) }) return false
        val hardLoss = issues.any(MosqueReadiness::locationIssue)
        if (continuing && hardLoss) return false
        r.engine.locationDisabled(hardLoss, now)
        r.engine.tick(now)
        return r.engine.desired && (!continuing || (r.engine.detector.arrival != null && !r.engine.fallback))
    }

    fun plan(c: Context, token: String): MosqueObservationPlan? =
        if (current?.token == token) reconcile(c) else null

    fun setWakeCoverage(token: String, until: Long) = synchronized(SilenceCoordinator.serial) {
        current?.takeIf { it.token == token }?.engine?.wakeCoverageUntil = until
    }

    fun accepted(c: Context, token: String, generation: Long) {
        val r = current ?: return
        if (r.token != token || r.engine.generation != generation ||
            r.engine.sampling == AdaptiveObservation.Sampling.PARKED) return
        r.engine.accepted(generation, SystemClock.elapsedRealtime())
        MosqueMetrics.started(c, SilenceScheduler.now().toEpochMilli())
        recordObservation(c, r)
    }

    private fun recordObservation(c: Context, r: Runtime) {
        val targets = r.targets
        val observation = MosqueObservationInterruption(r.token,
            targets.map { it.prayer }.distinct().singleOrNull(),
            targets.map { it.prayerDay }.distinct().singleOrNull(),
            r.engine.detector.arrival != null)
        if (observation != r.journal) {
            MosqueObservationInterruptions.observing(c, observation)
            r.journal = observation
        }
    }

    fun observe(c: Context, token: String, generation: Long, fix: PresenceFix, fixTime: Long) {
        val plan = plan(c, token) ?: return
        if (plan.generation != generation) return
        val runtime = current ?: return
        val engine = runtime.engine
        val previousArrival = engine.detector.arrival
        val wasProvisional = engine.detector.tentativeUntil != null
        val used = engine.observe(fix, SystemClock.elapsedRealtime(), DetectionRecording.awaitingReturn(token))
        MosqueWalkSharing.fix(c, fix, fixTime, used, runtime.areas)
        if (used && previousArrival == null && engine.detector.arrival != null) MosqueWalkSharing.event(c, fixTime, "arrival")
        if (used && previousArrival != null && engine.detector.arrival == null) MosqueWalkSharing.event(c, fixTime, "departure")
        if (used && !wasProvisional && engine.detector.tentativeUntil != null) MosqueWalkSharing.event(c, fixTime, "provisional")
        MosqueMetrics.fix(c, used, fix.accuracyMeters, SystemClock.elapsedRealtime() - fix.elapsedRealtimeMillis,
            used && engine.detector.distance <= 0, SilenceScheduler.now().toEpochMilli())
        // A first native fix can race the asynchronous request-accepted callback.
        if (used && runtime.journal == null && engine.sampling != AdaptiveObservation.Sampling.PARKED)
            recordObservation(c, runtime)
        if (used) DetectionRecording.acquiring(c, token, engine, fix = fix)
        val diagnostics = activeMosqueDiagnostics(c)
        val recorded = diagnostics?.fix(fix, fixTime, used, engine.detector.distance) == true
        if (runtime.continuing && engine.detector.arrival == null && diagnostics != null) {
            diagnostics.engine(engine, PrivateSilenceAreaStore.read(c).areas, fix.takeIf { used && recorded })
        }
        reconcile(c, evidenceFix = fix.takeIf { used && recorded }, evidenceSession = diagnostics?.sessionId)
    }
    fun requestedState(c: Context): PresenceState? = synchronized(SilenceCoordinator.serial) {
        current?.engine?.state?.takeIf { SilenceCoordinator.requested(c, SilenceOwner.MOSQUE) }
    }

    fun fallbackRequested(c: Context): Boolean = requestedState(c) == PresenceState.SCHEDULE_FALLBACK

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
