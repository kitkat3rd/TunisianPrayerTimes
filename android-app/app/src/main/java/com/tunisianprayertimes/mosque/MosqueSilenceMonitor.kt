package com.tunisianprayertimes.mosque

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.mosque.presence.*

internal fun Location.toMosqueFix(): PresenceFix {
    val mock = if (Build.VERSION.SDK_INT >= 31) isMock else @Suppress("DEPRECATION") isFromMockProvider
    return PresenceFix(GeoPoint(latitude, longitude), if (hasAccuracy()) accuracy.toDouble() else null,
        elapsedRealtimeNanos / 1_000_000L, mock)
}

internal const val ARRIVAL_FIX_INTERVAL_MILLIS = 2_000L

data class MosqueObservationPlan(val token: String, val endElapsed: Long, val deadlineElapsed: Long,
    val highAccuracy: Boolean, val state: PresenceState, val areas: List<SilenceArea>,
    val generation: Long = 0, val providerEnd: Long = endElapsed) {
    internal val intervalMillis: Long get() = when {
        !highAccuracy -> 60_000L
        state == PresenceState.ACQUIRING || state == PresenceState.ENTERING -> ARRIVAL_FIX_INTERVAL_MILLIS
        else -> 5_000L
    }
}

/** Production authority: actual planner intervals, never the future-prayer/45-minute bridge lookup. */
object MosqueSilenceMonitor {
    private var recoveryDone = false
    // Only a successful AlarmManager call in this process can establish this value.
    private var armedDeadline: Long? = null
    private fun prefs(context: Context) = context.getSharedPreferences("mosque_presence", Context.MODE_PRIVATE)
    private fun boot(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).toString()

    fun hasWindow(context: Context): Boolean = synchronized(SilenceCoordinator.serial) {
        AdaptiveMosqueMonitor.periods(context).silence != null
    }

    fun canObserve(context: Context): Boolean = synchronized(SilenceCoordinator.serial) {
        AdaptiveMosqueMonitor.periods(context).observation != null
    }

    internal fun rememberObservation(context: Context, end: Long) {
        val state = prefs(context)
        if (state.getLong("window_end", -1) != end || state.getString("boot", null) != boot(context))
            state.edit().putString("boot", boot(context)).putLong("window_end", end).commit()
    }

    /** Stable prayer/day suppression survives the legacy place-change dismissal pruning. */
    fun recordDismissals(context: Context, occurrences: Collection<String>) = synchronized(SilenceCoordinator.serial) {
        MosqueMetrics.dismiss(context, occurrences)
        val today = SilenceScheduler.now().atZone(com.tunisianprayertimes.PrayerTimesRepository.place(context).zone).toLocalDate()
        val state = prefs(context)
        val kept = (state.getStringSet("dismissed_occurrences", emptySet()).orEmpty() + occurrences).filter { key ->
            runCatching { !java.time.LocalDate.parse(key.substringAfter('@')).isBefore(today.minusDays(4)) }.getOrDefault(false)
        }
        state.edit().putStringSet("dismissed_occurrences", kept.toSet()).commit()
    }
    fun isDismissed(context: Context, occurrence: String): Boolean = synchronized(SilenceCoordinator.serial) {
        prefs(context).getStringSet("dismissed_occurrences", emptySet()).orEmpty().contains(occurrence)
    }

    /** May start only from a visible user action, exact user-requested prayer start, or GMS geofence. */
    internal fun reconcile(context: Context, canStart: Boolean = false, deliberate: Boolean = false,
        burst: Boolean = false, transition: Int? = null, trigger: (MosqueObservationDiagnostics.() -> Unit)? = null) {
        val app = context.applicationContext
        val plan = synchronized(SilenceCoordinator.serial) {
            recoverLocked(app)
            traceObservationTrigger(app, trigger) {
                AdaptiveMosqueMonitor.reconcile(app, canStart, deliberate, burst, transition)
            }
        }
        MosqueMetrics.finish(app, SilenceScheduler.now().toEpochMilli())
        if (plan != null && canStart) MosqueObservationService.start(app, plan.token)
        else if (plan == null) MosqueObservationService.stop(app)
    }

    internal fun canActivateLocked(context: Context, alreadyRequested: Boolean = false) =
        AdaptiveMosqueMonitor.canActivate(context, alreadyRequested)

