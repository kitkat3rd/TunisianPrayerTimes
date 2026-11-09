package com.tunisianprayertimes.adhan

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.AppFontFamily
import com.tunisianprayertimes.ui.PrayerSilencePalette
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme

/** «الأذان»: which prayers have their adhan played at their time, and in which voice. */
class AdhanSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    LifecycleResumeEffect(Unit) {
        // Back from the system's settings: the permissions may have changed, and so may the alarms they allow.
        exactAllowed = canScheduleExact(context)
        notificationsAllowed = notificationsEnabled(context)
        AdhanScheduler.refreshLater(context)
        onPauseOrDispose { }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = notificationsEnabled(context)
    }
    fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openSettings(context, Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        }
    }
    val anyEnabled = enabled.values.any { it }

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
            if (anyEnabled && !exactAllowed) {
                PermissionNotice(
                    text = stringResource(R.string.adhan_exact_alarms_off),
                    action = stringResource(R.string.adhan_allow),
                    onAction = { openSettings(context, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) },
                )
            }
            if (anyEnabled && !notificationsAllowed) {
                PermissionNotice(
                    text = stringResource(R.string.adhan_notifications_off),
                    action = stringResource(R.string.adhan_allow),
                    // The system's page works even after the permission was refused for good.
                    onAction = { openSettings(context, Settings.ACTION_APP_NOTIFICATION_SETTINGS) },
                )
            }
            AdhanSettings.prayers.forEach { prayer ->
                AdhanPrayerRow(
                    prayer = prayer,
                    enabled = enabled.getValue(prayer),
                    voice = voices.getValue(prayer),
                    onEnabledChange = { on ->
                        AdhanSettings.setEnabled(context, prayer, on)
                        enabled = enabled + (prayer to on)
                        if (on && !notificationsEnabled(context)) askForNotifications()
                    },
                    onChooseVoice = { choosingFor = prayer },
                )
                HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
            }
            Spacer(Modifier.height(16.dp))
            listOf(R.string.adhan_note_quiet_phone, R.string.adhan_note_silence, R.string.adhan_note_stop).forEach { note ->
                Text(
                    text = stringResource(note),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = PrayerSilencePalette.SecondaryText,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
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

/** A listen to one voice in the settings, at the media volume the user is adjusting right now. */
private class AdhanPreview(private val context: Context) {
    private var player: MediaPlayer? = null

    fun play(voice: AdhanVoice, onEnd: () -> Unit): Boolean {
        stop()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val created = MediaPlayer.create(context, voice.rawResId, attributes, 0) ?: return false
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

private fun notificationsEnabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun openSettings(context: Context, action: String) {
    val intent = Intent(action).apply {
        when (action) {
            Settings.ACTION_APP_NOTIFICATION_SETTINGS -> putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            else -> data = Uri.parse("package:${context.packageName}")
        }
    }
    runCatching { context.startActivity(intent) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
}
