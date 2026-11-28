package com.tunisianprayertimes.ui

import android.content.Context
import com.tunisianprayertimes.ManualSilenceMode
import com.tunisianprayertimes.ManualSilenceScheduler
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.AdaptiveMosqueMonitor
import com.tunisianprayertimes.mosque.MosquePrayerActions
import com.tunisianprayertimes.mosque.presence.PresenceState
import com.tunisianprayertimes.silence.SilenceWindow
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar
import java.util.Locale

internal enum class PhoneSilenceReasonSource {
    MOSQUE_WINDOW,
    AUTO_PRAYER,
    AUTO_PRAYER_UNKNOWN,
    MANUAL_UNTIL_STOPPED,
    MANUAL_UNTIL_TIME,
    MANUAL_UNTIL_PRAYER,
    WAKE_ALARM,
    EXTERNAL,
}

internal data class PhoneSilenceReason(
    val source: PhoneSilenceReasonSource,
    val prayer: Prayer? = null,
    val endsAtMillis: Long = -1L,
    val doNotDisturbActive: Boolean = false,
)

internal fun phoneStatusNoticeText(
    context: Context,
    isPhoneSilenced: Boolean,
    hasDnd: Boolean,
    silenceReason: PhoneSilenceReason?,
    showMissingDnd: Boolean = true,
    nowMillis: Long = System.currentTimeMillis(),
): String? {
    if (!hasDnd && showMissingDnd) return context.getString(R.string.phone_status_permission_short)
    if (!isPhoneSilenced) return null

    return when {
        silenceReason != null -> phoneSilenceReasonText(context, silenceReason, nowMillis)
        else -> context.getString(R.string.phone_status_silent_short)
    }
}

internal fun phoneSilenceReasonText(context: Context, reason: PhoneSilenceReason, nowMillis: Long = System.currentTimeMillis()): String {
    if ((reason.source == PhoneSilenceReasonSource.AUTO_PRAYER || reason.source == PhoneSilenceReasonSource.AUTO_PRAYER_UNKNOWN) &&
        reason.endsAtMillis > nowMillis) {
        val zone = ZoneId.systemDefault()
        val endDate = Instant.ofEpochMilli(reason.endsAtMillis).atZone(zone).toLocalDate()
        val endClock = formatTimeOfDay(reason.endsAtMillis)
        val end = if (endDate == Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()) endClock
            else context.getString(R.string.hero_silence_end_date, endClock, gregorianDateLabel(endDate))
        return if (reason.prayer == null) context.getString(R.string.hero_current_scheduled_prayers, end)
            else context.getString(R.string.hero_current_scheduled_prayer, prayerName(context, reason.prayer), end)
    }
    if (reason.source in setOf(PhoneSilenceReasonSource.MANUAL_UNTIL_STOPPED, PhoneSilenceReasonSource.MANUAL_UNTIL_PRAYER, PhoneSilenceReasonSource.MANUAL_UNTIL_TIME) &&
        MosquePrayerActions.canUndo(context)) return context.getString(
        if (ManualSilenceScheduler.canScheduleExact(context)) R.string.phone_status_silent_manual_until_time else R.string.manual_approximate_time,
        formatTimeOfDay(com.tunisianprayertimes.PrefsManager.getManualSilenceEndsAtMillis(context)))
    return when (reason.source) {
        PhoneSilenceReasonSource.MOSQUE_WINDOW -> if (reason.endsAtMillis > 0L) {
            MosquePrayerActions.continuationLabel(context, reason.endsAtMillis)
        } else {
            context.getString(when (AdaptiveMosqueMonitor.requestedState(context)) {
                PresenceState.ENTERING -> R.string.mosque_status_entering
                PresenceState.SCHEDULE_FALLBACK -> R.string.mosque_adaptive_fallback
                else -> R.string.mosque_phone_reason
            })
        }
        PhoneSilenceReasonSource.AUTO_PRAYER -> context.getString(
            R.string.phone_status_silent_auto_prayer,
            prayerName(context, requireNotNull(reason.prayer)),
        )
        PhoneSilenceReasonSource.AUTO_PRAYER_UNKNOWN -> context.getString(R.string.phone_status_silent_auto)
        PhoneSilenceReasonSource.MANUAL_UNTIL_STOPPED -> context.getString(R.string.phone_status_silent_manual)
        PhoneSilenceReasonSource.MANUAL_UNTIL_TIME -> context.getString(
            if (ManualSilenceScheduler.canScheduleExact(context)) R.string.phone_status_silent_manual_until_time else R.string.manual_approximate_time,
            formatTimeOfDay(reason.endsAtMillis),
        )
        PhoneSilenceReasonSource.MANUAL_UNTIL_PRAYER -> context.getString(
            if (ManualSilenceScheduler.canScheduleExact(context)) R.string.phone_status_silent_manual_until_prayer else R.string.manual_approximate_prayer,
            prayerName(context, requireNotNull(reason.prayer)),
        )
        PhoneSilenceReasonSource.WAKE_ALARM -> context.getString(R.string.phone_status_silent_wake_alarm)
        PhoneSilenceReasonSource.EXTERNAL -> context.getString(
            if (reason.doNotDisturbActive) R.string.phone_status_dnd_active
            else R.string.phone_status_silent_external,
        )
    }
}

