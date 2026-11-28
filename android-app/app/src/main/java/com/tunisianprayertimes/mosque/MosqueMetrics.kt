package com.tunisianprayertimes.mosque

import android.content.Context
import android.os.SystemClock
import com.tunisianprayertimes.mosque.presence.AdaptiveObservation
import com.tunisianprayertimes.mosque.presence.AdaptiveVisitDetector.Departure
import com.tunisianprayertimes.silence.SilenceWindow
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Only coarse decision values cross this boundary. The executor never receives a location or saved
 * area.
 */
internal object MosqueMetrics {
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private var active = emptyList<String>() // accessed on the journal executor only

    @androidx.annotation.VisibleForTesting
    internal fun awaitWrites() {
        executor.submit {}.get(15, java.util.concurrent.TimeUnit.SECONDS)
    }

    private const val DAY = 86_400_000L

    private fun key(w: SilenceWindow) = MosquePrayerActions.key(w)

    private fun counts(s: JSONObject) = s.getJSONObject("counts")

    private fun event(c: Context, body: MosqueReportStore.() -> Unit) {
        val app = c.applicationContext
        val epoch = generation.get()
        executor.execute {
            if (epoch != generation.get()) return@execute
            runCatching {
                    val store = MosqueReportStore.get(app)
                    store.db.atomic {
                        if (!MosqueReportStore.permitted(app)) {
                            if (active.isNotEmpty() || store.value("collecting") != null)
                                store.clearMetrics(clearQuestions = !MosqueReportStore.enabled(app))
                            active = emptyList()
                        } else {
                            if (store.value("collecting") == null) store.put("collecting", "1")
                            store.body()
                        }
                    }
                    MosqueReportDelivery.scheduleNew(app, store)
                }
                .onFailure {
                    android.util.Log.w("MosqueReports", "Could not update local counters")
                }
        }
    }

    fun resume(c: Context) {
        MosqueWalkSharing.finish(c, System.currentTimeMillis(), recovering = true)
        event(c) { finalizeDue(System.currentTimeMillis()) }
        adoption(c, selected = false)
        executor.execute {
            runCatching { MosqueReportDelivery.recover(c, MosqueReportStore.get(c)) }
        }
    }

    fun setEnabled(c: Context, enabled: Boolean) {
        val store = MosqueReportStore.get(c)
        store.db.atomic {
            store.put("participation", if (enabled) "on" else "off")
            if (!enabled) store.clearMetrics()
        }
        generation.incrementAndGet()
        if (!enabled) MosqueReportDelivery.cancelMetrics(c)
        resume(c)
    }

    fun adoption(c: Context, selected: Boolean) {
        val combined = MosqueSilenceSettings.combined(c)
        val enabled = com.tunisianprayertimes.PrefsManager.isEnabled(c)
        event(c) {
            val now = System.currentTimeMillis()
            val day = java.time.LocalDate.now().toString()
            val n = JSONObject()
            val week = (now / (7 * DAY)).toString()
            if (value("observed_week") != week) {
                put("observed_week", week)
                n.put("setup_participant", 1)
                if (enabled && combined) n.put("setup_enabled", 1)
            }
            if (selected && value("selected_at") == null) {
                put("selected_at", now.toString())
                put("selection_day", day)
                put("selection_meta", MosqueReportStore.metadata().toString())
                n.put("setup_selected", 1)
            }
            value("selected_at")?.toLongOrNull()?.let { at ->
                if (now - at in 0..7 * DAY && combined && enabled && value("setup_ready") == null) {
                    // Inspect system readiness outside the database transaction in settings/monitor
                    // callbacks.
                    if (value("readiness") == "ready") {
                        put("setup_ready", "1")
                        n.put("setup_ready", 1)
                    }
                }
                if (now - at in 7 * DAY until 14 * DAY && value("week_two") == null) {
                    put("week_two", "1")
                    val followup = JSONObject().put("setup_followup", 1)
                        .put(if (enabled && combined) "setup_still_enabled" else "setup_disabled", 1)
                    if (value("setup_ready") == "1") followup.put("setup_completed7", 1)
                    queue("metrics", JSONObject(value("selection_meta") ?: MosqueReportStore.metadata().toString())
                        .put("kind","setup").put("prayer","APP").put("day",value("selection_day") ?: day)
                        .put("counts",followup), now)
                }
            }
            if (n.length() > 0)
                queue(
                    "metrics",
                    MosqueReportStore.metadata()
                        .put("kind", "setup")
                        .put("prayer", "APP")
                        .put("day", day)
                        .put("counts", n),
                    now,
                )
        }
    }

