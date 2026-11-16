package com.tunisianprayertimes.wake

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

internal fun WakeTriggerPayload.motivationOccurrenceKey(): String =
    "ring|$eventId|$triggerAtMillis|$occurrenceAtMillis"

/** Shared by rings and awake checks, and kept on disk so reopening an alarm does not reroll it. */
internal object WakeMotivationHistory {
    private const val PREFS = "wake_motivation_history"
    private const val SELECTIONS = "selections"
    // More than the full catalogue, but bounded even for frequent timers and recurring alarms.
    private const val MAX_OCCURRENCES = 96

    @Synchronized
    fun select(
        context: Context,
        occurrenceKey: String,
        topics: Set<WakeMotivationTopic>,
        alarmId: String,
        epochDay: Long,
    ): WakeMotivation {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val history = runCatching {
                val saved = JSONArray(prefs.getString(SELECTIONS, "[]"))
                (0 until saved.length()).map { index ->
                    val entry = saved.getJSONObject(index)
                    entry.getString("occurrence") to entry.getString("message")
                }
            }.getOrDefault(emptyList())
            val savedId = history.lastOrNull { it.first == occurrenceKey }?.second
            WakeMotivations.all.firstOrNull { it.reviewId == savedId }?.let { return it }

            val selected = WakeMotivations.select(topics, alarmId, epochDay, history.map { it.second })
            val updated = JSONArray()
            (history + (occurrenceKey to selected.reviewId)).takeLast(MAX_OCCURRENCES).forEach { (key, id) ->
                updated.put(JSONObject().put("occurrence", key).put("message", id))
            }
            prefs.edit().putString(SELECTIONS, updated.toString()).apply()
            return selected
        } catch (error: Exception) {
            // A preferences failure must never interfere with ringing or dismissal.
            Log.w("WakeMotivation", "Message history is unavailable", error)
            return WakeMotivations.select(topics, alarmId, epochDay)
        }
    }
}
