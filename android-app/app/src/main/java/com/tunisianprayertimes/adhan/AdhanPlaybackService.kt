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
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tunisianprayertimes.MainActivityPendingIntents
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.adhan.AdhanDecision.Outcome
import com.tunisianprayertimes.adhan.AdhanScheduler.occurrence
import com.tunisianprayertimes.adhan.AdhanScheduler.putOccurrence
import com.tunisianprayertimes.mosque.MosqueSilenceSettings
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays one adhan. Started by its alarm, it goes to the foreground at once, then decides off the main thread whether
 * the adhan sounds ([AdhanDecision]); if not, a quiet notice says the time has come and why there was no sound.
 *
 * The adhan sounds at the ring volume: the level the side keys set when nothing plays, whose zero is the phone's silent
 * or vibrate mode. At zero it does not start, and the quiet notice says so. It stops at its end, at «إيقاف» or a swipe
 * of its notification, at a volume key (screen on or off, through its media session), at a headset's button, when the
 * phone turns silent, vibrate or Do Not Disturb, when an alarm starts ringing, or when a call or another app takes
 * the audio. App silence always takes priority; playback never changes or postpones the silencer.
 */
class AdhanPlaybackService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var current: AdhanScheduler.Occurrence? = null
    private var focusRequest: AudioFocusRequest? = null
    private var volumes: List<Int>? = null
    private var stateReceiverRegistered = false
    private var volumeObserver: ContentObserver? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var session: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStartId = 0

    /** The adhan whose warning is running: a volume key or «إلغاء هذا الأذان» cancels it. */
    private var warning: AdhanScheduler.Occurrence? = null
    private var warningVolumes: List<Int>? = null
    private var warningObserver: ContentObserver? = null
    private var warningPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private val endWarningLater = Runnable { endWarning() }

    private val endQuietAlertLater = Runnable {
        val shown = alertState.value
        shown?.quietReasonResId?.let { postQuietNotice(this, shown.occurrence, it) }
        finish()
    }

    /** Bumped by every new adhan and every stop, so a decision that comes back late is dropped. */
    private var generation = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stopped from the notification")
                finish()
            }
            ACTION_CANCEL -> {
                // The full-screen page's button or a volume key there: cancel during the warning, stop during the adhan.
                val warned = warning
                if (warned != null) cancelFromWarning(warned, confirm = false) else finish()
            }
            ACTION_PLAY -> {
                val occurrence = intent.occurrence()
                if (occurrence != null && occurrence == current) {
                    // Keep a quiet page's reason and close button on a duplicate delivery too.
                    Log.d(TAG, "Adhan of $occurrence already handled")
                    return START_NOT_STICKY
                }
                // A service started in the foreground must show its notification first, whatever comes next.
                goForeground(occurrence)
                // The adhan's own time: its warning is over.
                endWarning(stopService = false)
                when {
                    occurrence == null -> if (current == null) finish()
                    else -> begin(occurrence)
                }
            }
            ACTION_WARN -> {
                val occurrence = intent.occurrence()
                val playing = current
                when {
                    // A late warning must not replace an adhan's playing or quiet notification.
                    playing != null -> Unit
                    occurrence == null -> {
                        goForeground(null)
                        finish()
                    }
                    else -> {
                        val reason = intent.getIntExtra(EXTRA_QUIET_REASON, 0).takeIf { it != 0 }
                        startForegroundWith(warningBuilder(this, occurrence, fullScreen = true, quietReasonResId = reason).build())
                        beginWarning(occurrence, reason)
                    }
                }
            }
            else -> if (current == null && warning == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    // ---- The warning ----

    /**
     * The seconds before the adhan. An audible adhan's notification vibrates; then any volume key, screen on or off (through the
     * media session), a headset's button or «إلغاء هذا الأذان» cancels this one adhan. A short double buzz confirms a
     * key's cancellation, since the phone may still be in a pocket. The adhan's alarm ends the warning at its time.
     */
    private fun beginWarning(occurrence: AdhanScheduler.Occurrence, quietReasonResId: Int?) {
        endWarning(stopService = false)
        warning = occurrence
        alertState.value = AdhanAlert(occurrence, sounding = false, quietReasonResId = quietReasonResId)
        acquireWakeLock()
        val before = currentVolumes()
        warningVolumes = before
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                if (warning == occurrence && currentVolumes() != before) {
                    Log.d(TAG, "Volume key during the warning: cancelling the adhan of $occurrence")
                    restoreAdhanVolume(before)
                    cancelFromWarning(occurrence, confirm = true)
                }
            }
        }
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        warningObserver = observer
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (warning == occurrence && AdhanSettings.isSkipped(this, occurrence.prayer, occurrence.prayerDay)) endWarning()
        }
        AdhanSettings.registerListener(this, listener)
        warningPrefsListener = listener
        session = openSession()
        // If the adhan's alarm never comes, the warning still ends.
        val untilAdhan = (occurrence.atMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        main.postDelayed(endWarningLater, untilAdhan + WARNING_END_GRACE_MILLIS)
    }

    private fun cancelFromWarning(occurrence: AdhanScheduler.Occurrence, confirm: Boolean) {
        AdhanSettings.skip(this, occurrence.prayer, occurrence.prayerDay)
        if (confirm) confirmCancelled()
        endWarning()
    }

    /** Ends the warning; with [stopService], the service too unless an adhan is sounding. */
    private fun endWarning(stopService: Boolean = true) {
        main.removeCallbacks(endWarningLater)
        warningObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        warningObserver = null
        warningPrefsListener?.let { AdhanSettings.unregisterListener(this, it) }
        warningPrefsListener = null
        warningVolumes = null
        val wasWarning = warning != null
        warning = null
        if (wasWarning && current == null) {
            releaseSession()
            if (stopService) finish()
        }
    }

    /** A key on the headset or the session's stop: during the warning it cancels the adhan, during the adhan it stops it. */
    private fun stopFromUser() {
        val warned = warning
        if (warned != null) cancelFromWarning(warned, confirm = true) else finish()
    }

    private fun confirmCancelled() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            val effect = VibrationEffect.createWaveform(CANCELLED_VIBRATION, -1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            }
        }.onFailure { Log.w(TAG, "Could not confirm the cancellation", it) }
    }

    /**
     * A key pressed to cancel or stop the adhan also moved the ring a step: put it back. A phone the key turned to
     * vibrate or silent stays so, since that may be what the user wanted.
     */
    private fun restoreAdhanVolume(before: List<Int>) {
        val was = before.getOrNull(VOLUME_STREAMS.indexOf(ADHAN_STREAM)) ?: return
        val audio = getSystemService(AudioManager::class.java)
        val now = audio.getStreamVolume(ADHAN_STREAM)
        if (was > 0 && now > 0 && now != was) runCatching { audio.setStreamVolume(ADHAN_STREAM, was, 0) }
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

    private class Prepared(
        val outcome: Outcome,
        val player: MediaPlayer?,
        val failed: Boolean = false,
        val voice: AdhanVoice? = null,
    )

    /** Off the main thread: the decision, then the player for an adhan allowed to sound. */
    private fun prepare(app: Context, occurrence: AdhanScheduler.Occurrence): Prepared {
        val outcome = decide(app, occurrence)
        Log.d(TAG, "Adhan of $occurrence: $outcome")
        if (outcome != Outcome.PLAY) return Prepared(outcome, null)
        val voice = AdhanSettings.voice(app, occurrence.prayer)
        val prepared = MediaPlayer.create(app, voice.rawResId, audioAttributes(), 0)
            ?: return Prepared(Outcome.QUIET_SILENT_PHONE, null, failed = true)
        return Prepared(outcome, prepared, voice = voice)
    }

    private fun decide(app: Context, occurrence: AdhanScheduler.Occurrence): Outcome = decide(app, occurrence, forecast = false)

    private fun onPrepared(token: Int, occurrence: AdhanScheduler.Occurrence, prepared: Prepared) {
        if (token != generation) {
            // Stopped, or another adhan came, while this one was being decided.
            prepared.player?.release()
            return
        }
        val playing = prepared.player
        if (playing == null) {
            if (prepared.failed) showQuietNotice(occurrence, null) else showQuietNotice(occurrence, prepared.outcome)
            return
        }
        if (!requestFocus()) {
            // Android refuses the audio during a call, and may refuse it to a background app: say which.
            playing.release()
            showQuietNotice(occurrence, if (inCall(getSystemService(AudioManager::class.java))) Outcome.QUIET_IN_CALL else null)
            return
        }
        player = playing
        playing.setOnCompletionListener { finish() }
        playing.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "Player error $what/$extra")
            finish()
            true
        }
        watchForStop()
        // Preparation and focus acquisition can take time. Recheck after registering for changes so a silence
        // that began in the meantime prevents playback, and later DND/ringer changes stop it.
        val outcome = decide(this, occurrence)
        if (outcome != Outcome.PLAY) {
            showQuietNotice(occurrence, outcome)
            return
        }
        playing.start()
        // Open the page even without a warning; an already-open countdown changes in place.
        val openPage = alertState.value?.occurrence != occurrence
        alertState.value = AdhanAlert(occurrence, sounding = true, voice = prepared.voice, startedAtMillis = System.currentTimeMillis())
        goForeground(occurrence, fullScreen = openPage)
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
                val before = volumes ?: return
                if (currentVolumes() != before) {
                    Log.d(TAG, "Volume changed: stopping the adhan")
                    restoreAdhanVolume(before)
                    finish()
                }
            }
        }
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        volumeObserver = observer
        // An alarm that starts ringing ends the adhan: the app's wake alarms take no audio focus, so the focus
        // listener alone would leave both sounding together.
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                if (alarmRinging(configs)) {
                    Log.d(TAG, "An alarm is ringing: stopping the adhan")
                    finish()
                }
            }
        }
        getSystemService(AudioManager::class.java).registerAudioPlaybackCallback(callback, main)
        playbackCallback = callback
        session = openSession()
    }

    /**
     * With the screen off, Android sends the volume keys only to a playing media session (or to playing music), so
     * without one the keys did nothing to the adhan (OnePlus, 15 Nov 2026). This session plays at the adhan's own
     * attributes: a key moves the ring volume, which [watchForStop] sees, and a headset's button stops it too. It
     * posts no media notification, so no media controls show.
     */
    private fun openSession(): MediaSession? = runCatching {
        MediaSession(this, TAG).apply {
            setPlaybackToLocal(audioAttributes())
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPause() = stopFromUser()
                    override fun onStop() = stopFromUser()
                },
                main,
            )
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                    .build(),
            )
            isActive = true
        }
    }.onFailure { Log.w(TAG, "Could not open the adhan's media session", it) }.getOrNull()

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

    /** Ends the adhan now and stops the service without changing the silencer. */
    private fun finish() {
        endWarning(stopService = false)
        stopPlayback()
        alertState.value = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    private fun releaseSession() {
        session?.let { open ->
            runCatching {
                open.isActive = false
                open.release()
            }
        }
        session = null
    }

    private fun stopPlayback() {
        main.removeCallbacks(endQuietAlertLater)
        generation++
        current = null
        if (stateReceiverRegistered) {
            runCatching { unregisterReceiver(stateReceiver) }
            stateReceiverRegistered = false
        }
        volumeObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        volumeObserver = null
        playbackCallback?.let { callback ->
            runCatching { getSystemService(AudioManager::class.java).unregisterAudioPlaybackCallback(callback) }
        }
        playbackCallback = null
        releaseSession()
        player?.let { playing ->
            runCatching { if (playing.isPlaying) playing.stop() }
            runCatching { playing.release() }
        }
        player = null
        focusRequest?.let { request -> runCatching { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(request) } }
        focusRequest = null
    }

    override fun onDestroy() {
        endWarning(stopService = false)
        stopPlayback()
        alertState.value = null
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

    private fun goForeground(occurrence: AdhanScheduler.Occurrence?, fullScreen: Boolean = false) {
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
            .setOnlyAlertOnce(!fullScreen)
            .setContentIntent(if (occurrence != null) AdhanAlertActivity.pendingIntent(this) else MainActivityPendingIntents.adhan(this))
            .apply { if (fullScreen) setFullScreenIntent(AdhanAlertActivity.pendingIntent(this@AdhanPlaybackService), true) }
            .setDeleteIntent(stopIntent(this))
            .addAction(R.drawable.ic_stop, getString(R.string.adhan_notification_stop), stopIntent(this))
            .build()
        startForegroundWith(notification)
    }

    /** One notification for the warning and then the adhan: the adhan's replaces the warning's in place. */
    private fun startForegroundWith(notification: Notification) {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
    }

    private fun showQuietNotice(occurrence: AdhanScheduler.Occurrence, outcome: Outcome?) {
        val reason = quietReason(outcome)
        if (reason == null || outcome == Outcome.QUIET_LATE) {
            // A disabled/cancelled or very late adhan must not open a page. A slightly late one keeps its tray notice.
            if (reason != null) postQuietNotice(this, occurrence, reason)
            finish()
            return
        }
        val openPage = alertState.value?.occurrence != occurrence
        // Keep only the visual reminder: no player, audio focus or media session while the adhan is quiet.
        stopPlayback()
        current = occurrence
        alertState.value = AdhanAlert(occurrence, sounding = false, countingDown = false, quietReasonResId = reason)
        startForegroundWith(
            NotificationCompat.Builder(this, CHANNEL_PLAYING)
                .setSmallIcon(R.drawable.ic_adhkar_mosque)
                .setContentTitle(getString(R.string.adhan_quiet_title, AdhanSettings.prayerName(this, occurrence.prayer)))
                .setContentText(getString(reason))
                .setStyle(NotificationCompat.BigTextStyle().bigText(getString(reason)))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(!openPage)
                .setContentIntent(AdhanAlertActivity.pendingIntent(this))
                .apply { if (openPage) setFullScreenIntent(AdhanAlertActivity.pendingIntent(this@AdhanPlaybackService), true) }
                .setDeleteIntent(stopIntent(this))
                .addAction(R.drawable.ic_stop, getString(R.string.adhan_close), stopIntent(this))
                .build(),
        )
        main.postDelayed(endQuietAlertLater, QUIET_ALERT_TIMEOUT_MILLIS)
    }

    companion object {
        private const val TAG = "AdhanPlayback"
        internal const val ACTION_PLAY = "com.tunisianprayertimes.adhan.ACTION_PLAY"
        private const val ACTION_WARN = "com.tunisianprayertimes.adhan.ACTION_WARN"
        private const val ACTION_CANCEL = "com.tunisianprayertimes.adhan.ACTION_CANCEL"
        private const val EXTRA_QUIET_REASON = "quiet_reason"

        private val alertState = MutableStateFlow<AdhanAlert?>(null)

        /** Null once the warning and its adhan are over: the page then closes. */
        internal val alert: StateFlow<AdhanAlert?> = alertState.asStateFlow()

        /** The page's button or volume key: this adhan is cancelled before it sounds, or stopped while it sounds. */
        internal fun cancelFromPage(context: Context) {
            runCatching { context.startService(Intent(context, AdhanPlaybackService::class.java).setAction(ACTION_CANCEL)) }
                .onFailure { Log.w(TAG, "Could not cancel from the page", it) }
        }
        private const val ACTION_STOP = "com.tunisianprayertimes.adhan.ACTION_STOP"
        private const val CHANNEL_PLAYING = "adhan_playing"
        private const val CHANNEL_QUIET = "adhan_quiet"
        private const val CHANNEL_SOON = "adhan_soon"
        private const val NOTIFICATION_ID = 740401
        private const val WARNING_NOTIFICATION_ID = 740402
        private const val LATE_WARNING_GRACE_MILLIS = 2 * 60_000L

        /** The warning ends this long after the adhan's time if the adhan's alarm never came. */
        private const val WARNING_END_GRACE_MILLIS = 30_000L

        /** Three firm pulses, about two seconds: felt in a pocket, short enough not to alarm. */
        private val WARNING_VIBRATION = longArrayOf(0L, 500L, 250L, 500L, 250L, 500L)

        /** Two short taps: the cancellation took. */
        private val CANCELLED_VIBRATION = longArrayOf(0L, 60L, 120L, 60L)
        private const val QUIET_NOTIFICATION_ID_BASE = 740410

        private const val WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60_000L

        /** The quiet notice goes away once the prayer has surely begun. */
        private const val QUIET_NOTICE_TIMEOUT_MILLIS = 30 * 60_000L

        /** A silent page must not keep the screen awake for the length of an unheard recording. */
        internal const val QUIET_ALERT_TIMEOUT_MILLIS = 30_000L

        private val VOLUME_STREAMS = listOf(
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_ALARM,
        )

        /** One thread for preparing adhans in the order asked. */
        private val work = Executors.newSingleThreadExecutor()

        /**
         * The rule of [AdhanDecision] read from the phone. With [forecast], just ahead for the warning: the silence
         * windows are read at the adhan's own time, so a silence starting with it already counts, and the adhan is
         * taken as on time.
         */
        internal fun decide(app: Context, occurrence: AdhanScheduler.Occurrence, forecast: Boolean): Outcome {
            val audio = app.getSystemService(AudioManager::class.java)
            val autoActive = PrefsManager.isAutoSilenceActive(app)
            val combined = MosqueSilenceSettings.combined(app)
            val at = if (forecast) Instant.ofEpochMilli(occurrence.atMillis) else SilenceScheduler.now()
            // The adhan alarm may arrive first: a silence due now still takes priority.
            val windowActive = if (timeWindowsSilence(app)) {
                runCatching {
                    SilenceScheduler.planner(app).containing(at)
                        .any { !SilenceScheduler.isDismissed(app, it.prayer, it.prayerDay) }
                }.getOrElse {
                    Log.w(TAG, "Could not read the silence windows", it)
                    true
                }
            } else {
                false
            }
            return AdhanDecision.decide(
                AdhanDecision.Situation(
                    enabled = AdhanSettings.isEnabled(app, occurrence.prayer) &&
                        !AdhanSettings.isSkipped(app, occurrence.prayer, occurrence.prayerDay),
                    lateMillis = if (forecast) 0L else System.currentTimeMillis() - occurrence.atMillis,
                    inCall = inCall(audio),
                    userChoseAppSilence = PrefsManager.isManualSilenceActive(app) || WakeAlarmScheduler.isSilenceUntilAlarmActive(app),
                    appSilence = AdhanDecision.appSilence(
                        autoActive = autoActive,
                        combined = combined,
                        windowActive = windowActive,
                    ),
                    phoneQuiet = phoneQuiet(app),
                    volumeZero = volumeZero(app),
                    alarmRinging = alarmRinging(audio.activePlaybackConfigurations),
                ),
            )
        }

        /**
         * Shortly before each enabled adhan, including one that will stay quiet. Only an audible adhan's warning
         * vibrates; silence never hides the countdown. Off the main thread: the forecast reads the silence windows.
         */
        internal fun warnBeforeAdhan(context: Context, occurrence: AdhanScheduler.Occurrence) {
            val outcome = decide(context, occurrence, forecast = true)
            Log.d(TAG, "Warning for the adhan of $occurrence: $outcome")
            if (outcome == Outcome.NOTHING) return
            val reason = quietReason(outcome)
            try {
                // The service listens for the volume keys during the warning.
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AdhanPlaybackService::class.java).setAction(ACTION_WARN).putOccurrence(occurrence)
                        .putExtra(EXTRA_QUIET_REASON, reason ?: 0),
                )
            } catch (error: Exception) {
                // Android refused the service: the notification alone still warns, with its button.
                Log.w(TAG, "Could not start the warning of $occurrence", error)
                createChannels(context)
                val notification = warningBuilder(context, occurrence, fullScreen = false, quietReasonResId = reason)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    // Gone once the adhan time has passed, even if its alarm never came.
                    .setTimeoutAfter(AdhanScheduler.WARNING_LEAD_MILLIS + LATE_WARNING_GRACE_MILLIS)
                    .build()
                runCatching { context.getSystemService(NotificationManager::class.java)?.notify(WARNING_NOTIFICATION_ID, notification) }
                    .onFailure { Log.w(TAG, "Could not post the warning", it) }
            }
        }

        /**
         * The warning's notification: it vibrates (its channel's pattern) and offers «إلغاء هذا الأذان». With [fullScreen]
         * (the service's own, which holds the page's state), a locked or dark phone shows the countdown page instead;
         * a phone in use shows the banner, as Android decides.
         */
        private fun warningBuilder(
            context: Context,
            occurrence: AdhanScheduler.Occurrence,
            fullScreen: Boolean,
            quietReasonResId: Int? = null,
        ): NotificationCompat.Builder {
            createChannels(context)
            val prayerName = AdhanSettings.prayerName(context, occurrence.prayer)
            val text = context.getString(quietReasonResId ?: R.string.adhan_soon_text)
            return NotificationCompat.Builder(context, if (quietReasonResId == null) CHANNEL_SOON else CHANNEL_PLAYING)
                .setSmallIcon(R.drawable.ic_adhkar_mosque)
                .setContentTitle(context.getString(R.string.adhan_soon_title, prayerName))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setContentIntent(if (fullScreen) AdhanAlertActivity.pendingIntent(context) else MainActivityPendingIntents.adhan(context))
                .apply { if (fullScreen) setFullScreenIntent(AdhanAlertActivity.pendingIntent(context), true) }
                .addAction(R.drawable.ic_stop, context.getString(R.string.adhan_soon_cancel), AdhanReceiver.skipIntent(context, occurrence))
        }

        /** The fallback warning, if one was posted outside the service. */
        internal fun cancelWarning(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(WARNING_NOTIFICATION_ID)
        }

        private fun quietReason(outcome: Outcome?): Int? = when (outcome) {
            Outcome.QUIET_SILENT_PHONE -> R.string.adhan_quiet_silent_phone
            Outcome.QUIET_PRAYER_SILENCE -> R.string.adhan_quiet_prayer_silence
            Outcome.QUIET_ZERO_VOLUME -> R.string.adhan_quiet_zero_volume
            Outcome.QUIET_IN_CALL -> R.string.adhan_quiet_in_call
            Outcome.QUIET_ALARM_RINGING -> R.string.adhan_quiet_alarm_ringing
            Outcome.QUIET_LATE -> R.string.adhan_quiet_late
            null -> R.string.adhan_quiet_failed
            Outcome.PLAY, Outcome.NOTHING -> null
        }

        /** The stream of [audioAttributes]: its volume is the adhan's volume. */
        internal const val ADHAN_STREAM = AudioManager.STREAM_RING

        /** The adhan's volume reads zero (a silent or vibrating phone also reads zero). */
        internal fun volumeZero(context: Context): Boolean =
            context.getSystemService(AudioManager::class.java).getStreamVolume(ADHAN_STREAM) == 0

        /**
         * The ringtone's stream: the adhan is as loud as the phone's ring. The notification volume, used before, can sit
         * at zero while the phone rings (OnePlus, 15 Nov 2026: ring 5 of 16, notifications 0), and a muted adhan
         * played to its end unheard. The ring volume is the one the side keys set, and its zero is silent or vibrate,
         * which [phoneQuiet] already honours.
         */
        internal fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        /** A call, or a call ringing. */
        internal fun inCall(audio: AudioManager): Boolean =
            audio.mode == AudioManager.MODE_IN_CALL ||
                audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
                audio.mode == AudioManager.MODE_RINGTONE

        /** An alarm is sounding: an app's player with the alarm usage. Android lists only playing players to apps. */
        internal fun alarmRinging(configs: List<AudioPlaybackConfiguration>): Boolean =
            configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_ALARM }

        /** The app's time-based silence windows apply: silence is on and not only at a mosque. */
        internal fun timeWindowsSilence(context: Context): Boolean =
            PrefsManager.isEnabled(context) && MosqueSilenceSettings.mode(context) != null && !MosqueSilenceSettings.combined(context)

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
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_SOON, context.getString(R.string.adhan_channel_soon), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.adhan_channel_soon_description)
                    // A vibration only: a sound would defeat the warning's purpose.
                    setSound(null, null)
                    enableVibration(true)
                    vibrationPattern = WARNING_VIBRATION
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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

/** The adhan the full-screen page shows ([AdhanAlertActivity]): counting down, sounding, or quiet. */
internal data class AdhanAlert(
    val occurrence: AdhanScheduler.Occurrence,
    val sounding: Boolean,
    /** While sounding: the recording and when it started, so the page's waveform follows the voice. */
    val voice: AdhanVoice? = null,
    val startedAtMillis: Long = 0L,
    val countingDown: Boolean = !sounding,
    val quietReasonResId: Int? = null,
)