    fun readiness(c: Context, ready: Boolean) =
        event(c) {
            val status = if (ready) "ready" else "blocked"
            if (value("readiness") != status) {
                put("readiness", status)
                adoption(c, selected = false)
            }
        }

    private fun state(w: SilenceWindow, expected: Boolean): JSONObject =
        JSONObject().apply {
            put("meta", MosqueReportStore.metadata())
            put("watch", MosquePeriods.earlyStart(w))
            put("start", w.start.toEpochMilli())
            put("nominal", w.end.toEpochMilli())
            put("end", w.end.toEpochMilli())
            put("prayer", w.prayer.name)
            put("day", w.prayerDay.toString())
            put("counts", JSONObject().put(if (expected) "expected" else "joined_in_progress", 1))
        }

    fun scheduled(c: Context, windows: List<SilenceWindow>, now: Long) {
        val epoch = generation.get()
        executor.execute {
            runCatching {
                val store = MosqueReportStore.get(c)
                store.db.atomic {
                    if (generation.get() != epoch || !MosqueReportStore.enabled(c)) return@atomic
                    val current =
                        store.value("question")?.takeIf(String::isNotEmpty)?.let(::JSONObject)
                    val last = store.value("lastQuestion")?.toLongOrNull() ?: 0L
                    if (now - last < 14 * DAY) return@atomic
                    if (current != null && now - current.getLong("end") in 0..DAY) return@atomic
                    val candidates =
                        windows.filter {
                            it.start.toEpochMilli() >= now && it.start.toEpochMilli() < now + DAY
                        }
                    if (current != null && windows.any { key(it) == current.optString("key") && it.end.toEpochMilli() > now })
                        return@atomic
                    val sample = candidates.randomOrNull()
                    store.put(
                        "question",
                        sample
                            ?.let {
                                JSONObject()
                                    .put("key", key(it))
                                    .put("prayer", it.prayer.name)
                                    .put("end", it.end.toEpochMilli())
                                    .toString()
                            }
                            .orEmpty(),
                    )
                }
            }
        }
        scheduleMetrics(c, windows, now)
    }

    private fun scheduleMetrics(c: Context, windows: List<SilenceWindow>, now: Long) =
        event(c) {
            finalizeDue(now)
            val next = windows.filter { it.end.toEpochMilli() > now }.associateBy(::key)
            for (old in keys("SELECT key FROM windows WHERE end_ms>?", now.toString())) {
                if (old !in next && !old.startsWith("shared:")) {
                    val abandoned = window(old) ?: continue
                    if (counts(abandoned).has("started") || counts(abandoned).has("skipped")) {
                        counts(abandoned).put("skipped", 1)
                        if (counts(abandoned).has("expected"))
                            counts(abandoned).put("skipped_expected", 1)
                        emit(abandoned, "summary", counts(abandoned), now)
                    }
                    db.execSQL("DELETE FROM windows WHERE key=?", arrayOf(old))
                }
            }
            for ((id, w) in next) {
                val s = window(id)
                if (s == null) save(id, state(w, now <= MosquePeriods.earlyStart(w)))
                else if (
                    s.getLong("nominal") != w.end.toEpochMilli() ||
                        s.getLong("start") != w.start.toEpochMilli()
                ) {
                    s.put("watch", MosquePeriods.earlyStart(w))
                        .put("start", w.start.toEpochMilli())
                        .put("nominal", w.end.toEpochMilli())
                        .put("end", w.end.toEpochMilli())
                    save(id, s)
                }
            }
        }

