package com.tunisianprayertimes.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import com.tunisianprayertimes.AndroidAutoConnection
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.adhkar.MushafPassages
import com.tunisianprayertimes.mosque.DisplayTexts
import com.tunisianprayertimes.mosque.MosqueReadiness
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.silence.SilenceWindow
import com.tunisianprayertimes.ui.theme.GoldLight
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A blocking grant takes priority over a location loss, for which mosque mode has a schedule fallback. */
internal fun firstPrayerSilenceIssue(issues: List<Int>?): Int? {
    if (issues.isNullOrEmpty()) return null
    return when {
        R.string.mosque_needs_dnd in issues -> R.string.mosque_needs_dnd
        R.string.mosque_needs_exact in issues -> R.string.mosque_needs_exact
        else -> issues.firstOrNull { !MosqueReadiness.locationIssue(it) } ?: issues.first()
    }
}

@Composable
internal fun NextPrayerHeroCard(
    countdown: NextPrayerCountdownInfo?,
    prayerName: String,
    currentTimeMillis: Long,
    isPhoneSilenced: Boolean,
    hasDnd: Boolean,
    silenceReason: PhoneSilenceReason?,
    autoSilenceEnabled: Boolean,
    configuredMode: AutomaticSilenceMode?,
    setupIssues: List<Int>?,
    onRepairSilence: () -> Unit,
    silenceActionPending: Boolean,
    silenceActive: Boolean,
    manualActionLabel: String,
    onChooseManualDuration: () -> Unit,
    phoneZone: ZoneId = ZoneId.systemDefault(),
    onManualSilenceClick: () -> Unit,
) {
    val context = LocalContext.current
    val clock = countdown?.let {
        prayerHeroTime(it.at, Instant.ofEpochMilli(currentTimeMillis), phoneZone, it.placeZone)
    }
    val issue = when {
        !autoSilenceEnabled -> null
        setupIssues?.contains(R.string.mosque_waiting_for_place) == true -> R.string.mosque_waiting_for_place
        !hasDnd -> R.string.mosque_needs_dnd
        configuredMode == null -> R.string.mosque_invalid_mode
        else -> firstPrayerSilenceIssue(setupIssues)
    }
    // A configured mosque mode may use its scheduled interval when location alone is unavailable.
    val locationFallback = issue != null && configuredMode == AutomaticSilenceMode.TIME_AND_MOSQUE &&
        MosqueReadiness.locationIssue(issue)
    val phoneStatus = phoneStatusNoticeText(context, isPhoneSilenced, hasDnd, silenceReason, showMissingDnd = false)
    HeroCardSurface(
        modifier = Modifier.testTag(TestTags.STATUS_CARD),
        overlay = SolidColor(if (isPhoneSilenced) Color(0x803A1F2E) else Color(0x33002422)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.next_prayer_countdown_title), color = Color.White,
                    fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.semantics { heading() })
                if (LocalDensity.current.fontScale <= 1.3f) NextPrayerHeroVerse()
            }
            Column(Modifier.fillMaxWidth().testTag("next_prayer_summary").semantics(mergeDescendants = true) { }) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(prayerName, color = Color.White, fontSize = if (clock == null) 18.sp else 29.sp,
                        lineHeight = 35.sp, fontWeight = FontWeight.Bold)
                    if (clock != null) {
                        Text(clock.phoneClock, color = GoldLight, fontSize = 28.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold)
                        if (clock.tomorrow) Text(stringResource(R.string.next_prayer_countdown_tomorrow),
                            color = Color.White, fontSize = 14.sp)
                    }
                }
                if (clock?.placeClock != null) {
                    val clarification = if (clock.phoneDate != null && clock.placeDate != null) {
                        stringResource(R.string.hero_prayer_clocks_with_dates,
                            gregorianDateLabel(clock.phoneDate), clock.placeClock, gregorianDateLabel(clock.placeDate))
                    } else stringResource(R.string.hero_prayer_clocks, clock.placeClock)
                    Text(clarification, color = Color.White, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
            if (countdown != null) {
                val remaining = formatCountdownRemaining(countdown.triggerAtMillis, currentTimeMillis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(painterResource(R.drawable.ic_adhkar_clock), null, tint = Color.White, modifier = Modifier.size(15.dp))
                    Text(stringResource(R.string.next_prayer_countdown_remaining, remaining),
                        color = Color.White, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (phoneStatus != null) {
                Text(phoneStatus, color = GoldLight, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
            }
            if (countdown != null && configuredMode != null && autoSilenceEnabled &&
                setupIssues != null && (issue == null || locationFallback)
            ) {
                PrayerSilenceForecast(countdown, prayerName, configuredMode, locationFallback, phoneZone, silenceActive)
            }
            if (issue != null) {
                val action = stringResource(if (issue == R.string.mosque_waiting_for_place)
                    R.string.prayer_data_choose_location else R.string.hero_finish_silence_setup)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.20f))
                        .clickable(role = Role.Button, onClickLabel = action, onClick = onRepairSilence)
                        .heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 5.dp)
                        .testTag("prayer_silence_repair"),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(issue), color = Color.White, fontSize = 13.sp, lineHeight = 19.sp)
                    Text(action, color = GoldLight, fontSize = 13.sp, lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)
                }
            }
            PrayerHeroSilenceControl(
                silenced = silenceActive, actionLabel = manualActionLabel,
                onClick = onManualSilenceClick, onChooseDuration = onChooseManualDuration,
                enabled = !silenceActionPending,
            )
        }
    }
}

