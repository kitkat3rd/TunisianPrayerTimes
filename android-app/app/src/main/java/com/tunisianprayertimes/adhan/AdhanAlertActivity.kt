package com.tunisianprayertimes.adhan

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.AppFontFamily
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The full-screen countdown of the warning (owner's idea, 2026-11-15): the prayer, the seconds left and one large
 * «إلغاء هذا الأذان», over the lock screen. At prayer time the waveform follows the recording, with a silence
 * reason and «إغلاق» when the phone is silenced. A volume key cancels the countdown or dismisses a brief reminder.
 * While audio is available, volume down mutes it, the first volume up brings it back, and then volume up raises
 * the ring volume as usual. The on-screen slider adjusts the current recording's gain.
 * Android opens the page only on a locked or dark phone; a phone in use shows the notification's banner.
 */
class AdhanAlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        // Volume up during the adhan raises the adhan's own volume.
        volumeControlStream = AdhanPlaybackService.ADHAN_STREAM
        // Dark clock and icons on the light paper, whatever the phone's theme.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            TunisianPrayerTimesTheme(fontFamily = AppFontFamily) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val alert by AdhanPlaybackService.alert.collectAsStateWithLifecycle()
                    val shown = alert
                    // The page may open a moment before the service records its adhan: wait briefly, then close.
                    LaunchedEffect(shown == null) {
                        if (shown == null) {
                            delay(OPEN_GRACE_MILLIS)
                            finish()
                        }
                    }
                    // The screen stays on through the countdown and the adhan (owner, 2026-11-15); the proximity
                    // sensor still darkens it in a pocket (onResume).
                    LaunchedEffect(shown != null) {
                        if (shown != null) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                    Surface(Modifier.fillMaxSize(), color = Paper) {
                        if (shown != null) {
                            AdhanAlertScreen(shown, onCancel = { AdhanPlaybackService.cancelFromPage(this) })
                        }
                    }
                }
            }
        }
    }

    /**
     * Like a phone call: the screen goes dark while something covers the sensor, so a lit page in a pocket cannot
     * cancel or stop the adhan by a stray touch. It takes the screen back when the phone comes out.
     */
    private var proximityLock: PowerManager.WakeLock? = null

    override fun onResume() {
        super.onResume()
        val power = getSystemService(PowerManager::class.java)
        if (proximityLock == null && power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityLock = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "TunisianPrayerTimes:adhanPage")
                .apply {
                    setReferenceCounted(false)
                    // Never longer than the longest adhan and its warning.
                    acquire(PROXIMITY_LOCK_TIMEOUT_MILLIS)
                }
        }
    }

    override fun onPause() {
        proximityLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        proximityLock = null
        super.onPause()
    }

    /** The volume keys whose press the page used: their repeats and release are swallowed too. */
    private val swallowedKeys = mutableSetOf<Int>()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isVolumeKey(keyCode)) return super.onKeyDown(keyCode, event)
        // A held key the page already used keeps doing nothing, even once the page has no adhan.
        if (event.repeatCount > 0 && keyCode in swallowedKeys) return true
        val shown = AdhanPlaybackService.alert.value ?: return super.onKeyDown(keyCode, event)
        if (shown.sounding) {
            when {
                // Volume up on a heard adhan takes the default way, on [volumeControlStream].
                keyCode == KeyEvent.KEYCODE_VOLUME_UP && !shown.muted -> return super.onKeyDown(keyCode, event)
                keyCode == KeyEvent.KEYCODE_VOLUME_UP -> AdhanPlaybackService.muteFromPage(this, muted = false)
                keyCode == KeyEvent.KEYCODE_VOLUME_MUTE -> AdhanPlaybackService.muteFromPage(this, muted = !shown.muted)
                !shown.muted -> AdhanPlaybackService.muteFromPage(this, muted = true)
            }
        } else if (shown.voice == null && event.repeatCount == 0) {
            AdhanPlaybackService.cancelFromPage(this)
        }
        swallowedKeys += keyCode
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Swallowed with its press, so the volume does not move.
        if (isVolumeKey(keyCode) && swallowedKeys.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE

    companion object {
        private const val OPEN_GRACE_MILLIS = 2_000L
        private const val PROXIMITY_LOCK_TIMEOUT_MILLIS = 10 * 60_000L
        private const val REQUEST_CODE = 740403

        internal fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, REQUEST_CODE,
            Intent(context, AdhanAlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

@Composable
internal fun AdhanAlertScreen(alert: AdhanAlert, onCancel: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(alert.countingDown) {
        // Every frame during the countdown, so the ring empties smoothly over the seconds.
        if (alert.countingDown) while (true) withFrameMillis { now = System.currentTimeMillis() }
    }
    val left = (alert.occurrence.atMillis - now).coerceAtLeast(0L)
    // Western digits, as everywhere in the app.
    val seconds = (left + 999L) / 1000L
    val share = (left.toFloat() / AdhanScheduler.WARNING_LEAD_MILLIS).coerceIn(0f, 1f)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds(),
    ) {
        // The gold line drawing of a Kairouan-style mosque along the bottom (owner's choice, 2026-11-15); the plain
        // paper of its upper part continues in [Paper] on taller screens, and a fade hides the seam.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .aspectRatio(BACKGROUND_WIDTH_PER_HEIGHT),
        ) {
            Image(
                painter = painterResource(R.drawable.adhan_alert_background),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(SEAM_FADE_SHARE)
                    .background(Brush.verticalGradient(listOf(Paper, Paper.copy(alpha = 0f)))),
            )
        }
        // The group sits just above the minaret's finial, so the number hangs in the sky over the mosque and the
        // button falls where the thumb rests; on a short screen it may reach down over the drawing.
        val drawingTop = maxHeight - maxWidth / BACKGROUND_WIDTH_PER_HEIGHT * (1f - DRAWING_TOP_IN_IMAGE)
        AdhanAlertContent(
            alert, seconds, share, onCancel,
            Modifier.height(drawingTop.coerceAtLeast(maxHeight * MIN_CONTENT_SHARE)),
        )
    }
}

/** Every colour comes from the drawing: sepia ink, antique gold, the paper itself. */
@Composable
private fun AdhanAlertContent(alert: AdhanAlert, seconds: Long, share: Float, onCancel: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .safeDrawingPadding()
            .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val voice = alert.voice
        Text(
            text = context.getString(
                if (alert.countingDown || voice != null) R.string.adhan_notification_title else R.string.adhan_quiet_title,
                AdhanSettings.prayerName(context, alert.occurrence.prayer),
            ),
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        // On short screens let the waveform give the controls and silence reason their space.
        val waveSpace = if (voice != null) Modifier.weight(1f, fill = false) else Modifier
        Box(waveSpace.size(RING_SIZE), contentAlignment = Alignment.Center) {
            when {
                voice != null -> VoiceWave(voice, alert.startedAtUptimeMillis, alert.muted)
                alert.countingDown -> Countdown(seconds, share)
                alert.quietReasonResId != null -> Text(
                    text = stringResource(alert.quietReasonResId),
                    color = Ink,
                    fontSize = 20.sp,
                    lineHeight = 30.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (voice != null && alert.quietReasonResId != null) {
            Text(
                text = stringResource(alert.quietReasonResId),
                color = Ink,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(if (voice != null) 12.dp else 36.dp))
        if (alert.onVolumeChange != null) {
            AdhanPlaybackControls(alert.volume, alert.onVolumeChange, onStop = onCancel)
        } else {
            Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                shape = RoundedCornerShape(percent = 50),
                border = BorderStroke(1.dp, Gold),
                colors = ButtonDefaults.buttonColors(containerColor = Ink, contentColor = Paper),
            ) {
                Text(
                    text = stringResource(if (alert.countingDown) R.string.adhan_soon_cancel else R.string.adhan_close),
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdhanPlaybackControls(volume: Float, onVolumeChange: (Float) -> Unit, onStop: () -> Unit) {
    val volumeLabel = stringResource(R.string.adhan_playback_volume)
    val colors = SliderDefaults.colors(activeTrackColor = Gold, inactiveTrackColor = Gold.copy(alpha = 0.32f))
    // Keep the scale and its gestures aligned with the fixed mute (left) and loud (right) endpoints.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_volume_off), contentDescription = null, modifier = Modifier.size(22.dp), tint = Ink)
            Slider(
                value = volume,
                onValueChange = onVolumeChange,
                modifier = Modifier.weight(1f).semantics { contentDescription = volumeLabel },
                thumb = { Box(Modifier.size(16.dp).background(Ink, CircleShape)) },
                track = { state ->
                    SliderDefaults.Track(
                        sliderState = state, modifier = Modifier.height(4.dp), colors = colors,
                        thumbTrackGapSize = 0.dp, drawStopIndicator = null,
                    )
                },
            )
            Icon(painterResource(R.drawable.ic_volume_on), contentDescription = null, modifier = Modifier.size(22.dp), tint = Ink)
        }
    }
    TextButton(onClick = onStop, colors = ButtonDefaults.textButtonColors(contentColor = Ink)) {
        Text(stringResource(R.string.adhan_notification_stop), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Before the adhan: the seconds inside a hairline gold ring that empties with them. */
@Composable
private fun Countdown(seconds: Long, share: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val stroke = RING_STROKE.toPx()
        val inset = stroke
        val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
        drawArc(
            color = Gold.copy(alpha = RING_TRACK_ALPHA),
            startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(inset, inset), size = arcSize,
            style = Stroke(width = stroke * 0.6f),
        )
        drawArc(
            color = Gold,
            startAngle = -90f, sweepAngle = 360f * share, useCenter = false,
            topLeft = Offset(inset, inset), size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
    Text(
        text = seconds.toString(),
        fontSize = 88.sp,
        lineHeight = 96.sp,
        fontWeight = FontWeight.Light,
        color = Ink,
    )
}

/**
 * While the adhan sounds: not a timer (owner, 2026-11-15: a countdown does not suit a sound that is playing) but the
 * voice itself, in the pattern of voice-note players the owner chose. Thin gold bars follow the recording's measured
 * loudness ([AdhanVoice.levelResId]) at the moment it plays: the middle bar is now, and each bar outward a little
 * earlier, so the muezzin's phrases spread from the centre and the bars fall still in his pauses. No microphone.
 */
@Composable
private fun VoiceWave(voice: AdhanVoice, startedAtUptimeMillis: Long, muted: Boolean) {
    val context = LocalContext.current
    val levels by produceState<ByteArray?>(initialValue = null, voice) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.resources.openRawResource(voice.levelResId).use { it.readBytes() } }.getOrNull()
        }
    }
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { now = SystemClock.uptimeMillis() } }
    // Muted by volume down: the voice runs on, faded, so the page shows the adhan is still going.
    val shade = if (muted) WAVE_MUTED_ALPHA else 1f
    Canvas(Modifier.fillMaxSize()) {
        val data = levels
        val position = (now - startedAtUptimeMillis).coerceAtLeast(0L).toFloat() / ADHAN_LEVEL_INTERVAL_MILLIS
        val barWidth = WAVE_BAR_WIDTH.toPx()
        val step = barWidth + WAVE_BAR_GAP.toPx()
        val tallest = size.height * WAVE_HEIGHT_SHARE
        for (k in -WAVE_SIDE_BARS..WAVE_SIDE_BARS) {
            val distance = abs(k).toFloat() / WAVE_SIDE_BARS
            val level = if (data == null) 0f else levelAt(data, position - abs(k) * WAVE_LEVELS_PER_BAR)
            // A curve, so the voice's swells still show near the top instead of flattening into a block.
            val height = barWidth + (tallest - barWidth) * level.pow(WAVE_CURVE) * (1f - WAVE_EDGE_DROP * distance)
            val x = center.x + k * step
            drawLine(
                color = Gold.copy(alpha = (1f - WAVE_EDGE_FADE * distance) * shade),
                start = Offset(x, center.y - height / 2f),
                end = Offset(x, center.y + height / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** The loudness at a fractional index, between the two measures around it; 0 before the start and after the end. */
private fun levelAt(data: ByteArray, index: Float): Float {
    if (index < 0f || index >= data.size - 1) return 0f
    val below = index.toInt()
    val between = index - below
    val a = (data[below].toInt() and 0xFF) / 255f
    val b = (data[below + 1].toInt() and 0xFF) / 255f
    return a + (b - a) * between
}

private const val WAVE_SIDE_BARS = 10
private const val WAVE_LEVELS_PER_BAR = 1.6f
private const val WAVE_HEIGHT_SHARE = 0.6f
private const val WAVE_EDGE_DROP = 0.35f
private const val WAVE_EDGE_FADE = 0.55f
private const val WAVE_CURVE = 1.6f
private const val WAVE_MUTED_ALPHA = 0.3f
private val WAVE_BAR_WIDTH = 5.dp
private val WAVE_BAR_GAP = 5.dp

private val RING_SIZE = 216.dp
private val RING_STROKE = 3.dp
private const val RING_TRACK_ALPHA = 0.28f

/**
 * The background: gold ink line art of a mosque with a Kairouan-style minaret, generated with OpenAI's
 * gpt-image-2.6-sunburst on 2026-11-15 and chosen by the owner (prompt in docs/adhan-at-prayer-time.md), 1024 × 1536.
 */
private const val BACKGROUND_WIDTH_PER_HEIGHT = 1024f / 1536f

/** Where the drawing starts in the image (the minaret's finial), from the top. */
private const val DRAWING_TOP_IN_IMAGE = 0.58f

/** The image's top share that fades into the plain paper above it. */
private const val SEAM_FADE_SHARE = 0.2f

/** On a short screen the countdown may reach down over the drawing rather than shrink. */
private const val MIN_CONTENT_SHARE = 0.55f

/** The paper of the drawing (sampled from its top: 249, 236, 212), a warm sepia ink and the drawing's antique gold. */
private val Paper = Color(0xFFF9ECD4)
private val Gold = Color(0xFFB08A47)
private val Ink = Color(0xFF3E3423)
