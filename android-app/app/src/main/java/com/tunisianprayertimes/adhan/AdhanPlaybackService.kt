package com.tunisianprayertimes.adhan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tunisianprayertimes.MainActivityPendingIntents
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceModeController
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.adhan.AdhanDecision.Outcome
import com.tunisianprayertimes.adhan.AdhanScheduler.occurrence
import com.tunisianprayertimes.adhan.AdhanScheduler.putOccurrence
import com.tunisianprayertimes.mosque.MosqueSilenceSettings
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays one adhan. Started by its alarm, it goes to the foreground at once, then decides off the main thread whether
 * the adhan sounds ([AdhanDecision]); if not, a quiet notice says the time has come and why there was no sound.
 *
 * The adhan sounds at the notification volume. It stops at its end, at «إيقاف» in its notification, when a volume key
 * is pressed, when the phone turns silent, vibrate or Do Not Disturb, or when a call or another app takes the audio.
 * While it plays the prayer silence waits ([SilenceModeController.holdAutoSilenceForAdhan]); it comes on at the end.
 */
class AdhanPlaybackService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var current: AdhanScheduler.Occurrence? = null
    private var focusRequest: AudioFocusRequest? = null
    private var volumes: List<Int>? = null
    private var stateReceiverRegistered = false
    private var volumeObserver: ContentObserver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStartId = 0

    /** Bumped by every new adhan and every stop, so a decision that comes back late is dropped. */
    private var generation = 0

    /** Whether this service holds the prayer silence back; set by the decision thread, cleared once. */
    private val holding = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stopped from the notification")
                finish()
            }
            ACTION_PLAY -> {
                val occurrence = intent.occurrence()
                // A service started in the foreground must show its notification first, whatever comes next.
                goForeground(occurrence)
                when {
                    occurrence == null -> if (current == null) finish()
                    occurrence == current -> Log.d(TAG, "Adhan of $occurrence already handled")
                    else -> begin(occurrence)
                }
            }
            else -> if (current == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun begin(occurrence: AdhanScheduler.Occurrence) {
        if (current != null) stopPlayback()
        current = occurrence
        val token = ++generation
        acquireWakeLock()
        val app = applicationContext
        work.execute {
            val result = runCatching { prepare(app, occurrence) }
                .onFailure { Log.w(TAG, "Could not prepare the adhan of $occurrence", it) }
                .getOrElse { Prepared(Outcome.QUIET_SILENT_PHONE, null, failed = true) }
            main.post { onPrepared(token, occurrence, result) }
        }
    }

    private class Prepared(val outcome: Outcome, val player: MediaPlayer?, val failed: Boolean = false)

    /** Off the main thread: the decision, then for an adhan that sounds the player and the hold on the silence. */
    private fun prepare(app: Context, occurrence: AdhanScheduler.Occurrence): Prepared {
        val outcome = decide(app, occurrence)
        Log.d(TAG, "Adhan of $occurrence: $outcome")
        if (outcome != Outcome.PLAY && outcome != Outcome.LIFT_AND_PLAY) return Prepared(outcome, null)
        val voice = AdhanSettings.voice(app, occurrence.prayer)
        val prepared = MediaPlayer.create(app, voice.rawResId, audioAttributes(), 0)
            ?: return Prepared(Outcome.QUIET_SILENT_PHONE, null, failed = true)
        val until = System.currentTimeMillis() + prepared.duration.coerceAtLeast(0) + HOLD_MARGIN_MILLIS
        val lifted = SilenceModeController.holdAutoSilenceForAdhan(app, until)
        holding.set(true)
        if (PrefsManager.isEnabled(app)) AdhanScheduler.armSilenceResume(app, until + RESUME_DELAY_MILLIS)
        if (lifted || outcome == Outcome.LIFT_AND_PLAY) {
            // The ringer and Do Not Disturb come back asynchronously on some phones (see restoreState).
            val deadline = SystemClock.elapsedRealtime() + LIFT_WAIT_MILLIS
            while (phoneQuiet(app) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        }
        // Still quiet: the user's own silent, vibrate or Do Not Disturb lay under the prayer silence.
        if (phoneQuiet(app)) {
            prepared.release()
            releaseHold(app)
            return Prepared(Outcome.QUIET_SILENT_PHONE, null)
        }
        return Prepared(outcome, prepared)
    }

    private fun decide(app: Context, occurrence: AdhanScheduler.Occurrence): Outcome {
        val audio = app.getSystemService(AudioManager::class.java)
        val autoActive = PrefsManager.isEnabled(app) && PrefsManager.isAutoSilenceActive(app)
        val combined = MosqueSilenceSettings.combined(app)
        val windows = if (autoActive && !combined) {
            runCatching {
                SilenceScheduler.planner(app).containing(SilenceScheduler.now())
                    .filter { !SilenceScheduler.isDismissed(app, it.prayer, it.prayerDay) }
                    .map { AdhanDecision.Window(it.prayer, it.prayerDay, it.start.toEpochMilli()) }
            }.getOrElse {
                Log.w(TAG, "Could not read the silence windows", it)
                // Unknown: counts as a silence the adhan must not lift.
                listOf(AdhanDecision.Window(occurrence.prayer, occurrence.prayerDay, Long.MIN_VALUE))
            }
        } else {
            emptyList()
        }
        return AdhanDecision.decide(
            AdhanDecision.Situation(
                enabled = AdhanSettings.isEnabled(app, occurrence.prayer),
                lateMillis = System.currentTimeMillis() - occurrence.atMillis,
                inCall = audio.mode == AudioManager.MODE_IN_CALL ||
                    audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
                    audio.mode == AudioManager.MODE_RINGTONE,
                userChoseAppSilence = PrefsManager.isManualSilenceActive(app) || WakeAlarmScheduler.isSilenceUntilAlarmActive(app),
                appSilence = AdhanDecision.appSilence(
                    autoActive = autoActive,
                    combined = combined,
                    windows = windows,
                    prayer = occurrence.prayer,
                    prayerDay = occurrence.prayerDay,
                    adhanMillis = occurrence.atMillis,
                ),
                phoneQuiet = phoneQuiet(app),
            ),
        )
    }

    private fun onPrepared(token: Int, occurrence: AdhanScheduler.Occurrence, prepared: Prepared) {
        if (token != generation) {
            // Stopped, or another adhan came, while this one was being decided.
            prepared.player?.release()
            releaseHoldLater()
            return
        }
        val playing = prepared.player
        if (playing == null) {
            if (prepared.failed) showQuietNotice(occurrence, null) else showQuietNotice(occurrence, prepared.outcome)
            finish()
            return
        }
        if (!requestFocus()) {
            // Android refuses the audio during a call.
            playing.release()
            showQuietNotice(occurrence, Outcome.QUIET_IN_CALL)
            finish()
            return
        }
        player = playing
        playing.setOnCompletionListener { finish() }
        playing.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "Player error $what/$extra")
            finish()
            true
        }
        playing.start()
        watchForStop()
    }

    // ---- Stopping ----

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (phoneQuiet(context)) {
                Log.d(TAG, "Phone turned quiet: stopping the adhan")
                finish()
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            Log.d(TAG, "Audio focus lost ($change): stopping the adhan")
            main.post { finish() }
        }
    }

    /** A volume key, silent, vibrate or Do Not Disturb stops the adhan, as most adhan apps do. */
    private fun watchForStop() {
        volumes = currentVolumes()
        ContextCompat.registerReceiver(
            this, stateReceiver,
            IntentFilter().apply {
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        stateReceiverRegistered = true
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                // Every system setting reports here; only a changed volume counts.
                if (currentVolumes() != volumes) {
                    Log.d(TAG, "Volume changed: stopping the adhan")
                    finish()
                }
            }
        }
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        volumeObserver = observer
    }

    private fun currentVolumes(): List<Int> {
        val audio = getSystemService(AudioManager::class.java)
        return VOLUME_STREAMS.map { audio.getStreamVolume(it) }
    }

    private fun requestFocus(): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(audioAttributes())
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
        val granted = getSystemService(AudioManager::class.java).requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focusRequest = request
        return granted
    }

    /** Ends the adhan now, its silence comes on, and the service goes. */
    private fun finish() {
        stopPlayback()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    private fun stopPlayback() {
        generation++
        current = null
        if (stateReceiverRegistered) {
            runCatching { unregisterReceiver(stateReceiver) }
            stateReceiverRegistered = false
        }
        volumeObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        volumeObserver = null
        player?.let { playing ->
            runCatching { if (playing.isPlaying) playing.stop() }
            runCatching { playing.release() }
        }
        player = null
        focusRequest?.let { request -> runCatching { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(request) } }
        focusRequest = null
        releaseHoldLater()
    }

    private fun releaseHoldLater() {
        if (!holding.get()) return
        val app = applicationContext
        // After any decision still running, on the same thread: it may be setting the hold right now.
        work.execute { releaseHold(app) }
    }

    private fun releaseHold(app: Context) {
        if (!holding.getAndSet(false)) return
        runCatching { SilenceModeController.releaseAdhanHold(app) }
            .onFailure { Log.w(TAG, "Could not bring the prayer silence back", it) }
        AdhanScheduler.cancelSilenceResume(app)
    }

    override fun onDestroy() {
        stopPlayback()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TunisianPrayerTimes:adhan")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MILLIS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
    }

    // ---- Notifications ----

    private fun goForeground(occurrence: AdhanScheduler.Occurrence?) {
        createChannels(this)
        val prayerName = occurrence?.let { AdhanSettings.prayerName(this, it.prayer) }
        val notification = NotificationCompat.Builder(this, CHANNEL_PLAYING)
            .setSmallIcon(R.drawable.ic_adhkar_mosque)
            .setContentTitle(prayerName?.let { getString(R.string.adhan_notification_title, it) } ?: getString(R.string.adhan_settings_title))
            .apply { if (prayerName != null) setContentText(getString(R.string.adhan_notification_text, prayerName)) }
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(MainActivityPendingIntents.adhan(this))
            .setDeleteIntent(stopIntent(this))
            .addAction(R.drawable.ic_stop, getString(R.string.adhan_notification_stop), stopIntent(this))
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
    }

    private fun showQuietNotice(occurrence: AdhanScheduler.Occurrence, outcome: Outcome?) {
        val reason = when (outcome) {
            Outcome.QUIET_SILENT_PHONE -> R.string.adhan_quiet_silent_phone
            Outcome.QUIET_IN_CALL -> R.string.adhan_quiet_in_call
            Outcome.QUIET_LATE -> R.string.adhan_quiet_late
            null -> R.string.adhan_quiet_failed
            Outcome.PLAY, Outcome.LIFT_AND_PLAY, Outcome.NOTHING -> return
        }
        postQuietNotice(this, occurrence, reason)
    }

    companion object {
        private const val TAG = "AdhanPlayback"
        internal const val ACTION_PLAY = "com.tunisianprayertimes.adhan.ACTION_PLAY"
        private const val ACTION_STOP = "com.tunisianprayertimes.adhan.ACTION_STOP"
        private const val CHANNEL_PLAYING = "adhan_playing"
        private const val CHANNEL_QUIET = "adhan_quiet"
        private const val NOTIFICATION_ID = 740401
        private const val QUIET_NOTIFICATION_ID_BASE = 740410

        /** The silence waits this long after the recording's length, in case playback starts late. */
        private const val HOLD_MARGIN_MILLIS = 60_000L

        /** The safety alarm that brings the silence back comes this long after the hold ends. */
        private const val RESUME_DELAY_MILLIS = 5_000L
        private const val LIFT_WAIT_MILLIS = 2_000L
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60_000L

        /** The quiet notice goes away once the prayer has surely begun. */
        private const val QUIET_NOTICE_TIMEOUT_MILLIS = 30 * 60_000L

        private val VOLUME_STREAMS = listOf(
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_ALARM,
        )

        /** One thread for decisions and silence hand-backs, in the order asked. */
        private val work = Executors.newSingleThreadExecutor()

        /** The notification sound's stream: the adhan is as loud as the phone's notifications. */
        internal fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /** Silent, vibrate or any Do Not Disturb filter. */
        internal fun phoneQuiet(context: Context): Boolean {
            val audio = context.getSystemService(AudioManager::class.java)
            val filter = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
            return audio.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
                (filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
        }

        /** Called from the adhan's alarm. False when Android refused to start the player. */
        internal fun start(context: Context, occurrence: AdhanScheduler.Occurrence): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AdhanPlaybackService::class.java).setAction(ACTION_PLAY).putOccurrence(occurrence),
            )
            true
        } catch (error: Exception) {
            // Android 12+ refuses a foreground start outside an exact alarm (the inexact fallback without the permission).
            Log.w(TAG, "Could not start the adhan of $occurrence", error)
            false
        }

        private fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(
            context, NOTIFICATION_ID,
            Intent(context, AdhanPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        internal fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_PLAYING, context.getString(R.string.adhan_channel_playing), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.adhan_channel_playing_description)
                    // The adhan itself is the sound; the notification only carries «إيقاف».
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_QUIET, context.getString(R.string.adhan_channel_quiet), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.adhan_channel_quiet_description)
                },
            )
        }

        /** The time has come and the adhan did not sound: a silent notice says so, and why. */
        internal fun postQuietNotice(context: Context, occurrence: AdhanScheduler.Occurrence, reasonResId: Int) {
            createChannels(context)
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val prayerName = AdhanSettings.prayerName(context, occurrence.prayer)
            val reason = context.getString(reasonResId)
            val notification = NotificationCompat.Builder(context, CHANNEL_QUIET)
                .setSmallIcon(R.drawable.ic_adhkar_mosque)
                .setContentTitle(context.getString(R.string.adhan_quiet_title, prayerName))
                .setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(MainActivityPendingIntents.adhan(context))
                .setAutoCancel(true)
                .setSilent(true)
                .setTimeoutAfter(QUIET_NOTICE_TIMEOUT_MILLIS)
                .build()
            val index = AdhanSettings.prayers.indexOf(occurrence.prayer).coerceAtLeast(0)
            runCatching { manager.notify(QUIET_NOTIFICATION_ID_BASE + index, notification) }
                .onFailure { Log.w(TAG, "Could not post the quiet notice", it) }
        }
    }
}