    fun scope(c: Context, periods: MosquePeriods, now: Long, canStart: Boolean, issues: List<Int>) {
        val windows = periods.targets.toList()
        val grace = periods.continuationEnd
        val blockers = issues.map { c.resources.getResourceEntryName(it) }
        event(c) {
            active = windows.map(::key)
            if (windows.isNotEmpty())
                put("readiness", if (blockers.isEmpty()) "ready" else "blocked")
            for (w in windows) {
                val id = key(w)
                var s = window(id) ?: state(w, false)
                val meta = MosqueReportStore.metadata()
                val previous = s.getJSONObject("meta")
                if (
                    listOf(
                            "channel",
                            "build",
                            "catalogue",
                            "detector",
                            "android_api_level",
                            "manufacturer",
                            "device_model",
                        )
                        .any { previous.opt(it) != meta.opt(it) }
                ) {
                    val oldCounts = counts(s)
                    val untouched =
                        oldCounts.length() == 1 &&
                            (oldCounts.has("expected") || oldCounts.has("joined_in_progress"))
                    if (!untouched) emit(s, "partial", oldCounts, now)
                    s = state(w, untouched && oldCounts.has("expected"))
                }
                if (grace != null) {
                    s.put("end", grace)
                    counts(s).put("grace", 1)
                }
                if (canStart) counts(s).increment("wake")
                blockers.forEach { counts(s).put("block_" + it.removePrefix("mosque_"), 1) }
                save(id, s)
            }
        }
    }

    fun hold(c: Context, confirmed: Boolean, continuation: Long?) =
        event(c) {
            change { s ->
                val nominal = s.getLong("nominal")
                s.put(
                    "end",
                    continuation
                        ?: if (confirmed) nominal + MosquePeriods.CONTINUATION_MILLIS else nominal,
                )
            }
        }

    fun finish(c: Context, now: Long) {
        MosqueWalkSharing.finish(c, now)
        event(c) { finalizeDue(now) }
    }

    fun dismiss(c: Context, ids: Collection<String>) {
        val selected = ids.toList()
        event(c) {
            selected.forEach { id ->
                window(id)?.let {
                    counts(it).put("skipped", 1)
                    if (counts(it).has("expected")) counts(it).put("skipped_expected", 1)
                    save(id, it)
                }
            }
        }
    }

    private fun MosqueReportStore.change(ids: List<String> = active, action: (JSONObject) -> Unit) {
        ids.distinct().forEach { id ->
            window(id)?.let {
                action(it)
                save(id, it)
            }
        }
    }

    fun started(c: Context, now: Long) =
        event(c) {
            change { s ->
                if (!s.has("started")) {
                    s.put("started", now)
                    counts(s)
                        .put("started", 1)
                        .put("start_" + delayBand((now - s.getLong("watch")).coerceAtLeast(0)), 1)
                }
            }
        }

    fun fix(c: Context, used: Boolean, accuracy: Double?, age: Long, evidence: Boolean, now: Long) =
        event(c) {
            change { s ->
                val bucket =
                    when {
                        accuracy == null || !accuracy.isFinite() -> "unknown"
                        accuracy <= 10 -> "le10"
                        accuracy <= 30 -> "le30"
                        else -> "gt30"
                    }
                val n = counts(s)
                n.increment("accuracy_" + bucket)
                s.put("accuracy", bucket)
                if (!used) n.increment(if (age !in 0..30_000) "fix_stale" else "fix_rejected")
                if (used) {
                    n.increment("usable_fix")
                    if (!s.has("firstFix")) {
                        s.put("firstFix", now)
                        n.put("first_fix_" + delayBand(now - s.optLong("started", now)), 1)
                    }
                    if (evidence && !s.has("evidence")) s.put("evidence", now)
                }
            }
        }

    fun decision(c: Context, engine: AdaptiveObservation, now: Long) {
        val arrival = engine.detector.arrival?.name?.lowercase()
        // The v1 aggregate uses stable outcome buckets. The walk report retains the precise reason.
        val departure =
            when (engine.detector.departureOnLastFix) {
                Departure.CLEAR_OUTSIDE, Departure.ENTRANCE_RETURN -> "clear_outside"
                Departure.SUSTAINED_OUTSIDE -> "sustained_outside"
                Departure.EXTERIOR_PATH -> "exterior_path"
                null -> null
            }
        val provisional = engine.detector.tentativeUntil != null
        val fallback = engine.fallback
        val hardLoss = engine.isUnavailable
        val near = engine.approachingOnLastFix
        val sampling = engine.sampling.name
        val elapsed = SystemClock.elapsedRealtime()
        event(c) {
            change { s ->
                val n = counts(s)
                if (near) n.put("approach", 1)
                if (provisional && !s.optBoolean("tentative")) n.increment("provisional")
                if (!provisional && s.optBoolean("tentative") && arrival == null) {
                    n.increment("provisional_expired")
                    n.increment("expired_accuracy_" + s.optString("accuracy", "unknown"))
                }
                s.put("tentative", provisional)
                if (arrival != null && s.optString("arrival") != arrival)
                    n.increment("arrival_" + arrival)
                s.put("arrival", arrival ?: "")
                if (departure != null && departure != s.optString("departure"))
                    n.increment("exit_" + departure)
                if (departure != null && departure != s.optString("departure"))
                    s.put("exitTime", now)
                s.put("departure", departure ?: "")
                if (fallback && !s.optBoolean("fallback"))
                    n.increment(if (hardLoss) "fallback_unavailable" else "fallback_no_fix")
                s.put("fallback", fallback)
                if (s.optString("sampling") == "PRECISE") {
                    val gap = elapsed - s.optLong("sampleClock", elapsed)
                    if (gap in 0..120_000) s.put("preciseMs", s.optLong("preciseMs") + gap)
                }
                s.put("sampling", sampling).put("sampleClock", elapsed)
            }
        }
    }