@Composable
private fun PrayerSilenceForecast(
    countdown: NextPrayerCountdownInfo,
    prayerName: String,
    mode: AutomaticSilenceMode,
    locationFallback: Boolean,
    phoneZone: ZoneId,
    silenceActive: Boolean,
) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context, countdown.prayer) {
        val config = PrefsManager.observeConfigChanges(context, countdown.prayer) { revision++ }
        val enabled = PrefsManager.observePrayerEnabledChanges(context, countdown.prayer) { revision++ }
        onDispose { config(); enabled() }
    }
    LifecycleStartEffect(context) {
        // The host broadcasts a change signal. Read the actual projection state from its provider.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AndroidAutoConnection.ACTION_UPDATED) revision++
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(AndroidAutoConnection.ACTION_UPDATED), ContextCompat.RECEIVER_EXPORTED)
        revision++
        onStopOrDispose { context.unregisterReceiver(receiver) }
    }
    // Use the scheduler's actual interval, including Friday, midnight crossings and the phone's civil time.
    val forecast by key(countdown, revision, mode, silenceActive) {
        produceState<Pair<Int, SilenceWindow?>?>(initialValue = null) {
            value = withContext(Dispatchers.IO) {
                if (!PrefsManager.isPrayerSilenceEnabled(context, countdown.prayer) ||
                    SilenceScheduler.isDismissed(context, countdown.prayer, countdown.prayerDay)
                ) return@withContext R.string.next_prayer_silence_off_prayer to null
                if (AndroidAutoConnection.isProjected(context))
                    return@withContext R.string.next_prayer_silence_android_auto to null
                val window = SilenceScheduler.windowOf(context, countdown.prayer, countdown.prayerDay)
                    ?: return@withContext R.string.next_prayer_silence_no_window to null
                val label = if (mode == AutomaticSilenceMode.TIME_ONLY) R.string.next_prayer_silence_window
                    else R.string.next_prayer_silence_mosque_window
                label to window
            }
        }
    }
    val (label, interval) = forecast ?: return
    val message = if (interval == null) stringResource(label, prayerName) else {
        val anchor = remember(context, countdown, phoneZone) {
            val place = PrayerTimesRepository.place(context)
            PrayerTimelineAnchor(countdown.prayer, countdown.prayerDay, countdown.at, place.zone,
                place is PrayerPlace.Inm, displayZone = phoneZone)
        }
        val (start, end) = silenceWindowClocks(anchor, interval.start, interval.end)
        stringResource(if (locationFallback) R.string.next_prayer_silence_fallback_window else label, start, end)
    }
    Text(message, modifier = Modifier.testTag("prayer_silence_forecast"), color = Color.White,
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
}

@Composable
internal fun NextPrayerHeroVerse(modifier: Modifier = Modifier, color: Color = Color.White) {
    val verse = DisplayTexts.PRAYER_VERSES.first { it.id == "taha_13" }
    Text(
        text = "﴿ " + (MushafPassages.text(verse.id) ?: verse.text) + " ﴾",
        modifier = modifier.clearAndSetSemantics { contentDescription = verse.text },
        fontFamily = QuranMushafFont, fontSize = 15.sp, lineHeight = 24.sp, color = color, textAlign = TextAlign.Center,
    )
}
