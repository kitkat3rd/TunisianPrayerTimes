package com.tunisianprayertimes.mosque

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.presence.PresenceState
import kotlin.math.max

/** A finite, typed location service. Start authority is checked by its exact-alarm/geofence/UI callers. */
class MosqueObservationService : Service() {
    companion object {
        private const val CHANNEL = "mosque_window_observation"
        private const val NOTIFICATION = 7_204
        private const val TOKEN = "observation_token"
        @Volatile private var instance: MosqueObservationService? = null

        fun start(context: Context, token: String) {
            if (Build.VERSION.SDK_INT < 29 || MosqueSilenceMonitor.plan(context, token) == null) return
            try {
                context.startForegroundService(Intent(context, MosqueObservationService::class.java).putExtra(TOKEN, token))
            } catch (_: Exception) {
                // A denied background/while-in-use start is a real loss of readiness, never a reason
                // to run a receiver-side location request or claim confirmation.
                observationFailed(context)
            }
        }
        internal fun observationFailed(context: Context) {
            if (!MosqueSilenceSettings.combined(context)) return
            if (MosqueReadiness.issues(context).any(MosqueReadiness::locationIssue)) {
                MosqueSilenceMonitor.reconcile(context)
            } else {
                MosqueSilenceMonitor.environmentChanged(context, failed = true)
            }
        }
        fun stop(context: Context) {
            // A pending start must get its onCreate/startForeground before it stops itself.
            val service = instance ?: return
            val expected = service.token
            service.handler.post {
                if (service.token == expected && (expected == null || MosqueSilenceMonitor.plan(context, expected) == null)) service.stopSelf()
            }
        }
        internal fun cancelNative(context: Context) {
            val service = instance
            val expected = service?.token
            service?.handler?.post { if (service.token == expected) service.cancelProvider() }
            removeSavedGeofences(context)
        }
        private fun geofenceIntent(context: Context, token: String): PendingIntent = PendingIntent.getBroadcast(
            context, 81_202, Intent(context, MosqueControlReceiver::class.java)
                .setAction(MosqueControlReceiver.GEOFENCE).setData(Uri.parse("mosque-silence://window/$token")),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        internal fun removeSavedGeofences(context: Context, includeCurrent: Boolean = true) {
            val state = context.getSharedPreferences("mosque_presence", MODE_PRIVATE)
            val tokens = state.getStringSet("geofence_cleanup", emptySet()).orEmpty().toMutableSet()
            if (includeCurrent) state.getString("geofence_token", null)?.let { tokens.add(it) }
            val edit = state.edit().putStringSet("geofence_cleanup", tokens.toSet())
            if (includeCurrent) edit.remove("geofence_token")
            edit.commit() // Journal before asynchronous native removal; invalidates incoming transitions.
            tokens.forEach { token ->
                runCatching {
                    LocationServices.getGeofencingClient(context).removeGeofences(geofenceIntent(context, token)).addOnSuccessListener {
                        synchronized(SilenceCoordinator.serial) {
                            val pending = state.getStringSet("geofence_cleanup", emptySet()).orEmpty() - token
                            state.edit().putStringSet("geofence_cleanup", pending).commit()
                        }
                    }
                }
            }
        }
        internal fun validGeofenceToken(context: Context, token: String) =
            context.getSharedPreferences("mosque_presence", MODE_PRIVATE).getString("geofence_token", null) == token
    }

    internal val handler = Handler(Looper.getMainLooper())
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var token: String? = null
    private var callback: LocationCallback? = null
    private var promoted = false
    private var generation = -1L
    private var requestEnd = -1L
    private var highAccuracy: Boolean? = null
    private var geofenceEnd = -1L
    private val tick = object : Runnable {
        override fun run() {
            val current = token ?: return
            val plan = MosqueSilenceMonitor.plan(this@MosqueObservationService, current)
            if (plan == null) {
                cancelProvider()
                // Circles remain only until this currently eligible window ends. They may start a
                // bounded new episode on a later qualifying event; no timer reacquisition loop.
                stopSelf()
                return
            }
            updateProvider(plan)
            registerGeofences(plan)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(plan.state))
            handler.postDelayed(this, (plan.deadlineElapsed - SystemClock.elapsedRealtime()).coerceIn(1L, 30_000L))
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val channel = NotificationChannel(CHANNEL, getString(R.string.mosque_channel), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION, notification(null))
            promoted = true
        } catch (_: RuntimeException) {
            observationFailed(this)
            stopSelf()
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val incoming = intent?.getStringExtra(TOKEN)
        if (!promoted || incoming == null || MosqueSilenceMonitor.plan(this, incoming) == null) {
            if (token?.let { MosqueSilenceMonitor.plan(this, it) } == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (token != incoming) { cancelProvider(); geofenceEnd = -1; token = incoming }
        handler.removeCallbacks(tick)
        tick.run()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        handler.removeCallbacks(tick)
        cancelProvider()
        if (instance === this) {
            instance = null
            AdaptiveMosqueMonitor.serviceGone(this, token)
        }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun updateProvider(plan: MosqueObservationPlan) {
        synchronized(SilenceCoordinator.serial) {
            if (callback != null && highAccuracy == plan.highAccuracy && generation == plan.generation && kotlin.math.abs(requestEnd - plan.providerEnd) < 1_000) return
            cancelProvider()
            // Do not issue any native call on an obsolete plan after a mode/window/settings change.
            val checked = MosqueSilenceMonitor.plan(this, plan.token) ?: return
            val remaining = checked.providerEnd - SystemClock.elapsedRealtime()
            if (remaining <= 0) return
            val expected = checked.token
            val listener = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    if (callback !== this) return
                    result.locations.forEach { MosqueSilenceMonitor.onLocation(this@MosqueObservationService, expected, it, checked.generation) }
                    handler.removeCallbacks(tick); tick.run()
                }
            }
            callback = listener; highAccuracy = checked.highAccuracy; generation = checked.generation; requestEnd = checked.providerEnd
            val interval = if (checked.highAccuracy) 5_000L else 60_000L
            val request = LocationRequest.Builder(if (checked.highAccuracy) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY, interval)
                .setMinUpdateIntervalMillis(if (checked.highAccuracy) 2_000L else 30_000L)
                .setMaxUpdateAgeMillis(0).setMaxUpdateDelayMillis(0).setDurationMillis(remaining)
                .setWaitForAccurateLocation(checked.highAccuracy).build()
            try {
                if (MosqueSilenceMonitor.plan(this, expected) == null) { cancelProvider(); return }
                fused.requestLocationUpdates(request, listener, Looper.getMainLooper()).addOnSuccessListener {
                    if (callback !== listener || MosqueSilenceMonitor.plan(this, expected)?.generation != checked.generation) fused.removeLocationUpdates(listener)
                    else synchronized(SilenceCoordinator.serial) { AdaptiveMosqueMonitor.accepted(this, expected, checked.generation) }
                }.addOnFailureListener {
                    if (callback === listener && token == expected) { providerFailed(expected); stopSelf() }
                }
            } catch (_: SecurityException) { providerFailed(expected); stopSelf() }
        }
    }

    private fun providerFailed(expected: String) {
        if (token != expected) return
        observationFailed(this)
    }

    private fun cancelProvider() {
        callback?.let { runCatching { fused.removeLocationUpdates(it) } }
        callback = null; highAccuracy = null; requestEnd = -1
    }

    @SuppressLint("MissingPermission")
    private fun registerGeofences(plan: MosqueObservationPlan) {
        synchronized(SilenceCoordinator.serial) {
            if (kotlin.math.abs(geofenceEnd - plan.endElapsed) < 1_000) return
            val checked = MosqueSilenceMonitor.plan(this, plan.token) ?: return
            val remaining = checked.endElapsed - SystemClock.elapsedRealtime()
            if (remaining <= 0) return
            val circles = checked.areas.flatMap { area -> area.polygons.mapIndexed { index, polygon ->
                val points = polygon.exterior
                val lat = points.map { it.latitude }.average()
                val lon = points.map { it.longitude }.average()
                val distances = FloatArray(1)
                val radius = points.maxOf { point ->
                    android.location.Location.distanceBetween(lat, lon, point.latitude, point.longitude, distances)
                    distances[0].toDouble()
                }
                Geofence.Builder().setRequestId("${area.id}:$index")
                    .setCircularRegion(lat, lon, max(100.0, radius + 30.0).toFloat())
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_DWELL)
                    .setLoiteringDelay(60_000).setNotificationResponsiveness(60_000)
                    .setExpirationDuration(remaining).build()
            } }.take(100)
            if (circles.isEmpty()) return
            geofenceEnd = checked.endElapsed
            val request = GeofencingRequest.Builder().setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER).addGeofences(circles).build()
            val state = getSharedPreferences("mosque_presence", MODE_PRIVATE)
            val old = state.getString("geofence_token", null)
            if (old != null && old != checked.token) removeSavedGeofences(this)
            state.edit().putString("geofence_token", checked.token).commit()
            try {
                if (MosqueSilenceMonitor.plan(this, checked.token) == null) return
                val pending = geofenceIntent(this, checked.token)
                LocationServices.getGeofencingClient(this).addGeofences(request, pending).addOnSuccessListener {
                    // Acquisition may have finished while the registration was in flight. Idle circles
                    // are still useful until this same actual window ends.
                    if (!validGeofenceToken(this, checked.token) || !MosqueSilenceMonitor.canObserve(this) || !MosqueReadiness.ready(this))
                        LocationServices.getGeofencingClient(this).removeGeofences(pending)
                }.addOnFailureListener {
                    if (token == checked.token) { geofenceEnd = -1; providerFailed(checked.token); stopSelf() }
                }
            } catch (_: SecurityException) { providerFailed(checked.token); stopSelf() }
        }
    }

