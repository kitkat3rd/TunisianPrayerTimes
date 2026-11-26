package com.tunisianprayertimes

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import com.tunisianprayertimes.place.PrayerPlace

/** Measures the original launcher task. A new task cannot continue an abandoned first run. */
internal class FirstRunTimetableSession private constructor(
    private val prefs: SharedPreferences,
    private val startedAt: Long,
    private val collectionAllowed: () -> Boolean,
    private val elapsedRealtime: () -> Long,
    private val record: (event: String, elapsedBucket: String?) -> Unit,
) {
    fun save(outState: Bundle) = outState.putLong(STATE_STARTED_AT, startedAt)

    /** [displayedPlace] is the input to the timetable actually on screen, including during place changes. */
    fun timetableShown(context: Context, displayedPlace: PrayerPlace, confirmed: Boolean, hasPrayerTime: Boolean) {
        if (!hasPrayerTime || displayedPlace != PrefsManager.getPlace(context)) return
        if (PrefsManager.isFirstLaunch(context) || confirmed != PrefsManager.hasConfirmedPlace(context)) return
        synchronized(prefs) {
            val stage = prefs.getInt(KEY_STAGE, UNSEEN)
            if (stage != STARTED && stage != SUGGESTION_SEEN) return
            if (!collectionAllowed()) {
                prefs.edit().putInt(KEY_STAGE, CLOSED).apply()
                return
            }
            if (!confirmed && stage == SUGGESTION_SEEN) return
            val elapsed = elapsedRealtime() - startedAt
            if (elapsed < 0) return
            val nextStage = if (confirmed) CLOSED else SUGGESTION_SEEN
            prefs.edit().putInt(KEY_STAGE, nextStage).apply()
            record(if (confirmed) "first_confirmed_timetable" else "first_suggested_timetable", elapsedBucket(elapsed))
        }
    }

    companion object {
        private const val STATE_STARTED_AT = "com.tunisianprayertimes.state.FIRST_RUN_STARTED_AT"
        private const val KEY_STAGE = "first_run_timetable_stage"
        private const val UNSEEN = 0
        private const val STARTED = 1
        private const val SUGGESTION_SEEN = 2
        private const val CLOSED = 3

        fun open(
            prefs: SharedPreferences,
            freshOrdinaryLaunch: Boolean,
            savedInstanceState: Bundle?,
            collectionAllowed: () -> Boolean,
            elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
            record: (event: String, elapsedBucket: String?) -> Unit,
        ): FirstRunTimetableSession? = synchronized(prefs) {
            val stage = prefs.getInt(KEY_STAGE, UNSEEN)
            val start: Long
            if (savedInstanceState != null) {
                if (stage != STARTED && stage != SUGGESTION_SEEN) return@synchronized null
                if (!savedInstanceState.containsKey(STATE_STARTED_AT)) return@synchronized null
                start = savedInstanceState.getLong(STATE_STARTED_AT)
            } else {
                if (!freshOrdinaryLaunch || stage != UNSEEN) return@synchronized null
                start = elapsedRealtime()
            }
            if (!collectionAllowed()) {
                prefs.edit().putInt(KEY_STAGE, CLOSED).apply()
                return@synchronized null
            }
            if (stage == UNSEEN) {
                prefs.edit().putInt(KEY_STAGE, STARTED).apply()
                record("first_run_started", null)
            }
            FirstRunTimetableSession(prefs, start, collectionAllowed, elapsedRealtime, record)
        }

        private fun elapsedBucket(millis: Long): String = when {
            millis < 10_000 -> "0_9s"
            millis < 30_000 -> "10_29s"
            millis < 60_000 -> "30_59s"
            millis < 120_000 -> "60_119s"
            millis < 300_000 -> "120_299s"
            else -> "300s_plus"
        }
    }
}
