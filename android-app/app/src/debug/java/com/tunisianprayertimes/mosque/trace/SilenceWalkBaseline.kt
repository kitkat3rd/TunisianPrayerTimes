package com.tunisianprayertimes.mosque.trace

import android.content.Context
import android.os.SystemClock
import com.tunisianprayertimes.AndroidAutoConnection
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceModeController
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.mosque.AdaptiveMosqueMonitor
import com.tunisianprayertimes.mosque.MosquePeriods
import com.tunisianprayertimes.mosque.MosquePrayerActions
import com.tunisianprayertimes.mosque.MosqueReadiness
import com.tunisianprayertimes.mosque.MosqueSilenceSettings
import com.tunisianprayertimes.mosque.PrivateSilenceAreaStore
import com.tunisianprayertimes.mosque.SilenceCoordinator
import com.tunisianprayertimes.mosque.SilenceOwner
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import com.tunisianprayertimes.silence.SilenceWindow
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Describe what the feature could do when a walk starts, without asking it to reconcile or acquire GPS. */
internal object SilenceWalkBaseline {
    fun capture(context: Context, sessionId: Long) {
        if (SilenceTraceStore.activeId(context) != sessionId) return
        val now = SilenceScheduler.now()
        val elapsedAt = SystemClock.elapsedRealtime()
        fun record(kind: String, detail: String) {
            SilenceTraceStore.append(context, kind, detail, at = now.toEpochMilli(), sessionId = sessionId,
                elapsedMillis = elapsedAt)
        }
        try {
            val mode = MosqueSilenceSettings.mode(context)
            val enabled = PrefsManager.isEnabled(context)
            val combined = mode == AutomaticSilenceMode.TIME_AND_MOSQUE
            val activation = when {
                enabled -> R.string.walk_context_enabled
                PrefsManager.isAutomaticSilenceRequested(context) -> R.string.mosque_waiting_for_place
                else -> R.string.mosque_master_off
            }
            val method = when (mode) {
                AutomaticSilenceMode.TIME_AND_MOSQUE -> R.string.mosque_mode_combined
                AutomaticSilenceMode.TIME_ONLY -> R.string.mosque_mode_time
                null -> R.string.mosque_invalid_mode
            }
            record("test_context", context.getString(activation) + " · " + context.getString(method))
            if (mode != null) {
                val areas = if (combined) PrivateSilenceAreaStore.read(context) else null
                val issues = MosqueReadiness.forMode(context, mode, areas)
                val readiness = buildList {
                    addAll(issues.map(context::getString))
                    if (isEmpty()) add(context.getString(R.string.mosque_requirements_ready))
                    if (areas?.available == true) add(context.getString(R.string.mosque_areas_title) + " · " +
                        context.getString(R.string.mosque_areas_count, areas.areas.count { it.geometry.enabled }, areas.areas.size))
                    if (AndroidAutoConnection.isProjected(context)) add(context.getString(R.string.walk_context_car))
                    if (SilenceModeController.isAutoSilencePausedForAwakeCheck) add(context.getString(R.string.walk_context_alarm))
                }
                record("test_context", readiness.joinToString("\n"))
            }
            val owners = if (MosqueSilenceSettings.usesOwnedRules(context)) {
                SilenceOwner.entries.filter { SilenceCoordinator.requested(context, it) }
            } else buildList {
                if (PrefsManager.isAutoSilenceActive(context)) add(SilenceOwner.PRAYER)
                if (PrefsManager.isManualSilenceActive(context)) add(SilenceOwner.MANUAL)
                if (WakeAlarmScheduler.isSilenceUntilAlarmActive(context)) add(SilenceOwner.WAKE)
            }
            record("test_context", if (owners.isEmpty()) context.getString(R.string.walk_context_no_owners)
                else context.getString(R.string.walk_context_owners, owners.joinToString("، ") { context.getString(it.title) }))
            if (combined && enabled) AdaptiveMosqueMonitor.traceCurrentState(context, sessionId)

            if (enabled && mode != null) {
                val place = PrayerTimesRepository.place(context)
                val windows = SilenceScheduler.planner(context, place).windows(now)
                val (dismissed, eligible) = windows.partition { SilenceScheduler.isDismissed(context, it.prayer, it.prayerDay) }
                dismissed.filter { w ->
                    now in w || combined && MosquePeriods.from(listOf(w), now.toEpochMilli()).observation != null
                }.forEach { w ->
                    record("prayer_schedule", context.getString(R.string.walk_schedule_dismissed, MosquePrayerActions.name(context, w)))
                }
                schedule(context, eligible, now, place.zone, combined).forEach { record("prayer_schedule", it) }
            }
        } catch (_: Exception) {
            record("test_context", context.getString(R.string.walk_context_incomplete))
        }
    }

    internal fun schedule(context: Context, windows: List<SilenceWindow>, now: Instant, zone: ZoneId,
        combined: Boolean): List<String> = buildList {
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx", Locale.ROOT).withZone(zone)
        val periods = MosquePeriods.from(windows, now.toEpochMilli())
        val observation = periods.observation.takeIf { combined }
        if (combined) add(if (observation == null) context.getString(R.string.walk_schedule_no_watch)
            else context.getString(R.string.walk_schedule_watch, format.format(Instant.ofEpochMilli(observation.start)),
                format.format(Instant.ofEpochMilli(observation.activeEnd))))
        val observedKeys = observation?.occurrences.orEmpty().map { it.removeSuffix("/early") }.toSet()
        val current = windows.filter { now in it || MosquePrayerActions.key(it) in observedKeys }
        if (periods.silence == null) add(context.getString(R.string.walk_schedule_no_silence))
        val next = windows.filter { it.start > now }.minByOrNull { it.start }
        for (w in (current + listOfNotNull(next)).distinct()) {
            val label = when {
                now in w -> R.string.walk_schedule_current
                w == next -> R.string.walk_schedule_next
                else -> R.string.walk_schedule_observing
            }
            add(context.getString(label, MosquePrayerActions.name(context, w), format.format(w.adhan),
                format.format(w.start), format.format(w.end)))
        }
        if (current.isEmpty() && next == null) add(context.getString(R.string.walk_schedule_none_upcoming))
    }
}
