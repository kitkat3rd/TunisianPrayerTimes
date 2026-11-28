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
import android.os.SystemClock
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
 * the adhan sounds ([AdhanDecision]). A silenced phone still shows the voice waveform for the recording's duration.
 *
 * The adhan sounds at the ring volume: the level the side keys set when nothing plays, whose zero is the phone's silent
 * or vibrate mode. While the phone is muted, the waveform runs and the audio can join it when the ring is audible.
 * While it sounds (owner, 2026-11-16), one
 * press of volume down mutes it and it plays on unheard; volume up brings it back at the level it had, and then
 * raises it as usual (screen on or off, through its media session). It stops at its end, at «إيقاف» or a swipe of its
 * notification, at a headset's button, when an alarm starts ringing, or when a call or another app takes the audio.
 * If the phone is silenced during the adhan, the waveform continues from the same position with no audio.
 */
class AdhanPlaybackService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var current: AdhanScheduler.Occurrence? = null
    private var focusRequest: AudioFocusRequest? = null
    private var preparingResume = false

    /** The volumes while the adhan sounds: a key's step is measured against them. */
    private var volumes: List<Int>? = null

    /** The level to restore when a muted recording becomes audible again. */
    private var lastAudibleVolume = 1f

    /** The volumes before and just after the key that muted it, so that key's step can be put back. */
    private var muteStep: Pair<List<Int>, List<Int>>? = null
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
            ACTION_MUTE -> {
                // The page's volume keys while the adhan sounds; they move no volume themselves.
                if (player == null) {
                    if (current == null && warning == null) stopSelf(startId)
                } else if (intent.getBooleanExtra(EXTRA_MUTED, true)) {
                    setPlaybackVolume(0f)
                } else {
                    setVolumeFromPage(lastAudibleVolume)
                }
            }
            ACTION_PLAY -> {
                val occurrence = intent.occurrence()
                if (occurrence != null && occurrence == current) {
                    // Keep the current alert and its playback position on a duplicate delivery.
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
     * The seconds before the adhan. Its notification vibrates, for a quiet adhan too; then any volume key, screen on or
     * off (through the media session), a headset's button or «إلغاء هذا الأذان» cancels this one adhan. A short double buzz confirms a
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
                    restoreVolumes(before)
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

    private fun confirmCancelled() = vibrate(this, CANCELLED_VIBRATION)

    /**
     * A key pressed to cancel or mute the adhan also moved a volume a step (most often the ring): put each one back.
     * A phone the key turned to vibrate or silent stays so, since that may be what the user wanted.
     */
    private fun restoreVolumes(before: List<Int>) {
        val audio = getSystemService(AudioManager::class.java)
        VOLUME_STREAMS.forEachIndexed { index, stream ->
            val was = before.getOrNull(index) ?: return@forEachIndexed
            val now = audio.getStreamVolume(stream)
            if (was > 0 && now > 0 && now != was) runCatching { audio.setStreamVolume(stream, was, 0) }
        }
    }

    private fun begin(occurrence: AdhanScheduler.Occurrence) {
        if (current != null) stopPlayback()
        current = occurrence
        generation++
        acquireWakeLock()
        preparePlayback(occurrence)
    }

    private fun preparePlayback(occurrence: AdhanScheduler.Occurrence, resume: AdhanAlert? = null) {
        val token = generation
        val app = applicationContext
        work.execute {
            val result = runCatching { prepare(app, occurrence, resume) }
                .onFailure { Log.w(TAG, "Could not prepare the adhan of $occurrence", it) }
                .getOrElse { Prepared(null) }
            main.post { onPrepared(token, occurrence, result, resume) }
        }
    }

    private class Prepared(
        val outcome: Outcome?,
        val player: MediaPlayer? = null,
        val voice: AdhanVoice? = null,
        val silentDurationMillis: Long = 0L,
    )

    /** Off the main thread: prepare the audio player or time the silent display from the waveform samples. */
    private fun prepare(app: Context, occurrence: AdhanScheduler.Occurrence, resume: AdhanAlert?): Prepared {
        val outcome = decide(app, occurrence, forecast = false, inProgress = resume != null)
        Log.d(TAG, "Adhan of $occurrence: $outcome")
        if (outcome != Outcome.PLAY && (resume != null || !outcome.hasSilentWave())) return Prepared(outcome)
        val voice = resume?.voice ?: AdhanSettings.voice(app, occurrence.prayer)
        if (outcome != Outcome.PLAY) {
            val duration = app.resources.openRawResource(voice.levelResId).use { it.readBytes().size * ADHAN_LEVEL_INTERVAL_MILLIS }
            return Prepared(outcome, voice = voice, silentDurationMillis = duration)
        }
        val prepared = MediaPlayer.create(app, voice.rawResId, audioAttributes(), 0)
            ?: return Prepared(null)
        return Prepared(outcome, prepared, voice = voice)
    }

    private fun onPrepared(token: Int, occurrence: AdhanScheduler.Occurrence, prepared: Prepared, resume: AdhanAlert?) {
        if (token != generation) {
            // Stopped, or another adhan came, while this one was being decided.
            prepared.player?.release()
            return
        }
        preparingResume = false
        val playing = prepared.player
        if (playing == null) {
            if (resume == null) {
                showQuietNotice(occurrence, prepared.outcome, prepared.voice, durationMillis = prepared.silentDurationMillis)
            }
            return
        }
        player = playing
        playing.setOnCompletionListener { if (player === playing) finish() }
        playing.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "Player error $what/$extra")
            if (player === playing) finish()
            true
        }
        watchPhoneAudio()
        if (resume == null) {
            startPlayback(playing, occurrence, prepared.voice, token)
            return
        }
        val position = (SystemClock.uptimeMillis() - resume.startedAtUptimeMillis).coerceAtLeast(0L)
        if (position >= playing.duration) {
            finish()
            return
        }
        playing.setOnSeekCompleteListener {
            if (player === playing) {
                playing.setOnSeekCompleteListener(null)
                startPlayback(playing, occurrence, prepared.voice, token, resume)
            }
        }
        runCatching { playing.seekTo(position, MediaPlayer.SEEK_CLOSEST) }.onFailure {
            Log.w(TAG, "Could not seek to the current adhan position", it)
            releasePlayer()
        }
    }

    private fun startPlayback(
        playing: MediaPlayer,
        occurrence: AdhanScheduler.Occurrence,
        voice: AdhanVoice?,
        token: Int,
        resume: AdhanAlert? = null,
    ) {
        // The phone or app may have entered silence while the player was preparing or seeking.
        val outcome = decide(this, occurrence, forecast = false, inProgress = resume != null)
        if (outcome != Outcome.PLAY) {
            if (resume == null) showQuietNotice(occurrence, outcome, voice, durationMillis = playing.duration.toLong())
            else releasePlayer()
            return
        }
        if (!requestFocus(playing)) {
            releasePlayer()
            if (resume == null) {
                showQuietNotice(occurrence, if (inCall(getSystemService(AudioManager::class.java))) Outcome.QUIET_IN_CALL else null)
            }
            return
        }
        val level = resume?.volume ?: 1f
        playing.setVolume(level, level)
        lastAudibleVolume = level
        volumes = currentVolumes()
        watchForAlarms(playing)
        playing.start()
        main.removeCallbacks(endQuietAlertLater)
        // Open the page even without a warning; an already-open countdown changes in place.
        val openPage = alertState.value?.occurrence != occurrence
        alertState.value = AdhanAlert(
            occurrence, sounding = true, voice = voice,
            startedAtUptimeMillis = resume?.startedAtUptimeMillis ?: SystemClock.uptimeMillis(), volume = level,
            onVolumeChange = { level -> if (token == generation) setVolumeFromPage(level) },
        )
        goForeground(occurrence, fullScreen = openPage)
    }

    // ---- Stopping ----

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            phoneAudioChanged()
        }
    }

    private fun resumeIfAudible(shown: AdhanAlert) {
        if (preparingResume || player != null || phoneQuiet(this) || volumeZero(this)) return
        preparingResume = true
        preparePlayback(shown.occurrence, resume = shown)
    }

    private fun continueSilently() {
        val playing = player ?: return
        val shown = alertState.value ?: return
        if (!shown.sounding || shown.occurrence != current) return
        val outcome = decide(this, shown.occurrence, forecast = false, inProgress = true)
        if (outcome == Outcome.PLAY) return
        Log.d(TAG, "Phone turned quiet: continuing the adhan silently")
        showQuietNotice(
            shown.occurrence, outcome, shown.voice, shown.startedAtUptimeMillis, playing.duration.toLong(), lastAudibleVolume,
        )
    }

    private fun watchPhoneAudio() {
        if (stateReceiverRegistered) return
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
                phoneAudioChanged()
            }
        }
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        volumeObserver = observer
        session = openSession()
    }

    private fun phoneAudioChanged() {
        val shown = alertState.value ?: return
        if (shown.occurrence != current) return
        if (shown.canResume) {
            resumeIfAudible(shown)
            return
        }
        if (!shown.sounding) return
        if (phoneQuiet(this) || volumeZero(this)) {
            continueSilently()
            return
        }
        // The phone can send a volume key to a stream other than the ring.
        val before = volumes ?: return
        val now = currentVolumes()
        if (now == before) return
        volumes = now
        if (now.indices.any { now[it] < before[it] }) {
            // Keep the key's step so the step up can restore the sound even when volume was full.
            if (setPlaybackVolume(0f)) muteStep = before to now
        } else if (shown.muted) {
            setPlaybackVolume(lastAudibleVolume)
        }
    }

    private fun watchForAlarms(playing: MediaPlayer) {
        // An alarm that starts ringing ends the adhan: the app's wake alarms take no audio focus, so the focus
        // listener alone would leave both sounding together.
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                if (player === playing && alarmRinging(configs)) {
                    Log.d(TAG, "An alarm is ringing: stopping the adhan")
                    finish()
                }
            }
        }
        getSystemService(AudioManager::class.java).registerAudioPlaybackCallback(callback, main)
        playbackCallback = callback
    }

    /**
     * With the screen off, Android sends the volume keys only to a playing media session (or to playing music), so
     * without one the keys did nothing to the adhan (OnePlus, 15 Nov 2026). This session plays at the adhan's own
     * attributes: a key moves the ring volume, which the warning and [watchPhoneAudio] see, and a headset's button stops
     * it too. It posts no media notification, so no media controls show.
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

    /** Applies gain to this player while the recording continues at its current position. */
    private fun setPlaybackVolume(level: Float): Boolean {
        val playing = player ?: return false
        val shown = alertState.value ?: return false
        if (!shown.sounding || shown.occurrence != current || level !in 0f..1f || shown.volume == level) return false
        runCatching { playing.setVolume(level, level) }.onFailure {
            Log.w(TAG, "Could not change the adhan volume", it)
            return false
        }
        if (level > 0f) {
            lastAudibleVolume = level
            muteStep = null
        }
        alertState.value = shown.copy(volume = level)
        return true
    }

    /** Restore the ring step if an off-screen key muted the recording and the page makes it audible again. */
    private fun setVolumeFromPage(level: Float) {
        val step = muteStep
        if (setPlaybackVolume(level) && level > 0f && step != null && currentVolumes() == step.second) {
            restoreVolumes(step.first)
        }
    }

    private fun currentVolumes(): List<Int> {
        val audio = getSystemService(AudioManager::class.java)
        return VOLUME_STREAMS.map { audio.getStreamVolume(it) }
    }

    private fun requestFocus(playing: MediaPlayer): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(audioAttributes())
            .setOnAudioFocusChangeListener({ change ->
                if (player === playing && (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
                    if (phoneQuiet(this)) {
                        continueSilently()
                    } else {
                        Log.d(TAG, "Audio focus lost ($change): stopping the adhan")
                        finish()
                    }
                }
            }, main)
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
        preparingResume = false
        if (stateReceiverRegistered) {
            runCatching { unregisterReceiver(stateReceiver) }
            stateReceiverRegistered = false
        }
        volumeObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        volumeObserver = null
        volumes = null
        // An adhan that ends muted puts back the step of the key that muted it, unless the volume moved since.
        muteStep?.let { (before, after) -> if (currentVolumes() == after) restoreVolumes(before) }
        muteStep = null
        lastAudibleVolume = 1f
        playbackCallback?.let { callback ->
            runCatching { getSystemService(AudioManager::class.java).unregisterAudioPlaybackCallback(callback) }
        }
        playbackCallback = null
        releaseSession()
        releasePlayer()
    }

    private fun releasePlayer() {
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

    private fun showQuietNotice(
        occurrence: AdhanScheduler.Occurrence,
        outcome: Outcome?,
        voice: AdhanVoice? = null,
        startedAtUptimeMillis: Long = SystemClock.uptimeMillis(),
        durationMillis: Long = 0L,
        volume: Float = 1f,
    ) {
        val reason = quietReason(outcome)
        if (reason == null || outcome == Outcome.QUIET_LATE) {
            // A disabled/cancelled or very late adhan must not open a page. A slightly late one keeps its tray notice.
            if (reason != null) postQuietNotice(this, occurrence, reason)
            finish()
            return
        }
        val openPage = alertState.value?.occurrence != occurrence
        // Release the audio player while keeping the waveform on its original timeline.
        stopPlayback()
        current = occurrence
        val animatedVoice = voice?.takeIf { outcome.hasSilentWave() && durationMillis > 0L }
        val shown = AdhanAlert(
            occurrence, sounding = false, voice = animatedVoice, startedAtUptimeMillis = startedAtUptimeMillis,
            countingDown = false, quietReasonResId = reason, volume = volume,
            canResume = animatedVoice != null && (outcome == Outcome.QUIET_SILENT_PHONE || outcome == Outcome.QUIET_ZERO_VOLUME),
        )
        alertState.value = shown
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
        if (animatedVoice != null) {
            main.postAtTime(endQuietAlertLater, startedAtUptimeMillis + durationMillis)
        } else {
            main.postDelayed(endQuietAlertLater, QUIET_ALERT_TIMEOUT_MILLIS)
        }
        if (shown.canResume) {
            watchPhoneAudio()
            resumeIfAudible(shown)
        }
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

        /**
         * The page's button, or a volume key there before the adhan: this adhan is cancelled before it sounds, or
         * stopped while it sounds.
         */
        internal fun cancelFromPage(context: Context) {
            runCatching { context.startService(Intent(context, AdhanPlaybackService::class.java).setAction(ACTION_CANCEL)) }
                .onFailure { Log.w(TAG, "Could not cancel from the page", it) }
        }

        /** The page's volume keys while the adhan sounds: down mutes it, up brings it back. */
        internal fun muteFromPage(context: Context, muted: Boolean) {
            runCatching {
                context.startService(
                    Intent(context, AdhanPlaybackService::class.java).setAction(ACTION_MUTE).putExtra(EXTRA_MUTED, muted),
                )
            }.onFailure { Log.w(TAG, "Could not change the mute from the page", it) }
        }
        private const val ACTION_MUTE = "com.tunisianprayertimes.adhan.ACTION_MUTE"
        private const val EXTRA_MUTED = "muted"
        private const val ACTION_STOP = "com.tunisianprayertimes.adhan.ACTION_STOP"
        internal const val CHANNEL_PLAYING = "adhan_playing"
        internal const val CHANNEL_QUIET = "adhan_quiet"
        internal const val CHANNEL_SOON = "adhan_soon"
        private const val NOTIFICATION_ID = 740401
        private const val WARNING_NOTIFICATION_ID = 740402
        private const val LATE_WARNING_GRACE_MILLIS = 2 * 60_000L

        /** The warning ends this long after the adhan's time if the adhan's alarm never came. */
        private const val WARNING_END_GRACE_MILLIS = 30_000L

        /** Three firm pulses, about two seconds: felt in a pocket, short enough not to alarm. */
        private val WARNING_VIBRATION = longArrayOf(0L, 500L, 250L, 500L, 250L, 500L)

        /** Two short taps: the cancellation took. */
        private val CANCELLED_VIBRATION = longArrayOf(0L, 60L, 120L, 60L)

        /** A small buzz before the app silences the phone (owner, 2026-11-16): two short pulses, under a second. */
        private val BEFORE_SILENCE_VIBRATION = longArrayOf(0L, 300L, 200L, 300L)

        /** The buzz's alarm may come this much early or the window may have moved by this much, and still buzz. */
        private const val BEFORE_SILENCE_SLACK_MILLIS = 60_000L
        private const val QUIET_NOTIFICATION_ID_BASE = 740410

        private const val WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60_000L

        /** The quiet notice goes away once the prayer has surely begun. */
        private const val QUIET_NOTICE_TIMEOUT_MILLIS = 30 * 60_000L

        /** Duration of a quiet reminder due to a call, a ringing alarm or a playback failure. */
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
        internal fun decide(app: Context, occurrence: AdhanScheduler.Occurrence, forecast: Boolean, inProgress: Boolean = false): Outcome {
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
                    lateMillis = if (forecast || inProgress) 0L else System.currentTimeMillis() - occurrence.atMillis,
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
         * Shortly before each enabled adhan, including one that will stay quiet. Every warning vibrates and opens the
         * countdown: a quiet adhan is still the prayer's time, and the owner expected to feel it (2026-11-16, an Asr
         * kept quiet by a silence starting with its adhan). Android keeps the warning still on a phone that is already
         * silent or in Do Not Disturb, so a silence that has begun stays undisturbed. Off the main thread: the
         * forecast reads the silence windows.
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
         * The warning's notification, audible adhan or quiet: it vibrates (its channel's pattern) and offers
         * «إلغاء هذا الأذان»; a quiet one gives its reason. With [fullScreen]
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
            return NotificationCompat.Builder(context, CHANNEL_SOON)
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

        /**
         * A few seconds before the app's time silence begins, when that silence will keep this adhan's warning still:
         * a short buzz first (owner, 2026-11-16: "vibrate before the app silences"). The windows are read again, so a
         * silence dismissed, moved or begun since the alarm was armed gives no buzz. It vibrates as a notification
         * does, so the phone's own silent mode, Do Not Disturb or notification-vibration setting still keeps it still.
         */
        internal fun buzzBeforeSilence(context: Context, occurrence: AdhanScheduler.Occurrence) {
            if (!timeWindowsSilence(context)) return
            val now = System.currentTimeMillis()
            val start = AdhanScheduler.silenceStartBeforeWarning(
                context, SilenceScheduler.planner(context), occurrence.atMillis - AdhanScheduler.WARNING_LEAD_MILLIS,
            )
            if (start == null || start <= now || start - now > AdhanScheduler.BEFORE_SILENCE_LEAD_MILLIS + BEFORE_SILENCE_SLACK_MILLIS) {
                Log.d(TAG, "No silence about to begin before the warning of $occurrence: no buzz")
                return
            }
            Log.d(TAG, "The silence begins before the warning of $occurrence: buzzing first")
            vibrate(context, BEFORE_SILENCE_VIBRATION)
        }

        /** With the usage of a notification: Android applies the phone's silent mode, Do Not Disturb and settings. */
        private fun vibrate(context: Context, pattern: LongArray) {
            runCatching {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(VibratorManager::class.java).defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Vibrator::class.java)
                }
                val effect = VibrationEffect.createWaveform(pattern, -1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
                }
            }.onFailure { Log.w(TAG, "Could not vibrate", it) }
        }

        /** The fallback warning, if one was posted outside the service. */
        internal fun cancelWarning(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(WARNING_NOTIFICATION_ID)
        }

        private fun quietReason(outcome: Outcome?): Int? = when (outcome) {
            Outcome.QUIET_SILENT_PHONE, Outcome.QUIET_APP_SILENCE -> R.string.adhan_quiet_silent_phone
            Outcome.QUIET_PRAYER_SILENCE -> R.string.adhan_quiet_prayer_silence
            Outcome.QUIET_ZERO_VOLUME -> R.string.adhan_quiet_zero_volume
            Outcome.QUIET_IN_CALL -> R.string.adhan_quiet_in_call
            Outcome.QUIET_ALARM_RINGING -> R.string.adhan_quiet_alarm_ringing
            Outcome.QUIET_LATE -> R.string.adhan_quiet_late
            null -> R.string.adhan_quiet_failed
            Outcome.PLAY, Outcome.NOTHING -> null
        }

        private fun Outcome?.hasSilentWave(): Boolean =
            this == Outcome.QUIET_SILENT_PHONE || this == Outcome.QUIET_APP_SILENCE ||
                this == Outcome.QUIET_PRAYER_SILENCE || this == Outcome.QUIET_ZERO_VOLUME

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
    /** The recording and its start time while the waveform is active, including during silence. */
    val voice: AdhanVoice? = null,
    val startedAtUptimeMillis: Long = 0L,
    val countingDown: Boolean = !sounding,
    val quietReasonResId: Int? = null,
    val canResume: Boolean = false,
    /** Player gain relative to the ring volume. Zero keeps the recording playing silently. */
    val volume: Float = 1f,
    /** The live recording's control, shared in-process with the alert activity on the main thread. */
    val onVolumeChange: ((Float) -> Unit)? = null,
) {
    val muted: Boolean get() = volume == 0f
}
