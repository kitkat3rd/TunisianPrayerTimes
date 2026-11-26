package com.tunisianprayertimes.adhan

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tunisianprayertimes.NotificationAccess
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.AppFontFamily
import com.tunisianprayertimes.ui.PrayerPlaceRequiredDialog
import com.tunisianprayertimes.ui.PrayerSilencePalette
import com.tunisianprayertimes.ui.rememberNotificationAccessAction
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme

/** «الأذان»: which prayers have their adhan played at their time, and in which voice. */
class AdhanSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The side keys on this screen set the adhan's own volume, the one «استماع» plays at.
        volumeControlStream = AdhanPlaybackService.ADHAN_STREAM
        setContent {
            TunisianPrayerTimesTheme(fontFamily = AppFontFamily) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize(), color = PrayerSilencePalette.Surface) {
                        AdhanSettingsScreen(onBack = ::finish)
                    }
                }
            }
        }
    }
}

@Composable
private fun AdhanSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AdhanSettings.prayers.associateWith { AdhanSettings.isEnabled(context, it) }) }
    var voices by remember { mutableStateOf(AdhanSettings.prayers.associateWith { AdhanSettings.voice(context, it) }) }
    var choosingFor by remember { mutableStateOf<Prayer?>(null) }
    var exactAllowed by remember { mutableStateOf(canScheduleExact(context)) }
    var notificationsAllowed by remember { mutableStateOf(notificationsEnabled(context)) }
    var channelIssues by remember { mutableStateOf(adhanChannelIssues(context)) }
    var placeRequired by rememberSaveable { mutableStateOf(false) }
    var pendingPrayer by rememberSaveable { mutableStateOf<Prayer?>(null) }
    var pendingEnable by rememberSaveable { mutableStateOf(false) }
    var confirmEnable by rememberSaveable { mutableStateOf(false) }
    var placeAllowsDelivery by remember { mutableStateOf(PrefsManager.canUsePlaceForAutomation(context)) }
    var notificationRequested by rememberSaveable { mutableStateOf(false) }
    var volumeZero by remember { mutableStateOf(AdhanPlaybackService.volumeZero(context)) }
    var warnsBefore by remember { mutableStateOf(AdhanSettings.warnsBefore(context)) }
    var fullScreenAllowed by remember { mutableStateOf(canUseFullScreen(context)) }
    LifecycleResumeEffect(Unit) {
        // Back from the system's settings: the permissions may have changed, and so may the alarms they allow.
        exactAllowed = canScheduleExact(context)
        placeAllowsDelivery = PrefsManager.canUsePlaceForAutomation(context)
        notificationsAllowed = notificationsEnabled(context)
        channelIssues = adhanChannelIssues(context)
        fullScreenAllowed = canUseFullScreen(context)
        volumeZero = AdhanPlaybackService.volumeZero(context)
        AdhanScheduler.refreshLater(context)
        onPauseOrDispose { }
    }
    // The ring volume moves with the side keys and the system's panel while this screen shows.
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                volumeZero = AdhanPlaybackService.volumeZero(context)
            }
        }
        val ringerReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                volumeZero = AdhanPlaybackService.volumeZero(context)
            }
        }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        ContextCompat.registerReceiver(
            context, ringerReceiver, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
            runCatching { context.unregisterReceiver(ringerReceiver) }
        }
    }
    val activity = LocalActivity.current
    val repairNotifications = rememberNotificationAccessAction(entryPoint = "adhan") {
        notificationsAllowed = notificationsEnabled(context)
        channelIssues = adhanChannelIssues(context)
    }
    fun askForNotificationsOnce() {
        if (!notificationRequested && activity != null && NotificationAccess.canRequest(activity)) {
            notificationRequested = true
            repairNotifications(null)
        }
    }
    fun changeAdhan(prayer: Prayer?, on: Boolean) {
        placeAllowsDelivery = PrefsManager.canUsePlaceForAutomation(context)
        if (on && !PrefsManager.hasConfirmedPlace(context)) {
            pendingPrayer = prayer
            pendingEnable = true
            placeRequired = true
            return
        }
        if (prayer == null) {
            AdhanSettings.setAllEnabled(context, on)
            enabled = AdhanSettings.prayers.associateWith { on }
        } else {
            AdhanSettings.setEnabled(context, prayer, on)
            enabled = enabled + (prayer to on)
        }
        if (on) askForNotificationsOnce()
    }
    val anyEnabled = enabled.values.any { it }
    val configured = anyEnabled && placeAllowsDelivery

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_adhkar_back),
                    contentDescription = stringResource(R.string.mosque_back),
                    tint = PrayerSilencePalette.PrimaryText,
                )
            }
            Text(
                text = stringResource(R.string.adhan_settings_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerSilencePalette.PrimaryText,
            )
        }
        Column(Modifier.padding(start = 12.dp)) {
            val allEnabled = enabled.values.all { it }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .toggleable(value = allEnabled, role = Role.Switch) { on -> changeAdhan(null, on) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.adhan_all_prayers),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = allEnabled,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = GreenPrimary),
                )
            }
            if (anyEnabled) {
                HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .toggleable(value = warnsBefore, role = Role.Switch) { on ->
                            AdhanSettings.setWarnsBefore(context, on)
                            warnsBefore = on
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.adhan_warn_before),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDark,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = warnsBefore,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = GreenPrimary),
                    )
                }
            }
            HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
            if (anyEnabled && !placeAllowsDelivery) {
                PermissionNotice(
                    text = stringResource(R.string.adhan_waiting_for_place),
                    action = stringResource(R.string.prayer_data_choose_location),
                    onAction = { pendingEnable = false; placeRequired = true },
                )
            }
            if (configured && volumeZero) {
                PermissionNotice(
                    text = stringResource(R.string.adhan_volume_zero),
                    action = stringResource(R.string.adhan_set_volume),
                    onAction = { showVolumePanel(context) },
                )
            }
            if (configured && !notificationsAllowed) {
                PermissionNotice(
                    text = stringResource(R.string.adhan_notifications_off),
                    action = stringResource(R.string.adhan_allow),
                    onAction = { repairNotifications(null) },
                )
            } else if (configured) {
                channelIssues.filter { (channel, _) -> warnsBefore || channel.id != AdhanPlaybackService.CHANNEL_SOON }.forEach { (channel, issue) ->
                    PermissionNotice(
                        text = stringResource(
                            if (issue == NotificationAccess.Issue.LOW_IMPORTANCE) R.string.adhan_low_full_screen_channel
                            else R.string.adhan_channel_off, channel.name,
                        ),
                        action = stringResource(R.string.adhan_open_channel),
                        onAction = { repairNotifications(channel.id) },
                    )
                }
            }
            if (configured && !exactAllowed) {
                val timing = stringResource(R.string.adhan_exact_alarms_off)
                PermissionNotice(
                    text = if (warnsBefore) timing + " " + stringResource(R.string.adhan_warning_needs_exact) else timing,
                    action = stringResource(R.string.adhan_allow),
                    onAction = { openSettings(context, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) },
                )
            }
            if (configured && !fullScreenAllowed) {
                PermissionNotice(
                    text = stringResource(R.string.adhan_full_screen_off),
                    action = stringResource(R.string.adhan_allow),
                    onAction = { openSettings(context, FULL_SCREEN_SETTINGS) },
                )
            }
            AdhanSettings.prayers.forEach { prayer ->
                AdhanPrayerRow(
                    prayer = prayer,
                    enabled = enabled.getValue(prayer),
                    voice = voices.getValue(prayer),
                    onEnabledChange = { on -> changeAdhan(prayer, on) },
                    onChooseVoice = { choosingFor = prayer },
                )
                HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
            }
        }
    }

    if (placeRequired) {
        val resumeEnable = pendingEnable
        PrayerPlaceRequiredDialog(onPlaceConfirmed = {
            placeAllowsDelivery = PrefsManager.canUsePlaceForAutomation(context)
            confirmEnable = resumeEnable
        }) {
            placeRequired = false
            pendingEnable = false
        }
    }
    if (confirmEnable) {
        val prayer = pendingPrayer
        val title = if (prayer == null) stringResource(R.string.adhan_enable_all_question)
            else stringResource(R.string.adhan_enable_prayer_question, AdhanSettings.prayerName(context, prayer))
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text(title) },
            confirmButton = {
                TextButton(onClick = { confirmEnable = false; changeAdhan(prayer, true) }) {
                    Text(stringResource(R.string.adhan_enable_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnable = false }) { Text(stringResource(R.string.jomoaa_time_cancel)) }
            },
        )
    }
    choosingFor?.let { prayer ->
        AdhanVoiceDialog(
            prayer = prayer,
            selected = voices.getValue(prayer),
            onSelect = { voice ->
                AdhanSettings.setVoice(context, prayer, voice)
                voices = voices + (prayer to voice)
            },
            onDismiss = { choosingFor = null },
        )
    }
}

