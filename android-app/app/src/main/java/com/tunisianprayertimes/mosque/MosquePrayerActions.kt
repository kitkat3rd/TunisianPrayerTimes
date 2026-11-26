package com.tunisianprayertimes.mosque

import android.content.Context
import android.text.format.DateFormat
import com.tunisianprayertimes.ManualSilenceScheduler
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceModeController
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.silence.SilenceWindow
import com.tunisianprayertimes.wake.displayName
import java.util.Date

internal object MosquePrayerActions {
    fun key(w: SilenceWindow) = "${w.prayer}@${w.prayerDay}"
    fun name(c: Context, w: SilenceWindow) = "${w.prayer.displayName(c)} · ${w.prayerDay}"
    fun label(c: Context, w: SilenceWindow) = w.prayer.displayName(c) + " · " +
        DateFormat.getTimeFormat(c).format(Date(w.end.toEpochMilli()))
    fun continuationLabel(c: Context, endMillis: Long): String = c.getString(
        R.string.mosque_continuation_until, DateFormat.getTimeFormat(c).format(Date(endMillis)),
    )
    private fun prefs(c: Context) = c.getSharedPreferences("mosque_prayer_manual", Context.MODE_PRIVATE)

    fun manual(c: Context, occurrence: String) = synchronized(SilenceModeController) {
        val p = AdaptiveMosqueMonitor.periods(c)
        val w = p.targets.firstOrNull { key(it) == occurrence && SilenceScheduler.now() in it } ?: return@synchronized false
        if (MosqueReadiness.timeIssues(c).isNotEmpty() || SilenceModeController.isAutoSilencePausedForAwakeCheck) return@synchronized false
        val end = w.end.toEpochMilli()
        val alreadyManual = PrefsManager.isManualSilenceActive(c)
        val old = if (alreadyManual) PrefsManager.getManualSilenceEndsAtMillis(c) else -1L
        if (alreadyManual && (old <= 0 || old >= end)) return@synchronized false
        if (!alreadyManual && !SilenceModeController.setManualSilent(c)) return@synchronized false
        try {
            ManualSilenceScheduler.scheduleAt(c, end)
            prefs(c).edit().putLong("end", end).putLong("previous", old).commit()
        } catch (e: RuntimeException) {
            if (!alreadyManual) SilenceModeController.endManualSilenceIfActive(c)
            else if (old > System.currentTimeMillis()) ManualSilenceScheduler.scheduleAt(c, old)
            false
        }
    }

    fun invalidateUndo(c: Context) {
        if (prefs(c).contains("end")) prefs(c).edit().clear().commit()
    }

    fun canUndo(c: Context) = prefs(c).getLong("end", -2) == PrefsManager.getManualSilenceEndsAtMillis(c) &&
        PrefsManager.isManualSilenceActive(c) && PrefsManager.getManualSilenceEndsAtMillis(c) > System.currentTimeMillis()

    fun undo(c: Context) = synchronized(SilenceModeController) {
        if (!canUndo(c)) return@synchronized
        val previous = prefs(c).getLong("previous", -1)
        prefs(c).edit().clear().commit()
        if (previous > System.currentTimeMillis()) ManualSilenceScheduler.scheduleAt(c, previous)
        else SilenceModeController.endManualSilenceIfActive(c)
        Unit
    }
}
