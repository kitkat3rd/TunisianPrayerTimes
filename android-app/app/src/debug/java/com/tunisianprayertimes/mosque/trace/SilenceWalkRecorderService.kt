package com.tunisianprayertimes.mosque.trace

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteException
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.SilenceWalkActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Debug walk recorder. Its route fixes are only written to the current test walk. */
class SilenceWalkRecorderService : Service() {
    companion object {
        private const val START = "walk.start"
        private const val STOP = "walk.stop"
        private const val SESSION = "walk_session"
        private const val PREFS = "debug_silence_walk"
        private const val PAUSED = "paused_by_user"
        internal const val CHANNEL = "silence_walk_recorder"
        private const val NOTIFICATION = 7_251

        fun hasLocationPermission(context: Context): Boolean =
            hasFineLocation(context) || ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

        private fun hasFineLocation(context: Context) = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        private fun command(context: Context, action: String, id: Long) =
            Intent(context, SilenceWalkRecorderService::class.java).setAction(action)
                .setData(Uri.parse("silence-walk://recordings/$id")).putExtra(SESSION, id)

        private fun markPaused(context: Context) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PAUSED, true).commit()
        }

        /** The user explicitly chose Start, including after previously pausing automatic capture. */
        fun start(context: Context): Boolean {
            if (!startRecording(context)) return false
            val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            if (prefs.contains(PAUSED)) prefs.edit().remove(PAUSED).commit()
            return true
        }

        internal fun startAutomatically(context: Context) {
            if (context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PAUSED, false)) return
            startRecording(context)
        }

        private fun startRecording(context: Context): Boolean {
            if (!hasLocationPermission(context)) return false
            if (SilenceTraceStore.activeId(context) != null) return true
            val id = try {
                SilenceTraceStore.start(context)
            } catch (_: SQLiteException) {
                return false
            }
            return try {
                context.startForegroundService(command(context, START, id))
                true
            } catch (_: RuntimeException) {
                endWithError(context, id, R.string.silence_walk_start_failed)
                false
            }
        }

        fun stop(context: Context, sessionId: Long) {
            if (SilenceTraceStore.activeId(context) == sessionId) {
                markPaused(context)
                context.startService(command(context, STOP, sessionId))
            }
        }

        private fun endWithError(context: Context, id: Long, reason: Int) {
            SilenceTraceStore.append(context, "recorder_error", context.getString(reason), sessionId = id)
            SilenceTraceStore.stop(context, id)
        }
    }

    private val locations by lazy { getSystemService(LocationManager::class.java) }
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionId: Long? = null
    private var activeProvider: String? = null
    private var listener: LocationListener? = null
    private var locationStatus: String? = null
    private var lastSoundState: Pair<Int, Int>? = null
    private var watching = false

    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                LocationManager.PROVIDERS_CHANGED_ACTION -> updateProvider()
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> sessionId?.let {
                    val time = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    SilenceTraceStore.append(context, "device_clock", getString(R.string.silence_walk_clock_changed, time), sessionId = it)
                }
                else -> captureSound()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(SESSION, -1L) ?: -1L
        val active = SilenceTraceStore.activeId(this)
        if (active == null || active != id) {
            if (sessionId == null && active == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == STOP) {
            // The notification Stop action arrives here directly.
            markPaused(this)
            captureSound()
            SilenceTraceStore.stop(this, id)
            clearSession()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != START || sessionId == id) return START_NOT_STICKY

        clearSession()
        sessionId = id
        try {
            promote(id)
        } catch (_: RuntimeException) {
            fail(R.string.silence_walk_start_failed)
            return START_NOT_STICKY
        }
        if (!watching) {
            ContextCompat.registerReceiver(this, changes, IntentFilter().apply {
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            watching = true
        }
        captureSound()
        if (sessionId == null) return START_NOT_STICKY
        background.launch { SilenceWalkBaseline.capture(this@SilenceWalkRecorderService, id) }
        updateProvider()
        return START_NOT_STICKY
    }

    private fun promote(id: Long) {
        val channel = NotificationChannel(CHANNEL, getString(R.string.silence_walk_channel), NotificationManager.IMPORTANCE_LOW)
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, NOTIFICATION, Intent(this, SilenceWalkActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), flags)
        val stop = PendingIntent.getService(this, NOTIFICATION, command(this, STOP, id), flags)
        val notice = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.mosque_silhouette)
            .setContentTitle(getString(R.string.silence_walk_notification))
            .setContentText(getString(R.string.silence_walk_notification_detail))
            .setContentIntent(open)
            .addAction(0, getString(R.string.silence_walk_notification_stop), stop)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIFICATION, notice)
    }

    @SuppressLint("MissingPermission")
    private fun updateProvider() {
        val id = sessionId ?: return
        if (!hasLocationPermission(this)) {
            fail(R.string.silence_walk_permission_lost)
            return
        }
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            if (hasFineLocation(this@SilenceWalkRecorderService)) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { locations.isProviderEnabled(it) }.getOrDefault(false) }
        for (provider in candidates) {
            if (activeProvider == provider && listener != null) return
            val subscribedAt = SystemClock.elapsedRealtimeNanos()
            val next = object : LocationListener {
                override fun onLocationChanged(point: Location) {
                    if (listener !== this || sessionId != id || point.elapsedRealtimeNanos < subscribedAt) return
                    if (point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0) return
                    captureSound()
                    if (sessionId == null) return
                    val saved = SilenceTraceStore.append(this@SilenceWalkRecorderService, "route", provider,
                        at = point.time, latitude = point.latitude, longitude = point.longitude,
                        accuracyMeters = point.accuracy.toDouble().takeIf { point.hasAccuracy() }, sessionId = id,
                        elapsedMillis = point.elapsedRealtimeNanos / 1_000_000L)
                    if (!saved) fail(R.string.silence_walk_write_failed)
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
                    locations.requestLocationUpdates(provider, request, mainExecutor, next)
                } else {
                    locations.requestLocationUpdates(provider, 5_000L, 0f, next, Looper.getMainLooper())
                }
            } catch (_: RuntimeException) {
                continue
            }
            removeLocationListener()
            listener = next
            activeProvider = provider
            reportLocationStatus(id, getString(R.string.silence_walk_source, provider))
            return
        }
        removeLocationListener()
        when {
            !hasLocationPermission(this) -> fail(R.string.silence_walk_permission_lost)
            candidates.isEmpty() -> reportLocationStatus(id, getString(R.string.silence_walk_wait_provider))
            else -> fail(R.string.silence_walk_provider_failed)
        }
    }

    private fun reportLocationStatus(id: Long, status: String) {
        if (status != locationStatus) {
            locationStatus = status
            if (!SilenceTraceStore.append(this, "route_provider", status, sessionId = id))
                fail(R.string.silence_walk_write_failed)
        }
    }

    private fun captureSound() {
        val id = sessionId ?: return
        val ringer = getSystemService(AudioManager::class.java).ringerMode
        val filter = getSystemService(NotificationManager::class.java).currentInterruptionFilter
        val state = ringer to filter
        if (state == lastSoundState) return
        lastSoundState = state
        val ringLabel = when (ringer) {
            AudioManager.RINGER_MODE_NORMAL -> R.string.silence_walk_ringing
            AudioManager.RINGER_MODE_VIBRATE -> R.string.silence_walk_vibrating
            AudioManager.RINGER_MODE_SILENT -> R.string.silence_walk_silent
            else -> R.string.silence_walk_unknown
        }
        val filterLabel = when (filter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> R.string.silence_walk_filter_off
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> R.string.silence_walk_filter_priority
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> R.string.silence_walk_filter_alarms
            NotificationManager.INTERRUPTION_FILTER_NONE -> R.string.silence_walk_filter_none
            else -> R.string.silence_walk_unknown
        }
        val detail = getString(R.string.silence_walk_device_audio, getString(ringLabel), getString(filterLabel))
        if (!SilenceTraceStore.append(this, "device_audio", detail, sessionId = id))
            fail(R.string.silence_walk_write_failed)
    }

    private fun removeLocationListener() {
        listener?.let { runCatching { locations.removeUpdates(it) } }
        listener = null
        activeProvider = null
    }

    private fun fail(reason: Int) {
        sessionId?.let { endWithError(this, it, reason) }
        clearSession()
        stopSelf()
    }

    private fun clearSession() {
        sessionId = null
        removeLocationListener()
        locationStatus = null
        lastSoundState = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeLocationListener()
        if (watching) unregisterReceiver(changes)
        background.cancel()
        sessionId?.let { endWithError(this, it, R.string.silence_walk_service_interrupted) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