/** The uninterrupted schedule covering now. An active flag alone can also be a travel bridge with no known end. */
internal fun currentScheduledSilence(windows: List<SilenceWindow>, now: Instant): PhoneSilenceReason? {
    val active = windows.filter { now in it }
    if (active.isEmpty()) return null
    var end = active.maxOf { it.end }
    val prayers = active.mapTo(mutableSetOf()) { it.prayer }
    for (window in windows.sortedBy { it.start }) {
        if (window.start > end) break
        if (window.start <= now) continue
        prayers.add(window.prayer)
        end = maxOf(end, window.end)
    }
    val prayer = prayers.singleOrNull()
    return PhoneSilenceReason(
        source = if (prayer == null) PhoneSilenceReasonSource.AUTO_PRAYER_UNKNOWN else PhoneSilenceReasonSource.AUTO_PRAYER,
        prayer = prayer, endsAtMillis = end.toEpochMilli(),
    )
}

internal fun resolvePhoneSilenceReason(
    isPhoneSilenced: Boolean,
    autoSilenceActive: Boolean,
    activeAutoSilencePrayer: Prayer?,
    manualSilenceActive: Boolean,
    manualSilenceMode: ManualSilenceMode,
    manualTargetPrayer: Prayer,
    manualSilenceEndsAtMillis: Long,
    wakeSilenceUntilAlarmActive: Boolean,
    currentTimeMillis: Long,
    combinedMode: Boolean = false,
    doNotDisturbActive: Boolean = false,
    mosqueContinuationEnd: Long? = null,
): PhoneSilenceReason? {
    if (!isPhoneSilenced) return null

    if (manualSilenceActive) {
        return when (manualSilenceMode) {
            ManualSilenceMode.UNTIL_PRAYER -> PhoneSilenceReason(
                source = PhoneSilenceReasonSource.MANUAL_UNTIL_PRAYER,
                prayer = manualTargetPrayer,
            )
            ManualSilenceMode.DURATION -> if (manualSilenceEndsAtMillis > currentTimeMillis) {
                PhoneSilenceReason(
                    source = PhoneSilenceReasonSource.MANUAL_UNTIL_TIME,
                    endsAtMillis = manualSilenceEndsAtMillis,
                )
            } else {
                PhoneSilenceReason(PhoneSilenceReasonSource.MANUAL_UNTIL_STOPPED)
            }
            ManualSilenceMode.UNTIL_STOPPED -> PhoneSilenceReason(PhoneSilenceReasonSource.MANUAL_UNTIL_STOPPED)
        }
    }

    if (autoSilenceActive) {
        if (combinedMode) {
            val cap = mosqueContinuationEnd?.takeIf { it > currentTimeMillis } ?: -1L
            return PhoneSilenceReason(PhoneSilenceReasonSource.MOSQUE_WINDOW, endsAtMillis = cap)
        }
        return if (activeAutoSilencePrayer != null) {
            PhoneSilenceReason(
                source = PhoneSilenceReasonSource.AUTO_PRAYER,
                prayer = activeAutoSilencePrayer,
            )
        } else {
            PhoneSilenceReason(PhoneSilenceReasonSource.AUTO_PRAYER_UNKNOWN)
        }
    }

    if (wakeSilenceUntilAlarmActive) {
        return PhoneSilenceReason(PhoneSilenceReasonSource.WAKE_ALARM)
    }

    return PhoneSilenceReason(
        source = PhoneSilenceReasonSource.EXTERNAL,
        doNotDisturbActive = doNotDisturbActive,
    )
}

private fun prayerName(context: Context, prayer: Prayer): String = when (prayer) {
    Prayer.FAJR -> context.getString(R.string.prayer_fajr)
    Prayer.DHUHR -> context.getString(R.string.prayer_dhuhr)
    Prayer.ASR -> context.getString(R.string.prayer_asr)
    Prayer.MAGHRIB -> context.getString(R.string.prayer_maghrib)
    Prayer.ISHA -> context.getString(R.string.prayer_isha)
    Prayer.JOMOAA -> context.getString(R.string.prayer_jomoaa)
    Prayer.AID_FITR -> context.getString(R.string.prayer_aid_fitr)
    Prayer.AID_ADHA -> context.getString(R.string.prayer_aid_adha)
}

private fun formatTimeOfDay(targetTimeInMillis: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = targetTimeInMillis }
    return String.format(
        Locale.US,
        "%02d:%02d",
        calendar.get(Calendar.HOUR_OF_DAY),
        calendar.get(Calendar.MINUTE),
    )
}