    private fun notification(state: PresenceState?): android.app.Notification {
        val periods = AdaptiveMosqueMonitor.periods(this)
        val target = periods.targets.firstOrNull()
        val actionState = when {
            state == PresenceState.SCHEDULE_FALLBACK -> R.string.mosque_adaptive_fallback
            periods.observation != null && periods.silence == null -> R.string.mosque_adaptive_early
            state == PresenceState.VISIT_CONFIRMED -> R.string.mosque_adaptive_hold
            else -> R.string.mosque_status_checking
        }
        val builder = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.mosque_silhouette).setContentTitle(getString(R.string.mosque_observing_title))
        .setContentText(getString(actionState))
        .setContentIntent(PendingIntent.getActivity(this, 81_203, Intent(this, MosqueSettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

        .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        fun action(code: Int, name: String, title: String, key: String? = null) {
            val intent = Intent(this, MosqueControlReceiver::class.java).setAction(name)
            if (key != null) {
                intent.data = Uri.Builder().scheme("mosque-action").authority("prayer").appendPath(key).build()
                intent.putExtra("occurrence", key).putStringArrayListExtra("occurrences", arrayListOf(key))
            }
            if (name == MosqueControlReceiver.UNDO_MANUAL) intent.putExtra("end",
                PrefsManager.getManualSilenceEndsAtMillis(this))
            builder.addAction(0, title, PendingIntent.getBroadcast(this, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        if (target != null) {
            val key = MosquePrayerActions.key(target)
            action(81_204, MosqueControlReceiver.SKIP, getString(R.string.mosque_adaptive_skip, MosquePrayerActions.name(this, target)), key)
            if (MosquePrayerActions.canUndo(this))
                action(81_206, MosqueControlReceiver.UNDO_MANUAL, getString(R.string.mosque_adaptive_undo))
            else if (periods.silence != null)
                action(81_205, MosqueControlReceiver.MANUAL, getString(R.string.mosque_adaptive_manual, MosquePrayerActions.label(this, target)), key)
        }
        return builder.build()
    }
}
