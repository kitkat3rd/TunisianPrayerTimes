package com.tunisianprayertimes.mosque

import com.google.android.gms.location.GeofencingEvent
import com.tunisianprayertimes.mosque.presence.AdaptiveObservation
import com.tunisianprayertimes.mosque.presence.PresenceFix

/** Optional observer of decisions already made by the mosque monitor. */
internal interface MosqueObservationDiagnostics {
    val sessionId: Long
    fun stopped(barrier: Boolean)
    fun skipped(before: MosquePeriods, chosen: Set<String>)
    fun period(periods: MosquePeriods, seed: Boolean = false)
    fun failedStart()
    fun unavailable(issues: List<Int>)
    fun blocked(startFailed: Boolean)
    fun engine(engine: AdaptiveObservation, areas: List<SavedSilenceArea>, fix: PresenceFix? = null)
    fun fix(fix: PresenceFix, time: Long, used: Boolean, distance: Double): Boolean
    fun geofence(event: GeofencingEvent)
    fun prayerAlarm(prayerName: String, start: Boolean)
}
