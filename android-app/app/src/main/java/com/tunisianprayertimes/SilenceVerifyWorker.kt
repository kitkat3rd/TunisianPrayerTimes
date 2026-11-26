package com.tunisianprayertimes

import com.tunisianprayertimes.mosque.MosqueSilenceSettings
import com.tunisianprayertimes.mosque.SilenceCoordinator
import com.tunisianprayertimes.mosque.MosqueSilenceMonitor

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * One battery-constrained periodic job for prayer silence repair and an opted-in travel lookup,
 * including trips between cities in the same time zone. Either feature can run on its own.
 */
class SilenceVerifyWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SilenceVerifyWorker"
        internal const val WORK_NAME = "silence_verify"
        private const val VERIFY_INTERVAL_HOURS = 6L
        private const val VERIFY_FLEX_HOURS = 2L

        private fun canVerifySilence(context: Context): Boolean =
            PrefsManager.isEnabled(context) && ScheduleRefreshCoordinator.hasSilencePermissions(context)

        @Synchronized
        fun reconcileSchedule(context: Context) {
            if (canVerifySilence(context) || DelegationLocator.canUpdateTravelInBackground(context)) enqueue(context)
            else cancel(context)
        }

        fun enqueue(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
                val request = PeriodicWorkRequestBuilder<SilenceVerifyWorker>(
                    VERIFY_INTERVAL_HOURS,
                    TimeUnit.HOURS,
                    VERIFY_FLEX_HOURS,
                    TimeUnit.HOURS,
                )
                    .setConstraints(constraints)
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, "WorkManager not initialized, skipping enqueue")
            }
        }

        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "WorkManager not initialized, skipping cancel")
            }
        }
    }

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!canVerifySilence(app) && !DelegationLocator.canUpdateTravelInBackground(app)) {
            reconcileSchedule(app)
            return Result.success()
        }
        // The locator rechecks travel consent before acquiring a fix and again before saving it.
        DelegationLocator.updatePlaceFromCurrentLocation(app, background = true)
        if (!canVerifySilence(app)) return Result.success()
        if (MosqueSilenceSettings.usesOwnedRules(app)) {
            SilenceCoordinator.recover(app)
            MosqueSilenceMonitor.reconcile(app)
        }
        RamadanOverrideChecker.startPollingIfNeeded()
        SilenceScheduler.scheduleAll(app)
        AnalyticsTracker.scheduleRefreshResult(
            context = app,
            source = "silence_worker",
            result = "success",
            silencePrayerCount = AnalyticsTracker.silencePrayerCount(app),
            wakeAlarmCount = AnalyticsTracker.enabledWakeAlarmCount(app),
        )
        return Result.success()
    }
}
