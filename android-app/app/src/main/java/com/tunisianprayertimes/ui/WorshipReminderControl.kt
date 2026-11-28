package com.tunisianprayertimes.ui

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.TextMuted
import com.tunisianprayertimes.worship.WorshipReminder
import com.tunisianprayertimes.worship.WorshipTiming
import com.tunisianprayertimes.worship.VoluntaryPrayerWindow
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WorshipReminderControl(
    reminder: WorshipReminder, window: VoluntaryPrayerWindow?, now: Instant, displayZone: ZoneId, lastThird: Instant? = null,
    header: @Composable (button: @Composable () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var timing by remember { mutableStateOf(reminder.timing(context)) }
    var editor by rememberSaveable { mutableStateOf(false) }
    var pendingPlace by rememberSaveable { mutableStateOf<WorshipTiming?>(null) }
    var saving by remember { mutableStateOf(false) }
    val access = rememberReminderNotificationAccess(listOf(reminder.channelId)) {
        if (reminder.timing(context) != null) scope.launch(Dispatchers.IO) {
            runCatching { reminder.refresh(context) }.onFailure { Log.w("WorshipReminder", "Could not refresh notification access", it) }
        }
    }
    fun choose(choice: WorshipTiming?) {
        if (saving) return
        if (choice != null && !PrefsManager.hasConfirmedPlace(context)) {
            pendingPlace = choice
            return
        }
        saving = true
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { reminder.setTiming(context, choice) }
                if (saved) {
                    timing = choice
                    editor = false
                    if (choice != null && access.action == ReminderNotificationAction.Request) access.perform()
                } else {
                    pendingPlace = choice
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                timing = reminder.timing(context)
                Toast.makeText(context, R.string.worship_reminder_failed, Toast.LENGTH_LONG).show()
            } finally {
                saving = false
            }
        }
    }

    val title = stringResource(reminder.title)
    val label = stringResource(when {
        timing == null -> R.string.worship_reminder_action
        reminder == WorshipReminder.QIYAM -> R.string.qiyam_reminder_enabled
        else -> R.string.duha_reminder_daily
    })
    header {
        TextButton(onClick = { editor = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("${reminder.channelId}_action")
            .semantics { contentDescription = "$title، $label" }) {
            Icon(painterResource(R.drawable.ic_adhkar_bell), null, Modifier.size(17.dp))
            Text(label, Modifier.padding(start = 5.dp), fontSize = 14.sp)
        }
    }
    if (timing != null) ReminderNotificationsWarning(access, title)

    val awaitingPlace = pendingPlace
    if (awaitingPlace != null) {
        PrayerPlaceRequiredDialog(onDismiss = { pendingPlace = null }, onPlaceConfirmed = { choose(awaitingPlace) })
    } else if (editor) {
        AlertDialog(
            onDismissRequest = { editor = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(reminder.editorTitle)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup().testTag("${reminder.channelId}_choices")) {
                    (reminder.options + listOf(null)).forEach { option ->
                        val time = if (option != null && window != null) option.at(window, lastThird) else null
                        Row(Modifier.fillMaxWidth().selectable(selected = timing == option, enabled = !saving,
                            role = Role.RadioButton, onClick = { choose(option) }).heightIn(min = 48.dp).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            RadioButton(selected = timing == option, onClick = null)
                            Column {
                                Text(stringResource(option?.label ?: R.string.worship_reminder_off))
                                val detail = when {
                                    time == null && window != null && option != null -> stringResource(R.string.qiyam_reminder_short_night)
                                    time == null -> null
                                    time >= now -> worshipClockFormat.format(time.atZone(displayZone))
                                    reminder == WorshipReminder.QIYAM -> stringResource(R.string.qiyam_reminder_next_night)
                                    else -> stringResource(R.string.duha_reminder_next_day)
                                }
                                if (detail != null) Text(detail, color = TextMuted, fontSize = 13.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editor = false }) { Text(stringResource(R.string.worship_times_close)) }
            },
        )
    }
}