    fun onLocation(context: Context, token: String, location: Location, generation: Long = 0) = synchronized(SilenceCoordinator.serial) {
        AdaptiveMosqueMonitor.observe(context, token, generation, location.toMosqueFix(), location.time)
    }

    fun plan(context: Context, token: String): MosqueObservationPlan? = synchronized(SilenceCoordinator.serial) {
        AdaptiveMosqueMonitor.plan(context, token)
    }

    internal fun stopLocked(context: Context, needsRearm: Boolean) {
        val released = SilenceCoordinator.set(context, SilenceOwner.MOSQUE, false)
        AdaptiveMosqueMonitor.stop(context, needsRearm)
        if (released) {
            deadlineManager(context).cancel(deadlineIntent(context))
            armedDeadline = null
        }
        MosqueObservationService.cancelNative(context)
        DetectionRecording.stopped(context)
    }

    internal fun recoverLocked(context: Context) {
        if (recoveryDone) return
        recoveryDone = true
        val state = prefs(context)
        // No previous process can still be sampling. An accepted observation without its
        // clean end is useful to the user, even if its prayer window ended before this launch.
        MosqueObservationInterruptions.interrupted(context)
        // Older versions journaled a boundary-only visit by its silence start. Its samples
        // cannot be reused, and an interrupted or paused visit still needs an explicit recheck.
        if (state.contains("coverage_start")) {
            val edit = state.edit().remove("coverage_start").remove("live").remove("needs_rearm").remove("token")
            val start = state.getLong("coverage_start", -1)
            if (start >= 0 && (state.getBoolean("live", false) || state.getBoolean("needs_rearm", false))) {
                edit.putLong("blocked_silence_start", start)
            }
            edit.commit()
        }
        SilenceCoordinator.set(context, SilenceOwner.MOSQUE, false)
        // A delivered GMS transition can itself cold-start this process. Preserve its finite, same-boot
        // registration until the receiver validates the actual current window; otherwise every cold
        // arrival would lose its legitimate start route in Application.onCreate.
        if (!MosqueSilenceSettings.combined(context) || !PrefsManager.isEnabled(context) ||
            state.getString("boot", null) != boot(context) || state.getLong("window_end", 0) <= System.currentTimeMillis())
            MosqueObservationService.removeSavedGeofences(context)
        else MosqueObservationService.removeSavedGeofences(context, includeCurrent = false)
    }

    fun environmentChanged(context: Context, failed: Boolean = false) {
        synchronized(SilenceCoordinator.serial) {
            if (failed) AdaptiveMosqueMonitor.failedStart(context, serviceLost = false)
            stopLocked(context, needsRearm = false)
        }
        // No implicit location start after edits/settings changes; the screen offers an explicit recheck.
    }
    fun skip(context: Context, expected: Set<String>) {
        synchronized(SilenceCoordinator.serial) {
            AdaptiveMosqueMonitor.dismiss(context, expected)
        }
        MosqueObservationService.stop(context)
    }
    fun status(context: Context): PresenceState? = synchronized(SilenceCoordinator.serial) {
        AdaptiveMosqueMonitor.status(context)
    }
    fun endWall(context: Context): Long? = synchronized(SilenceCoordinator.serial) {
        AdaptiveMosqueMonitor.periods(context).let { it.silence?.activeEnd ?: it.continuationEnd }
    }

    private fun deadlineManager(context: Context) = context.getSystemService(AlarmManager::class.java)
    private fun deadlineIntent(context: Context) = PendingIntent.getBroadcast(context, 81_201,
        Intent(context, MosqueControlReceiver::class.java).setAction(MosqueControlReceiver.DEADLINE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    internal fun deadlineDelivered(context: Context) {
        synchronized(SilenceCoordinator.serial) { armedDeadline = null }
        reconcile(context)
    }

    internal fun armDeadline(context: Context, elapsed: Long): Boolean {
        val manager = deadlineManager(context)
        return try {
            if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
                armedDeadline = null
                return false
            }
            // A live earlier wake can reevaluate the visit. Keep it in place while later fixes
            // extend support, rather than continually pushing the safety alarm into the future.
            val armed = armedDeadline
            if (armed != null && armed > SystemClock.elapsedRealtime() && armed <= elapsed) return true
            manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsed, deadlineIntent(context))
            armedDeadline = elapsed
            true
        } catch (_: RuntimeException) {
            armedDeadline = null
            false
        }
    }
}
