package com.tunisianprayertimes.mosque

import android.content.Context
import android.database.sqlite.SQLiteException
import android.os.SystemClock
import android.util.Log
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.mosque.presence.AdaptiveObservation
import com.tunisianprayertimes.mosque.presence.PresenceFix
import com.tunisianprayertimes.mosque.trace.SilenceTraceStore
import com.tunisianprayertimes.mosque.trace.SilenceWalkBaseline
import com.tunisianprayertimes.mosque.trace.SilenceWalkSensors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Owned by the monitor under SilenceCoordinator.serial. A token can cover several separate visits. */
internal object DetectionRecording {
    private var token: String? = null
    private var session: Long? = null
    private var sawVisit = false
    private var waitingForReturn = false
    private var foregroundToken: String? = null
    private var currentPeriods: MosquePeriods? = null
    private val receivedStarts = mutableSetOf<String>()
    private var baseline: Job? = null

    fun awaitingReturn(token: String) = this.token == token && waitingForReturn

    fun acquiring(c: Context, token: String, engine: AdaptiveObservation, periods: MosquePeriods? = null,
        recheck: Boolean = false, prayerStart: Boolean = false, fix: PresenceFix? = null) {
        if (this.token != token) {
            stopped(c)
            this.token = token
        }
        if (periods != null) currentPeriods = periods
        val newPrayerStart = prayerStart && receivedStarts.addAll(periods?.silence?.occurrences.orEmpty())
        if (engine.isUnavailable || engine.sampling == AdaptiveObservation.Sampling.PARKED) return
        if (session == null) {
            // A departure can leave the monitor checking nearby locations in the same window.
            // Its ordinary ticks and outward fixes do not start another walk. A return fix,
            // explicit acquisition, or a fresh search after parking can begin the next report.
            val returning = fix != null && (fix.accuracyMeters ?: Double.POSITIVE_INFINITY) <= 30 &&
                (engine.detector.distance < 0 || engine.approachingOnLastFix)
            if (waitingForReturn && !recheck && !newPrayerStart && !returning && engine.detector.arrival == null) return
            val id = try {
                SilenceTraceStore.start(c, fix?.elapsedRealtimeMillis ?: SystemClock.elapsedRealtime())
            } catch (failure: SQLiteException) {
                Log.w("SilenceTrace", "Could not open the detection report", failure)
                return
            }
            session = id
            waitingForReturn = false
            val schedule = currentPeriods
            if (schedule != null) activeMosqueDiagnostics(c)?.period(schedule)
            val instant = SilenceScheduler.now()
            val elapsed = SystemClock.elapsedRealtime()
            val app = c.applicationContext
            baseline = CoroutineScope(Dispatchers.IO).launch {
                SilenceWalkBaseline.capture(app, id, schedule, instant, elapsed)
            }
            if (foregroundToken == token) SilenceWalkSensors.attach(c, id)
        }
        if (engine.detector.arrival != null) sawVisit = true
    }

    /** Called after the decision and Android rule write have been recorded. */
    fun settled(c: Context, token: String, engine: AdaptiveObservation) {
        if (this.token != token) return
        val parked = engine.sampling == AdaptiveObservation.Sampling.PARKED || engine.isUnavailable
        if (parked || sawVisit && engine.detector.arrival == null) {
            close(c)
            waitingForReturn = !parked
        }
    }

    fun foreground(c: Context, token: String) = synchronized(SilenceCoordinator.serial) {
        if (this.token == token && foregroundToken != token) {
            foregroundToken = token
            session?.let { SilenceWalkSensors.attach(c, it) }
        }
    }

    fun serviceGone(c: Context, token: String?) = synchronized(SilenceCoordinator.serial) {
        if (token != null && foregroundToken == token) {
            foregroundToken = null
            session?.let { SilenceWalkSensors.detach(c, it) }
        }
    }

    fun stopped(c: Context) {
        close(c)
        waitingForReturn = false
        token = null
        foregroundToken = null
        currentPeriods = null
        receivedStarts.clear()
    }

    private fun close(c: Context) {
        baseline?.cancel()
        baseline = null
        session?.let { id ->
            SilenceWalkSensors.detach(c, id)
            SilenceTraceStore.stop(c, id)
        }
        session = null
        sawVisit = false
    }
}
