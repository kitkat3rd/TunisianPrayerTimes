package com.tunisianprayertimes

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.tunisianprayertimes.mosque.MosqueSilenceSettings
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Performs a launcher action over the home screen without opening or replacing the app's current tab. */
class SilenceShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        if (savedInstanceState != null || action !in SilenceShortcutActions.actions) {
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                val message = withContext(NonCancellable + ScheduleRefreshCoordinator.scheduling) {
                    SilenceShortcutActions.perform(this@SilenceShortcutActivity, requireNotNull(action))
                }
                Toast.makeText(this@SilenceShortcutActivity, message, Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                Log.w("SilenceShortcut", "Could not perform launcher silence action", error)
                Toast.makeText(this@SilenceShortcutActivity, R.string.shortcut_silence_failed, Toast.LENGTH_SHORT).show()
            } finally {
                LauncherShortcuts.refresh(this@SilenceShortcutActivity)
                finish()
            }
        }
    }
}

internal object SilenceShortcutActions {
    const val SILENCE = "com.tunisianprayertimes.shortcut.SILENCE"
    const val UNSILENCE = "com.tunisianprayertimes.shortcut.UNSILENCE"
    const val SILENCE_30_MINUTES = "com.tunisianprayertimes.shortcut.SILENCE_30_MINUTES"
    val actions = setOf(SILENCE, UNSILENCE, SILENCE_30_MINUTES)

    /** Called on the same serial scheduling dispatcher as the in-app silence controls. */
    fun perform(context: Context, action: String): Int {
        require(action in actions)
        if (!context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) {
            return R.string.toast_dnd_permission
        }
        ManualSilenceScheduler.syncExpiredTimer(context)
        if (action == UNSILENCE) {
            synchronized(SilenceScheduler) {
                val wakeIds = WakeAlarmScheduler.silencedAlarmIds(context).toList()
                val hadManual = PrefsManager.isManualSilenceActive(context)
                val hadAuto = PrefsManager.isAutoSilenceActive(context)
                if (!hadManual && !hadAuto && wakeIds.isEmpty()) {
                    return R.string.shortcut_silence_already_off
                }
                // Read the bridging prayer before releasing the flag that identifies it.
                val prayer = SilenceScheduler.currentSilenceWindowPrayer(context)
                // End the app's wake-silence contribution as well; its alarms still ring as scheduled.
                wakeIds.forEach {
                    WakeAlarmScheduler.removeSilenceUntilAlarm(context, it)
                }
                val ended = when {
                    PrefsManager.isManualSilenceActive(context) -> SilenceModeController.setManualNormal(context)
                    PrefsManager.isAutoSilenceActive(context) -> SilenceModeController.disableAutoSilence(context)
                    else -> false
                }
                if ((hadManual || hadAuto) && !ended) return R.string.shortcut_silence_failed
                // A refresh must not immediately restart the prayer occurrence the user just stopped.
                SilenceScheduler.dismissCurrentWindows(context, prayer)
                if (ended || wakeIds.isNotEmpty()) SilenceModeController.notifyIfMissedCallDuringSilence(context)
            }
            return if (MosqueSilenceSettings.usesOwnedRules(context)) R.string.mosque_request_released
                else R.string.toast_normal_restored
        }
        // A stale/pinned "Silence" is idempotent and preserves an already running timer.
        if (action == SILENCE && SilenceStatus.isAppControlledSilenceActive(context)) {
            return R.string.toast_silent_enabled
        }
        synchronized(SilenceModeController) {
            if (!SilenceModeController.setManualSilent(context)) return R.string.shortcut_silence_failed
            if (action == SILENCE_30_MINUTES) {
                PrefsManager.setManualSilenceMode(context, ManualSilenceMode.DURATION)
                PrefsManager.setManualSilenceDurationMinutes(context, 30)
                // Replaces any current manual deadline with 30 minutes from this tap.
                ManualSilenceScheduler.schedule(context, 30)
                AnalyticsTracker.manualSilenceStarted(context, ManualSilenceMode.DURATION, 30)
                return R.string.shortcut_silence_30_confirmed
            }
            PrefsManager.setManualSilenceMode(context, ManualSilenceMode.UNTIL_STOPPED)
            ManualSilenceScheduler.cancel(context)
            AnalyticsTracker.manualSilenceStarted(context, ManualSilenceMode.UNTIL_STOPPED, null)
            return R.string.toast_silent_enabled
        }
    }
}
