package com.tunisianprayertimes.mosque

import android.content.Context
import android.content.SharedPreferences
import com.tunisianprayertimes.Prayer
import org.json.JSONObject
import java.time.LocalDate

internal data class MosqueObservationInterruption(
    val id: String,
    val prayer: Prayer?,
    val prayerDay: LocalDate?,
    val confirmedVisit: Boolean,
)

/** Local-only journal alongside the existing mosque recovery state, excluded from Android backup. */
internal object MosqueObservationInterruptions {
    private const val ACTIVE = "active_location_observation"
    private const val PENDING = "interrupted_location_observation"
    private fun prefs(context: Context) = context.getSharedPreferences("mosque_presence", Context.MODE_PRIVATE)

    // The monitor serializes transitions and calls this only when the actual observation changes.
    fun observing(context: Context, observation: MosqueObservationInterruption) {
        val record = JSONObject().put("id", observation.id)
            .put("prayer", observation.prayer?.name)
            .put("day", observation.prayerDay?.toString())
            .put("confirmed", observation.confirmedVisit)
        prefs(context).edit().putString(ACTIVE, record.toString()).commit()
    }

    fun finished(context: Context) {
        prefs(context).edit().remove(ACTIVE).commit()
    }

    fun interrupted(context: Context) {
        val state = prefs(context)
        val record = state.getString(ACTIVE, null) ?: return
        state.edit().putString(PENDING, record).remove(ACTIVE).commit()
    }

    fun pending(context: Context): MosqueObservationInterruption? {
        val raw = prefs(context).getString(PENDING, null) ?: return null
        return runCatching {
            val record = JSONObject(raw)
            MosqueObservationInterruption(
                record.getString("id"),
                Prayer.entries.firstOrNull { it.name == record.optString("prayer") },
                record.optString("day").takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
                record.getBoolean("confirmed"),
            )
        }.getOrNull()
    }

    fun acknowledge(context: Context, id: String) = synchronized(SilenceCoordinator.serial) {
        if (pending(context)?.id == id) prefs(context).edit().remove(PENDING).commit()
        Unit
    }

    fun observe(context: Context, onChange: () -> Unit): () -> Unit {
        val state = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PENDING || key == null) onChange()
        }
        state.registerOnSharedPreferenceChangeListener(listener)
        return { state.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
