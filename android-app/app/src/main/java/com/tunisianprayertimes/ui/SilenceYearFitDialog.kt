package com.tunisianprayertimes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import android.content.res.Resources
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.silence.SilenceFit
import com.tunisianprayertimes.silence.SilenceMisfit
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * D16's proposal (plan 6.9): the window does not fit every day of the coming year at the place. It says on how many
 * days and the first of them, and offers what fits them all: an end at the window's offset from the adhan, or a later
 * fixed end. Keeping the choice is allowed: on those days the end moves later by at most 30 minutes, or keeps its
 * offset from the adhan when even that comes before the start.
 */
@Composable
internal fun SilenceYearFitDialog(
    fit: SilenceFit,
    anchor: PrayerTimelineAnchor,
    onAdhanRelative: (() -> Unit)?,
    onLaterFixedEnd: (() -> Unit)?,
    onKeep: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Plain resource reads, not composables, so the branches below add no group (one-branch composables crashed once).
    val resources = LocalContext.current.resources
    val (body, keepNote) = yearFitText(resources, fit, anchor)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.silence_year_fit_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body, fontSize = 14.sp, lineHeight = 20.sp)
                Text(keepNote, color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp)
                fit.adhanRelativeEnd?.let { offset ->
                    onAdhanRelative?.let { apply ->
                        TextButton(onClick = apply) {
                            val endLabel = if (offset == 0) {
                                stringResource(R.string.silence_year_fit_use_adhan_end_at)
                            } else {
                                pluralStringResource(
                                    if (offset < 0) R.plurals.silence_year_fit_use_adhan_end_before
                                    else R.plurals.silence_year_fit_use_adhan_end_after,
                                    abs(offset),
                                    abs(offset),
                                )
                            }
                            Text(endLabel, color = GreenPrimaryDark, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                fit.laterFixedEnd?.let { time ->
                    onLaterFixedEnd?.let { apply ->
                        TextButton(onClick = apply) {
                            Text(stringResource(R.string.silence_year_fit_use_fixed_end, shownClock(anchor, time)), color = GreenPrimaryDark, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.silence_year_fit_keep), color = GreenPrimaryDark) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.prayer_editor_cancel), color = GreenPrimaryDark) } },
    )
}

/**
 * The popup's body and its note on keeping the choice. When one end follows the adhan and the other is a clock time,
 * both say the user's own times; otherwise the general text.
 */
internal fun yearFitText(resources: Resources, fit: SilenceFit, anchor: PrayerTimelineAnchor): Pair<String, String> {
    val days = fit.notFitting.size
    val first = fit.notFitting.firstOrNull()?.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ar-TN-u-nu-latn"))).orEmpty()
    val inDays = resources.getQuantityString(R.plurals.silence_year_fit_in_days, days, days)
    fun rule(minutes: Int, clock: String, atAdhan: Int, before: Int, after: Int): String = when {
        minutes == 0 -> resources.getString(atAdhan, clock)
        minutes < 0 -> resources.getQuantityString(before, -minutes, -minutes, clock)
        else -> resources.getQuantityString(after, minutes, minutes, clock)
    }
    return when (val misfit = fit.misfit) {
        is SilenceMisfit.StartReachesFixedEnd -> {
            val end = shownClock(anchor, misfit.end)
            val body = rule(
                misfit.startMinutes, end,
                R.string.silence_year_fit_start_at_adhan, R.plurals.silence_year_fit_start_before_adhan, R.plurals.silence_year_fit_start_after_adhan,
            ) + " " + resources.getString(R.string.silence_year_fit_start_reaches_end, end, inDays, first)
            // D16 on those days: the end 30 minutes later, then the adhan + its saved offset, which follows the start.
            val offset = misfit.savedAdhanOffsetMinutes
            val keep = if (offset != null && offset > 0 && offset > misfit.startMinutes) {
                resources.getQuantityString(R.plurals.silence_year_fit_keep_capped, offset, offset, shownClock(anchor, misfit.cappedEnd))
            } else {
                resources.getString(R.string.silence_year_fit_keep_note)
            }
            body to keep
        }
        is SilenceMisfit.EndReachesFixedStart -> {
            val start = shownClock(anchor, misfit.start)
            val body = rule(
                misfit.endMinutes, start,
                R.string.silence_year_fit_end_at_adhan, R.plurals.silence_year_fit_end_before_adhan, R.plurals.silence_year_fit_end_after_adhan,
            ) + " " + resources.getString(R.string.silence_year_fit_end_reaches_start, start, inDays, first)
            // Those days have no window at all.
            body to resources.getString(R.string.silence_year_fit_keep_no_silence)
        }
        else -> resources.getString(
            R.string.silence_year_fit_body,
            resources.getQuantityString(R.plurals.silence_year_fit_days, days, days),
            first,
        ) to resources.getString(R.string.silence_year_fit_keep_note)
    }
}

/** A clock stored in the place's zone, shown in the phone's on the anchor's day (D8). */
private fun shownClock(anchor: PrayerTimelineAnchor, time: LocalTime): String {
    val shown = ZonedDateTime.of(anchor.prayerDay, time, anchor.placeZone).withZoneSameInstant(anchor.displayZone).toLocalTime()
    return "⁦" + String.format(Locale.US, "%02d:%02d", shown.hour, shown.minute) + "⁩"
}
