package com.tunisianprayertimes.mosque.trace

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.tunisianprayertimes.R

/** A single subscription on the observation service's main looper, scoped to one report. */
internal class SilenceWalkSteps(private val context: Context, private val id: Long) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var sensor: Sensor? = null
    private var closed = false
    private var startedNanos = 0L
    private var lastSampleNanos = -1L
    private var lastCounter: Long? = null
    private var unavailableReason: Int? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (closed || SilenceTraceStore.activeId(context) != id) return
        if (!canReadWalkSteps(context)) {
            unsubscribe()
            unavailable(R.string.silence_walk_steps_permission_missing)
            return
        }
        if (sensor != null) return
        var foundSensor = false
        for (type in listOf(Sensor.TYPE_STEP_DETECTOR, Sensor.TYPE_STEP_COUNTER)) {
            // Prefer a wake-up variant if the device provides one, including with the screen off.
            val candidates = listOfNotNull(manager?.getDefaultSensor(type, true), manager?.getDefaultSensor(type))
            for (candidate in candidates.distinct()) {
                foundSensor = true
                startedNanos = SystemClock.elapsedRealtimeNanos()
                val registered = try {
                    manager.registerListener(this, candidate, SensorManager.SENSOR_DELAY_NORMAL, 0)
                } catch (_: RuntimeException) {
                    false
                }
                if (!registered) continue
                sensor = candidate
                lastSampleNanos = -1L
                lastCounter = null
                unavailableReason = null
                if (type == Sensor.TYPE_STEP_DETECTOR) record("step_detector_started", R.string.silence_walk_step_detector, startedNanos)
                else record("step_counter_started", R.string.silence_walk_step_counter, startedNanos)
                return
            }
        }
        unavailable(if (foundSensor) R.string.silence_walk_steps_failed else R.string.silence_walk_steps_missing)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val selected = sensor ?: return
        if (event.sensor != selected || SilenceTraceStore.activeId(context) != id) return
        val at = event.timestamp
        // Android may deliver several older samples together after the phone wakes up.
        if (at < startedNanos || at <= lastSampleNanos || at > SystemClock.elapsedRealtimeNanos()) return
        val raw = event.values.firstOrNull() ?: return
        val delta = if (selected.type == Sensor.TYPE_STEP_DETECTOR) {
            if (raw != 1f) return
            1L
        } else {
            if (!raw.isFinite() || raw < 0 || raw.toLong().toFloat() != raw) return
            val count = raw.toLong()
            val previous = lastCounter
            lastCounter = count
            lastSampleNanos = at
            if (previous == null || count < previous) {
                // This counter is cumulative since boot. Only differences during this subscription belong to the walk.
                val reason = if (previous == null) R.string.silence_walk_steps_baseline else R.string.silence_walk_steps_reset
                record("step_counter_ready", reason, at)
                return
            }
            count - previous
        }
        lastSampleNanos = at
        if (delta > 0) {
            SilenceTraceStore.append(context, "walk_steps", delta.toString(), sessionId = id, elapsedMillis = at / 1_000_000L)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun unavailable(reason: Int) {
        if (unavailableReason != reason) {
            record("step_sensor_unavailable", reason)
            unavailableReason = reason
        }
    }

    private fun record(kind: String, message: Int, atNanos: Long? = null) {
        SilenceTraceStore.append(context, kind, context.getString(message), sessionId = id, elapsedMillis = atNanos?.div(1_000_000L))
    }

    private fun unsubscribe() {
        if (sensor == null) return
        sensor = null
        try {
            manager.unregisterListener(this)
        } catch (_: RuntimeException) {
            // A revoked platform permission may already have removed the subscription.
        }
        record("step_sensor_stopped", R.string.silence_walk_steps_stopped)
    }

    fun close() {
        closed = true
        unsubscribe()
    }
}