    fun surveyCount(c: Context, reason: String) =
        event(c) {
            require(reason in setOf("survey_shown", "survey_skipped", "survey_answered"))
            queue(
                "metrics",
                MosqueReportStore.metadata()
                    .put("kind", "setup")
                    .put("prayer", "APP")
                    .put("day", java.time.LocalDate.now().toString())
                    .put("counts", JSONObject().put(reason, 1)),
                System.currentTimeMillis(),
            )
        }

    fun flag(c: Context, reason: String) = event(c) { change { s -> counts(s).put(reason, 1) } }

    fun fault(c: Context, reason: String, now: Long = System.currentTimeMillis()) =
        event(c) {
            val ids = active.ifEmpty { ownerKeys() }
            if (ids.isNotEmpty()) writeOutcome(ids, reason, true, now)
        }

    private fun MosqueReportStore.ownerKeys(): List<String> =
        value("owner")?.let(::JSONArray)?.let { a -> List(a.length(), a::getString) }.orEmpty()

    fun rule(c: Context, desired: Boolean, success: Boolean, now: Long, cleanup: Boolean = false) {
        val action = if (desired) "activation" else "release"
        MosqueWalkSharing.event(c, now, if (success) action else action + "_failed")
        val elapsed = SystemClock.elapsedRealtime()
        if (success)
            executor.execute { runCatching { MosqueFieldTrial.changed(c, desired, elapsed) } }
        recordRule(c, desired, success, now, cleanup)
    }

    private fun recordRule(
        c: Context,
        desired: Boolean,
        success: Boolean,
        now: Long,
        cleanup: Boolean,
    ) =
        event(c) {
            val owner = ownerKeys()
            val ids = if (desired) active else owner.ifEmpty { active }
            if (ids.isEmpty()) return@event
            val reason =
                (if (desired) "activation" else "release") + if (success) "_ok" else "_failed"
            if (desired || !success) put("owner", JSONArray(ids).toString())
            writeOutcome(ids, reason, !success, now)
            if (!desired && success) {
                if (cleanup) writeOutcome(ids, "cleanup_ok", false, now)
                put("owner", "[]")
            }
        }

    private fun MosqueReportStore.writeOutcome(
        ids: List<String>,
        reason: String,
        urgent: Boolean,
        now: Long,
    ) {
        val states = ids.mapNotNull { id -> window(id)?.let { id to it } }
        if (states.isEmpty()) return
        val (firstId, first) = states.first()
        val shared = states.size > 1
        val id = if (shared) "shared:" + states.joinToString("+") { it.first } else firstId
        val s =
            if (!shared) first
            else
                window(id)
                    ?: JSONObject(first.toString())
                        .put("prayer", "SHARED")
                        .put("counts", JSONObject())
                        .put("end", states.maxOf { it.second.getLong("end") })
        val n = counts(s)
        n.increment(reason)
        if (reason == "activation_ok" && s.has("evidence")) {
            val band =
                delayBand((now - maxOf(s.getLong("start"), s.getLong("evidence"))).coerceAtLeast(0))
            n.increment("entry_delay_" + band)
            if (band == "ge60")
                n.increment("slow_" + s.optString("arrival").ifBlank { "provisional" })
        }
        if (reason == "release_ok" && s.has("exitTime"))
            n.increment("exit_delay_" + delayBand(now - s.getLong("exitTime")))
        if (reason == "release_failed" || reason == "release_ok") {
            val cause =
                when {
                    n.has("skipped") -> "user"
                    s.has("exitTime") -> "departure"
                    n.has("grace") &&
                        now >= s.getLong("nominal") + MosquePeriods.CONTINUATION_MILLIS -> "grace"
                    now >= s.getLong("nominal") -> "nominal"
                    n.has("interrupted") -> "interrupted"
                    n.has("pause_android_auto") || n.has("pause_awake_check") -> "pause"
                    s.optString("arrival").isEmpty() && s.has("evidence") -> "expiry"
                    else -> "unknown"
                }
            n.put("end_" + cause, 1)
            if (
                reason == "release_ok" && now > s.getLong("end") + 5_000 && !s.optBoolean("overdue")
            ) {
                s.put("overdue", true)
                n.put("release_overdue", 1).put("overdue_" + cause, 1)
                emit(s, "fault", JSONObject().put("release_overdue", 1), now)
            }
            if (reason == "release_ok") s.put("end", maxOf(s.getLong("nominal"), now))
        }
        if (reason == "release_failed") {
            s.put("pending", true)
            if (!s.has("releaseDue")) s.put("releaseDue", now)
        }
        if (
            reason == "release_ok" &&
                s.optBoolean("pending") &&
                !s.optBoolean("overdue") &&
                now - s.optLong("releaseDue", now) >= 5_000L
        ) {
            s.put("overdue", true)
            n.put("release_overdue", 1)
            emit(s, "fault", JSONObject().put("release_overdue", 1), now)
        }
        if (reason == "release_ok") s.put("pending", false)
        if (urgent && n.getInt(reason) == 1) emit(s, "fault", JSONObject().put(reason, 1), now)
        save(id, s)
    }

