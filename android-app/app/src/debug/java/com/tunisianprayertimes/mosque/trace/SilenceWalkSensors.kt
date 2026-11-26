package com.tunisianprayertimes.mosque.trace

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.tunisianprayertimes.R
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Additional route and sound observations while the mosque location service is running. */
internal class SilenceWalkSensors private constructor(private val context: Context, private val id: Long) {
    companion object {
        private val main = Handler(Looper.getMainLooper())
        private var current: SilenceWalkSensors? = null

        private fun onMain(action: () -> Unit) {
            if (Looper.myLooper() == main.looper) action() else main.post { action() }
        }

        fun attach(context: Context, id: Long) = onMain {
            val app = context.applicationContext
            if (SilenceTraceStore.activeId(app) != id) return@onMain
            if (current?.id == id && current?.context === app) return@onMain
            current?.close()
            val sensors = SilenceWalkSensors(app, id)
            current = sensors
            sensors.start()
        }

        fun detach(context: Context, id: Long) = onMain {
            val sensors = current ?: return@onMain
            if (sensors.id != id || sensors.context !== context.applicationContext) return@onMain
            if (sensors.isCurrent()) sensors.captureSound()
            current = null
            sensors.close()
        }
    }

    private val locations = context.getSystemService(LocationManager::class.java)
    private var activeProvider: String? = null
    private var listener: LocationListener? = null
    private var locationStatus: String? = null
    private var lastSoundState: Pair<Int, Int>? = null
    private var watching = false

    private fun isCurrent() = current === this && SilenceTraceStore.activeId(context) == id

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isCurrent()) return
            when (intent.action) {
                LocationManager.PROVIDERS_CHANGED_ACTION -> updateProvider()
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                    val time = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    SilenceTraceStore.append(context, "device_clock", context.getString(R.string.silence_walk_clock_changed, time), sessionId = id)
                }
                else -> captureSound()
            }
        }
    }

    private fun start() {
        try {
            ContextCompat.registerReceiver(context, changes, IntentFilter().apply {
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            watching = true
        } catch (_: RuntimeException) {
            reportError(R.string.silence_walk_sensor_failed)
        }
        captureSound()
        updateProvider()
    }

    @SuppressLint("MissingPermission")
    private fun updateProvider() {
        if (!isCurrent()) return
        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!fine && !hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            removeLocationListener()
            reportLocationStatus(context.getString(R.string.silence_walk_permission_lost), "recorder_error")
            return
        }
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            if (fine) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { locations.isProviderEnabled(it) }.getOrDefault(false) }
        for (provider in candidates) {
            if (activeProvider == provider && listener != null) return
            val subscribedAt = SystemClock.elapsedRealtimeNanos()
            val next = object : LocationListener {
                override fun onLocationChanged(point: Location) {
                    if (listener !== this || !isCurrent() || point.elapsedRealtimeNanos < subscribedAt) return
                    if (point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0) return
                    captureSound()
                    val saved = SilenceTraceStore.append(context, "route", provider,
                        at = point.time, latitude = point.latitude, longitude = point.longitude,
                        accuracyMeters = point.accuracy.toDouble().takeIf { point.hasAccuracy() }, sessionId = id,
                        elapsedMillis = point.elapsedRealtimeNanos / 1_000_000L)
                    if (!saved) reportError(R.string.silence_walk_write_failed)
                }

                override fun onProviderDisabled(provider: String) {
                    if (listener === this) updateProvider()
                }
                override fun onProviderEnabled(provider: String) {
                    if (listener === this) updateProvider()
                }
                @Deprecated("Required by older Android versions")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            try {
                if (Build.VERSION.SDK_INT >= 31 && provider == LocationManager.FUSED_PROVIDER) {
                    val request = LocationRequest.Builder(5_000L)
                        .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build()
                    locations.requestLocationUpdates(provider, request, context.mainExecutor, next)
                } else {
                    locations.requestLocationUpdates(provider, 5_000L, 0f, next, Looper.getMainLooper())
                }
            } catch (_: RuntimeException) {
                continue
            }
            removeLocationListener()
            listener = next
            activeProvider = provider
            reportLocationStatus(context.getString(R.string.silence_walk_source, provider))
            return
        }
        removeLocationListener()
        if (candidates.isEmpty()) reportLocationStatus(context.getString(R.string.silence_walk_wait_provider))
        else reportLocationStatus(context.getString(R.string.silence_walk_provider_failed), "recorder_error")
    }

    private fun reportLocationStatus(status: String, kind: String = "route_provider") {
        if (status != locationStatus && SilenceTraceStore.append(context, kind, status, sessionId = id)) {
            locationStatus = status
        }
    }

    private fun reportError(reason: Int) {
        SilenceTraceStore.append(context, "recorder_error", context.getString(reason), sessionId = id)
    }

    private fun captureSound() {
        val state = try {
            context.getSystemService(AudioManager::class.java).ringerMode to
                context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        } catch (_: RuntimeException) {
            reportError(R.string.silence_walk_sensor_failed)
            return
        }
        if (state == lastSoundState) return
        val ringLabel = when (state.first) {
            AudioManager.RINGER_MODE_NORMAL -> R.string.silence_walk_ringing
            AudioManager.RINGER_MODE_VIBRATE -> R.string.silence_walk_vibrating
            AudioManager.RINGER_MODE_SILENT -> R.string.silence_walk_silent
            else -> R.string.silence_walk_unknown
        }
        val filterLabel = when (state.second) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> R.string.silence_walk_filter_off
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> R.string.silence_walk_filter_priority
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> R.string.silence_walk_filter_alarms
            NotificationManager.INTERRUPTION_FILTER_NONE -> R.string.silence_walk_filter_none
            else -> R.string.silence_walk_unknown
        }
        val detail = context.getString(R.string.silence_walk_device_audio, context.getString(ringLabel), context.getString(filterLabel))
        if (SilenceTraceStore.append(context, "device_audio", detail, sessionId = id)) lastSoundState = state
    }

    private fun removeLocationListener() {
        val previous = listener
        listener = null
        activeProvider = null
        if (previous != null) runCatching { locations.removeUpdates(previous) }
    }

    private fun close() {
        removeLocationListener()
        if (watching) {
            watching = false
            runCatching { context.unregisterReceiver(changes) }
        }
    }
}
