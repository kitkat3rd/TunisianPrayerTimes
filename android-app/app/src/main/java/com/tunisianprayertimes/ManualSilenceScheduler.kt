package com.tunisianprayertimes

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object ManualSilenceScheduler {
    const val ACTION_MANUAL_UNSILENCE = "com.tunisianprayertimes.ACTION_MANUAL_UNSILENCE"

    private const val TAG = "ManualSilenceScheduler"
    private const val REQUEST_CODE_MANUAL_UNSILENCE = 10_001
    private const val REQUEST_CODE_INEXACT_END = 10_002

    /** A rejected silence leaves the existing deadline and selected mode intact. */
    internal fun start(
        context: Context,
        mode: ManualSilenceMode,
        durationMinutes: Int = 0,
        prayerOccurrence: PrayerTimesRepository.PrayerOccurrence? = null,
    ): Long? = synchronized(SilenceModeController) {
        val deadline = when (mode) {
            ManualSilenceMode.DURATION -> {
                if (durationMinutes <= 0) return@synchronized null
                System.currentTimeMillis() + durationMinutes * 60_000L
            }
            ManualSilenceMode.UNTIL_PRAYER -> prayerOccurrence?.at?.toEpochMilli()?.takeIf { it > System.currentTimeMillis() }
                ?: return@synchronized null
            ManualSilenceMode.UNTIL_STOPPED -> -1L
        }
        val alreadyManual = PrefsManager.isManualSilenceActive(context)
        val previousDeadline = PrefsManager.getManualSilenceEndsAtMillis(context)
        if (!SilenceModeController.setManualSilent(context)) return@synchronized null
        try {
            if (deadline > 0L) scheduleAt(context, deadline) else cancel(context)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not update the manual silence deadline", error)
            if (!alreadyManual) {
                SilenceModeController.endManualSilenceIfActive(context)
            } else if (previousDeadline > 0L) {
                // A replacement backup may already have reached Android before the exact call failed.
                PrefsManager.setManualSilenceEndsAtMillis(context, previousDeadline)
                runCatching { armAt(context, previousDeadline) }
                    .onFailure { Log.w(TAG, "Could not rearm the previous manual silence deadline", it) }
            }
            return@synchronized null
        }
        PrefsManager.setManualSilenceMode(context, mode, prayerOccurrence?.prayer)
        deadline
    }

    fun schedule(context: Context, durationMinutes: Int): Long {
        val safeMinutes = durationMinutes.coerceAtLeast(1)
        val endsAtMillis = System.currentTimeMillis() + safeMinutes * 60_000L
        scheduleAt(context, endsAtMillis)
        return endsAtMillis
    }

    fun scheduleAt(context: Context, endsAtMillis: Long) = synchronized(SilenceModeController) {
        armAt(context, endsAtMillis)
        com.tunisianprayertimes.mosque.MosquePrayerActions.invalidateUndo(context)
    }

    private fun armAt(context: Context, endsAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exact = createPendingIntent(context, REQUEST_CODE_MANUAL_UNSILENCE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android cancels an app's exact alarms on revocation. The independent inexact alarm
            // can still return sound while the app is closed; the first actual expiry cancels both.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAtMillis, createPendingIntent(context, REQUEST_CODE_INEXACT_END))
        }
        try {
            if (canScheduleExact(context)) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAtMillis, exact)
            else alarmManager.cancel(exact)
        } catch (denied: SecurityException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) throw denied
        }
        // Reusing the same PendingIntent replaces the old alarm; publish its deadline after scheduling succeeds.
        PrefsManager.setManualSilenceEndsAtMillis(context, endsAtMillis)
        Log.d(TAG, "Scheduled manual unsilence at $endsAtMillis")
    }

    fun cancel(context: Context) = synchronized(SilenceModeController) {
        com.tunisianprayertimes.mosque.MosquePrayerActions.invalidateUndo(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(createPendingIntent(context, REQUEST_CODE_MANUAL_UNSILENCE))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.cancel(createPendingIntent(context, REQUEST_CODE_INEXACT_END))
        PrefsManager.clearManualSilenceEndsAt(context)
    }

    fun syncExpiredTimer(context: Context): Boolean = reconcileDeadline(context, rearm = false)

    /**
     * A reboot or a force-stop clears the end alarm of a timed manual silence, while the phone
     * stays silenced and the end time stays saved. Ends the silence if that time has passed, or
     * re-arms the alarm if it has not; re-arming replaces the alarm if it is still set.
     */
    fun restoreTimer(context: Context) {
        reconcileDeadline(context, rearm = true)
    }

    private fun reconcileDeadline(context: Context, rearm: Boolean): Boolean {
        synchronized(SilenceModeController) {
            val end = PrefsManager.getManualSilenceEndsAtMillis(context)
            if (end <= 0L || !PrefsManager.isManualSilenceActive(context)) return false
            if (System.currentTimeMillis() < end) {
                if (rearm) armAt(context, end)
                return false
            }
            if (!SilenceModeController.endManualSilenceIfActive(context)) return false
            // A duplicate or already-dispatched older broadcast must not consume a newer session's feedback.
            SilenceModeController.notifyIfMissedCallDuringSilence(context)
        }
        Log.d(TAG, "Manual silence timer expired")
        // scheduleAll takes the scheduler lock before the controller lock.
        resumeAutoSilenceIfPossible(context)
        return true
    }

    private fun resumeAutoSilenceIfPossible(context: Context) {
        if (!PrefsManager.isEnabled(context) || !canScheduleExact(context)) {
            return
        }

        SilenceScheduler.scheduleAll(context)
    }

    private fun createPendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, SilenceReceiver::class.java).apply {
            action = ACTION_MANUAL_UNSILENCE
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    internal fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return alarmManager.canScheduleExactAlarms()
        }
        return true
    }
}