@Composable
private fun AdhanPrayerRow(
    prayer: Prayer,
    enabled: Boolean,
    voice: AdhanVoice,
    onEnabledChange: (Boolean) -> Unit,
    onChooseVoice: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onEnabledChange),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = AdhanSettings.prayerName(context, prayer),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark,
                )
                if (prayer == Prayer.JOMOAA) {
                    Text(
                        text = stringResource(R.string.adhan_jumua_hint),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = PrayerSilencePalette.SecondaryText,
                    )
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = GreenPrimary),
            )
        }
        if (enabled) {
            Row(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .background(PrayerSilencePalette.TintedStrip, RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onChooseVoice)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = AdhanSettings.voiceTitle(context, voice),
                    fontSize = 13.sp,
                    color = PrayerSilencePalette.InteractiveTeal,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_qibla_chevron),
                    contentDescription = null,
                    tint = PrayerSilencePalette.InteractiveTeal,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun PermissionNotice(text: String, action: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(PrayerSilencePalette.TintedStrip, RoundedCornerShape(14.dp))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = PrayerSilencePalette.PrimaryText,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAction) { Text(action, color = GreenPrimary) }
    }
}

/** The voices of one prayer's adhan, each with a short listen. */
@Composable
private fun AdhanVoiceDialog(
    prayer: Prayer,
    selected: AdhanVoice,
    onSelect: (AdhanVoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val preview = remember { AdhanPreview(context) }
    var listening by remember { mutableStateOf<AdhanVoice?>(null) }
    DisposableEffect(Unit) { onDispose { preview.stop() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PrayerSilencePalette.Surface,
        title = {
            Text(
                text = stringResource(R.string.adhan_voice_title, AdhanSettings.prayerName(context, prayer)),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerSilencePalette.PrimaryText,
            )
        },
        text = {
            Column {
                AdhanSettings.voices(prayer).forEach { voice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .selectable(selected = voice == selected, role = Role.RadioButton) { onSelect(voice) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(
                            selected = voice == selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = GreenPrimary),
                        )
                        Text(
                            text = AdhanSettings.voiceTitle(context, voice),
                            fontSize = 14.sp,
                            color = TextDark,
                            modifier = Modifier.weight(1f),
                        )
                        val isListening = listening == voice
                        IconButton(onClick = {
                            if (isListening) {
                                preview.stop()
                                listening = null
                            } else {
                                listening = if (preview.play(voice) { listening = null }) voice else null
                            }
                        }) {
                            Icon(
                                painter = painterResource(if (isListening) R.drawable.ic_stop else R.drawable.ic_play_arrow),
                                contentDescription = stringResource(if (isListening) R.string.adhan_voice_stop_listening else R.string.adhan_voice_listen),
                                tint = PrayerSilencePalette.InteractiveTeal,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.adhan_close), color = GreenPrimary) }
        },
    )
}

/**
 * A listen to one voice in the settings, at the adhan's own volume and through the same rules, so what is heard here
 * is what the prayer time will sound like. It used to play at the media volume, which hid a ring or notification
 * volume at zero: the voice sounded here and the real adhan was silent.
 */
private class AdhanPreview(private val context: Context) {
    private var player: MediaPlayer? = null

    fun play(voice: AdhanVoice, onEnd: () -> Unit): Boolean {
        stop()
        val created = MediaPlayer.create(context, voice.rawResId, AdhanPlaybackService.audioAttributes(), 0) ?: return false
        created.setOnCompletionListener {
            stop()
            onEnd()
        }
        created.start()
        player = created
        return true
    }

    fun stop() {
        player?.let { playing ->
            runCatching { if (playing.isPlaying) playing.stop() }
            runCatching { playing.release() }
        }
        player = null
    }
}

private fun canScheduleExact(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

private fun notificationsEnabled(context: Context): Boolean = NotificationAccess.issue(context, null) == null

private fun adhanChannelIssues(context: Context): List<Pair<NotificationChannel, NotificationAccess.Issue>> {
    val manager = context.getSystemService(NotificationManager::class.java)
    return listOf(AdhanPlaybackService.CHANNEL_PLAYING, AdhanPlaybackService.CHANNEL_QUIET, AdhanPlaybackService.CHANNEL_SOON)
        .mapNotNull(manager::getNotificationChannel)
        .mapNotNull { channel ->
            // Only the playing and advance-warning channels carry full-screen intents.
            val importance = if (channel.id == AdhanPlaybackService.CHANNEL_QUIET) NotificationManager.IMPORTANCE_MIN
                else NotificationManager.IMPORTANCE_HIGH
            NotificationAccess.issue(context, channel.id, importance)?.let { issue -> channel to issue }
        }
}

/** The countdown and prayer-time page need it from Android 14; before, every app has it. */
private fun canUseFullScreen(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

/** Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, which takes the package as its data. */
private const val FULL_SCREEN_SETTINGS = "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT"

/** The system's volume slider for the adhan's stream, changing nothing by itself; the sound settings if refused. */
private fun showVolumePanel(context: Context) {
    runCatching {
        context.getSystemService(AudioManager::class.java)
            .adjustStreamVolume(AdhanPlaybackService.ADHAN_STREAM, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
    }.onFailure { openSettings(context, Settings.ACTION_SOUND_SETTINGS) }
}

private fun openSettings(context: Context, action: String) {
    val intent = Intent(action).apply {
        if (action != Settings.ACTION_SOUND_SETTINGS) data = Uri.fromParts("package", context.packageName, null)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
}