    private fun MosqueReportStore.emit(
        s: JSONObject,
        kind: String,
        counters: JSONObject,
        now: Long,
    ) {
        val body =
            JSONObject(s.getJSONObject("meta").toString())
                .put("kind", kind)
                .put("prayer", s.getString("prayer"))
                .put("day", s.getString("day"))
                .put("counts", counters)
        queue("metrics", body, now)
    }

    private fun MosqueReportStore.finalizeDue(now: Long) {
        for (key in ownerKeys()) {
            val s = window(key) ?: continue
            if (
                s.optBoolean("pending") &&
                    !s.optBoolean("overdue") &&
                    now - s.optLong("releaseDue", now) >= 5_000L
            ) {
                s.put("overdue", true)
                counts(s).put("release_overdue", 1)
                emit(s, "fault", JSONObject().put("release_overdue", 1), now)
                save(key, s)
            }
        }
        for (key in keys("SELECT key FROM windows WHERE end_ms<=?", now.toString())) {
            val s = window(key) ?: continue
            val n = counts(s)
            if (s.optBoolean("pending") && !s.optBoolean("overdue")) {
                s.put("overdue", true)
                n.put("release_overdue", 1)
                emit(s, "fault", JSONObject().put("release_overdue", 1), now)
            }
            if (!s.optBoolean("final")) {
                if (n.has("expected") && !n.has("started") && !n.has("skipped"))
                    n.put("missed_start", 1)
                if (s.getString("prayer") != "SHARED" && !n.has("skipped")) {
                    val outcome = when {
                        n.keys().asSequence().any { it.startsWith("arrival_") } -> "confirmed_window"
                        n.has("approach") -> "approach_unconfirmed"
                        n.has("usable_fix") -> "no_approach"
                        n.has("started") -> "no_usable_fix"
                        else -> "observation_unknown"
                    }
                    n.put(outcome, 1)
                }
                if (n.has("activation_ok") && !n.has("android_status_4")) n.put("activation_unverified", 1)
                if (n.has("release_ok") && !n.has("android_status_5")) n.put("release_unverified", 1)
                n.put("precise_seconds", (s.optLong("preciseMs") / 1000).coerceAtMost(1_000_000))
                emit(s, "summary", n, now)
                s.put("final", true)
                if (s.getString("prayer") != "SHARED") {
                    put(
                        "latest",
                        JSONObject()
                            .put("prayer", s.getString("prayer"))
                            .put("counts", n)
                            .put("end", s.getLong("end"))
                            .toString(),
                    )
                }
            } else if (!s.optBoolean("pending") && s.optBoolean("overdue") && n.has("cleanup_ok")) {
                emit(s, "recovery", JSONObject().put("cleanup_ok", 1), now)
            }
            if (s.optBoolean("pending") && now - s.getLong("end") < 14 * DAY) save(key, s)
            else db.execSQL("DELETE FROM windows WHERE key=?", arrayOf(key))
        }
    }
}
