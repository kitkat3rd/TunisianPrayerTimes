package com.tunisianprayertimes.mosque

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceAccessSettings
import com.tunisianprayertimes.ui.AppInfoButton
import com.tunisianprayertimes.ui.PrayerSettingsLink
import com.tunisianprayertimes.ui.gregorianDateLabel
import com.tunisianprayertimes.wake.displayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val BATTERY_GUIDANCE_REVIEWED = "battery_guidance_reviewed"
private fun noticePreferences(context: Context) = context.getSharedPreferences("mosque_presence", Context.MODE_PRIVATE)
private fun acknowledgeBatteryGuidance(context: Context) {
    noticePreferences(context).edit().putBoolean(BATTERY_GUIDANCE_REVIEWED, true).apply()
}

internal fun reviewMosqueBatterySettings(context: Context) {
    acknowledgeBatteryGuidance(context)
    SilenceAccessSettings.openAppBattery(context)
}

@Composable
internal fun rememberMosqueBatteryGuidance(eligible: Boolean): Boolean {
    if (!eligible) return false
    val app = LocalContext.current.applicationContext
    return produceState(false, app) {
        callbackFlow {
            val prefs = noticePreferences(app)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == BATTERY_GUIDANCE_REVIEWED || key == null) trySend(Unit)
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            trySend(Unit)
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }.conflate().map { !noticePreferences(app).getBoolean(BATTERY_GUIDANCE_REVIEWED, false) }
            .flowOn(Dispatchers.IO).collect { value = it }
    }.value
}

@Composable
internal fun rememberMosqueInterruption(): MosqueObservationInterruption? {
    val app = LocalContext.current.applicationContext
    return produceState<MosqueObservationInterruption?>(null, app) {
        callbackFlow {
            val unsubscribe = MosqueObservationInterruptions.observe(app) { trySend(Unit) }
            trySend(Unit)
            awaitClose { unsubscribe() }
        }.conflate().map { MosqueObservationInterruptions.pending(app) }
            .flowOn(Dispatchers.IO).collect { value = it }
    }.value
}

@Composable
internal fun MosqueInterruptionNotice(notice: MosqueObservationInterruption, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val occasion = listOfNotNull(notice.prayer?.displayName(context), notice.prayerDay?.let(::gregorianDateLabel))
        .joinToString(" · ")
    MosqueBatteryNoticeCard(
        title = stringResource(R.string.mosque_interruption_title),
        message = stringResource(if (notice.confirmedVisit) R.string.mosque_interruption_during_visit
            else R.string.mosque_interruption_during_search),
        occasion = occasion,
        modifier = modifier,
        onDismiss = {
            acknowledgeBatteryGuidance(context)
            scope.launch(Dispatchers.IO) { MosqueObservationInterruptions.acknowledge(context, notice.id) }
        },
    )
}

@Composable
internal fun MosqueBatteryGuidanceNotice(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    MosqueBatteryNoticeCard(
        title = stringResource(R.string.mosque_battery_guidance_title),
        modifier = modifier,
        onDismiss = { acknowledgeBatteryGuidance(context) },
    )
}

@Composable
internal fun MosqueBackgroundBatteryLink() {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PrayerSettingsLink(
            title = stringResource(R.string.mosque_interruption_battery_action),
            onClick = { reviewMosqueBatterySettings(context) },
            modifier = Modifier.weight(1f),
        )
        AppInfoButton(
            title = stringResource(R.string.mosque_battery_guidance_title),
            text = stringResource(R.string.mosque_battery_guidance_body),
        )
    }
}

@Composable
private fun MosqueBatteryNoticeCard(
    title: String,
    modifier: Modifier,
    message: String? = null,
    occasion: String = "",
    onDismiss: () -> Unit,
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onDismiss) {
                    Icon(
                        painterResource(R.drawable.ic_close), stringResource(R.string.mosque_interruption_dismiss),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (occasion.isNotEmpty()) Text(occasion, style = MaterialTheme.typography.labelMedium)
            if (message != null) Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
        }
        MosqueBackgroundBatteryLink()
    }
}
