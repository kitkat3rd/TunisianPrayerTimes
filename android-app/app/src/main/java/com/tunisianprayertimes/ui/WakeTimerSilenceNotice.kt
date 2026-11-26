package com.tunisianprayertimes.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ScheduleRefreshCoordinator
import com.tunisianprayertimes.SilenceAccessSettings
import com.tunisianprayertimes.mosque.SilenceOwner
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shown only for a selected silence-until-timer option. Returning from Settings never starts silence. */
@Composable
internal fun WakeTimerSilenceNotice(savedTimer: PrayerWakeConfig? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var applying by remember { mutableStateOf(false) }
    LifecycleResumeEffect(savedTimer?.id) {
        revision++
        val stop = savedTimer?.let { WakeAlarmScheduler.observeTimerSilence(context) { revision++ } }
        onPauseOrDispose { stop?.invoke() }
    }
    val issue = remember(revision) { WakeAlarmScheduler.timerSilenceIssue(context) }
    val pending = savedTimer != null && remember(revision, savedTimer.id) { WakeAlarmScheduler.isSilencedAlarm(context, savedTimer.id) }
    val active = pending && WakeAlarmScheduler.isSilenceUntilAlarmActive(context)
    val paused = pending && WakeAlarmScheduler.isTimerSilencePaused(context)
    val exact = remember(revision) { WakeAlarmScheduler.canScheduleExactAlarms(context) }
    if (issue == null && savedTimer == null) return
    val message = when {
        issue == WakeAlarmScheduler.TimerSilenceIssue.DND -> R.string.wake_timer_silence_needs_dnd
        issue == WakeAlarmScheduler.TimerSilenceIssue.RULE -> R.string.wake_timer_silence_needs_rule
        active -> R.string.wake_timer_silence_active
        paused -> R.string.wake_timer_silence_paused
        !exact -> R.string.wake_timer_silence_needs_ring
        else -> R.string.wake_timer_silence_unapplied
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(stringResource(message))
        when (issue) {
            WakeAlarmScheduler.TimerSilenceIssue.DND -> TextButton(onClick = { SilenceAccessSettings.openDnd(context) }) {
                Text(stringResource(R.string.mosque_allow_dnd))
            }
            WakeAlarmScheduler.TimerSilenceIssue.RULE -> TextButton(onClick = { SilenceAccessSettings.openRules(context, SilenceOwner.WAKE) }) {
                Text(stringResource(R.string.mosque_prepare_rules))
            }
            null -> if (savedTimer != null && !active && !paused && exact) {
                TextButton(enabled = !applying, onClick = {
                    applying = true
                    scope.launch {
                        try {
                            val applied = withContext(NonCancellable + ScheduleRefreshCoordinator.scheduling) {
                                ScheduleRefreshCoordinator.syncWake(context)
                                WakeAlarmScheduler.applyEditedTimerSilence(context, savedTimer)
                            }
                            if (!applied) Toast.makeText(context, R.string.wake_timer_silence_unapplied, Toast.LENGTH_LONG).show()
                        } finally {
                            revision++
                            applying = false
                        }
                    }
                }) { Text(stringResource(R.string.wake_timer_silence_start)) }
            }
        }
    }
}
