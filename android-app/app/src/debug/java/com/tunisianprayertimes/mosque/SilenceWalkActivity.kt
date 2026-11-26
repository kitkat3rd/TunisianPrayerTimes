package com.tunisianprayertimes.mosque

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tunisianprayertimes.NotificationAccess
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.trace.SilenceTraceStore
import com.tunisianprayertimes.mosque.trace.SilenceWalkRecorderService
import com.tunisianprayertimes.mosque.trace.TraceEntry
import com.tunisianprayertimes.mosque.trace.TraceSession
import com.tunisianprayertimes.ui.AppFontFamily
import com.tunisianprayertimes.ui.PrayerSilencePalette
import com.tunisianprayertimes.ui.rememberNotificationAccessAction
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class SilenceWalkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TunisianPrayerTimesTheme(fontFamily = AppFontFamily) {
                Surface(Modifier.fillMaxSize(), color = PrayerSilencePalette.Surface) {
                    SilenceWalkScreen(onBack = ::finish)
                }
            }
        }
    }
}

private data class WalkView(
    val sessions: List<TraceSession>,
    val selected: TraceSession?,
    val entries: List<TraceEntry>,
)

@Composable
private fun SilenceWalkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var exportId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf<Long?>(null) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showPermissionHelp by rememberSaveable { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var readFailed by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<WalkView?>(null) }
    LaunchedEffect(selectedId, lifecycle, revision) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            SilenceTraceStore.changes.collect {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val sessions = SilenceTraceStore.sessions(context)
                        val selected = sessions.find { it.id == selectedId } ?: sessions.firstOrNull()
                        WalkView(sessions, selected, selected?.let { SilenceTraceStore.entries(context, it.id) }.orEmpty())
                    }
                }
                readFailed = result.isFailure
                view = result.getOrNull()
            }
        }
    }

    fun startRecording() {
        showPermissionHelp = false
        selectedId = null
        if (!SilenceWalkRecorderService.start(context)) context.walkMessage(R.string.silence_walk_start_failed)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (SilenceWalkRecorderService.hasLocationPermission(context)) startRecording() else showPermissionHelp = true
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        val id = exportId
        exportId = null
        if (uri != null && id != null) scope.launch {
            // Finish the user's chosen write even if rotating or leaving this screen.
            val app = context.applicationContext
            withContext(NonCancellable) {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val session = SilenceTraceStore.sessions(app).first { it.id == id && it.endedAt != null }
                        val entries = SilenceTraceStore.entries(app, id)
                        checkNotNull(app.contentResolver.openOutputStream(uri, "wt")).use {
                            SilenceWalkExport.writeHtml(app, session, entries, it)
                        }
                    }.isSuccess
                }
                app.walkMessage(if (saved) R.string.silence_walk_saved else R.string.mosque_save_failed)
            }
        }
    }
    val selected = view?.selected
    val active = view?.sessions?.firstOrNull { it.endedAt == null }
    val dates = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.forLanguageTag("ar-TN")) }
    fun startedAt(session: TraceSession): String =
        dates.format(Instant.ofEpochMilli(session.startedAt).atZone(ZoneId.of(session.timeZoneId)))
    val allowNotifications = rememberNotificationAccessAction { revision++ }
    fun mark(sessionId: Long, kind: String, detail: Int) {
        val at = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                SilenceTraceStore.append(context, kind, context.getString(detail), at = at,
                    sessionId = sessionId, elapsedMillis = elapsed)
            }
            context.walkMessage(if (saved) R.string.silence_walk_marked else R.string.mosque_save_failed)
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_adhkar_back), stringResource(R.string.mosque_back))
            }
            Text(stringResource(R.string.silence_walk_title), Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = PrayerSilencePalette.PrimaryText)
        }
        HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (readFailed) {
                Text(stringResource(R.string.silence_walk_read_failed))
                OutlinedButton(onClick = { revision++ }) { Text(stringResource(R.string.silence_walk_retry)) }
            } else if (view == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (active == null) {
                Text(stringResource(R.string.silence_walk_intro))
                Button(onClick = {
                    if (SilenceWalkRecorderService.hasLocationPermission(context)) startRecording()
                    else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.silence_walk_start)) }
                if (showPermissionHelp) {
                    Text(stringResource(R.string.silence_walk_permission))
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }) { Text(stringResource(R.string.silence_walk_settings)) }
                }
            } else {
                Text(stringResource(R.string.silence_walk_active_since, startedAt(active)),
                    style = MaterialTheme.typography.titleMedium, color = PrayerSilencePalette.PrimaryText)
                Text(stringResource(R.string.silence_walk_active_help), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { SilenceWalkRecorderService.stop(context, active.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.silence_walk_stop))
                }
                if (NotificationAccess.issue(context, SilenceWalkRecorderService.CHANNEL) != null) {
                    TextButton(onClick = { allowNotifications(SilenceWalkRecorderService.CHANNEL) }) {
                        Text(stringResource(R.string.silence_walk_show_notification))
                    }
                }
                Text(stringResource(R.string.silence_walk_mark_hint), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { mark(active.id, "test_arrived", R.string.silence_walk_arrived_event) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.silence_walk_arrived))
                    }
                    OutlinedButton(onClick = { mark(active.id, "test_left", R.string.silence_walk_left_event) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.silence_walk_left))
                    }
                }
            }
            if (selected != null) {
                HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
                Text(stringResource(R.string.silence_walk_recorded_at, startedAt(selected)),
                    Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                    color = PrayerSilencePalette.PrimaryText)
                if ((view?.sessions?.size ?: 0) > 1) {
                    TextButton(onClick = { showHistory = true }) { Text(stringResource(R.string.silence_walk_history)) }
                }
                if (selected.endedAt != null) {
                    Text(stringResource(R.string.silence_walk_export_info), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = {
                        exportId = selected.id
                        export.launch("silence-walk-${selected.startedAt}.html")
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.silence_walk_save)) }
                }
                key(selected.id) { SilenceTraceReplay(selected, view?.entries.orEmpty()) }
                if (selected.endedAt != null) {
                    TextButton(onClick = { confirmDelete = selected.id }) { Text(stringResource(R.string.silence_walk_delete)) }
                }
            }
        }
    }
    if (showHistory) AlertDialog(
        onDismissRequest = { showHistory = false }, title = { Text(stringResource(R.string.silence_walk_history)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(view?.sessions.orEmpty(), key = { it.id }) { session ->
                    TextButton(onClick = { selectedId = session.id; showHistory = false }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (session.endedAt == null) R.string.silence_walk_active_since else R.string.silence_walk_recorded_at,
                            startedAt(session)))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showHistory = false }) { Text(stringResource(R.string.mosque_back)) } },
    )
    val deleting = view?.sessions?.find { it.id == confirmDelete && it.endedAt != null }
    if (deleting != null) AlertDialog(
        onDismissRequest = { confirmDelete = null },
        title = { Text(stringResource(R.string.silence_walk_delete)) },
        text = { Text(stringResource(R.string.silence_walk_delete_text, startedAt(deleting))) },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = null
                scope.launch {
                    val removed = withContext(Dispatchers.IO) { runCatching { SilenceTraceStore.delete(context, deleting.id) }.isSuccess }
                    if (!removed) context.walkMessage(R.string.mosque_save_failed)
                }
            }) { Text(stringResource(R.string.silence_walk_delete)) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.mosque_cancel)) } },
    )
}

private fun Context.walkMessage(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
