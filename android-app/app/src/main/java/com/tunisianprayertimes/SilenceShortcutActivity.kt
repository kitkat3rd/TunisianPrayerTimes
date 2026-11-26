package com.tunisianprayertimes

import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.tunisianprayertimes.mosque.MosqueSilenceSettings
import com.tunisianprayertimes.mosque.SilenceOwner
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
        if (!getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) {
            showOutcome(R.string.toast_dnd_permission)
            return
        }
        lifecycleScope.launch {
            val message = try {
                withContext(NonCancellable + ScheduleRefreshCoordinator.scheduling) {
                    SilenceShortcutActions.perform(this@SilenceShortcutActivity, requireNotNull(action))
                }
            } catch (error: Exception) {
                Log.w("SilenceShortcut", "Could not perform launcher silence action", error)
                R.string.shortcut_silence_failed
            } finally {
                LauncherShortcuts.refresh(this@SilenceShortcutActivity)
            }
            if (!isFinishing && !isDestroyed) showOutcome(message)
        }
    }

    private fun showOutcome(message: Int) {
        when (message) {
            R.string.toast_dnd_permission -> showSettingsDialog(R.string.manual_dnd_access_title,
                R.string.shortcut_dnd_retry, R.string.manual_dnd_open_settings, SilenceAccessSettings::openDnd)
            R.string.shortcut_silence_30_inexact -> showSettingsDialog(R.string.shortcut_silence_30,
                message, R.string.manual_exact_open_settings, SilenceAccessSettings::openExactAlarms)
            R.string.manual_rule_failed_body -> showSettingsDialog(R.string.manual_rule_failed_title,
                message, R.string.manual_rule_settings) { SilenceAccessSettings.openRules(it, SilenceOwner.MANUAL) }
            else -> {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun showSettingsDialog(title: Int, details: Int, action: Int, openSettings: (Context) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(details)
            .setPositiveButton(action) { _, _ -> openSettings(this) }
            .setNegativeButton(R.string.manual_exact_later, null)
            .setOnDismissListener { finish() }
            .show()
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
                if ((hadManual || hadAuto) && !ended) return failure(context)
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
        if (action == SILENCE_30_MINUTES) {
            ManualSilenceScheduler.start(context, ManualSilenceMode.DURATION, durationMinutes = 30)
                ?: return failure(context)
            PrefsManager.setManualSilenceDurationMinutes(context, 30)
            AnalyticsTracker.manualSilenceStarted(context, ManualSilenceMode.DURATION, 30)
            return if (ManualSilenceScheduler.canScheduleExact(context)) R.string.shortcut_silence_30_confirmed
                else R.string.shortcut_silence_30_inexact
        }
        ManualSilenceScheduler.start(context, ManualSilenceMode.UNTIL_STOPPED)
            ?: return failure(context)
        AnalyticsTracker.manualSilenceStarted(context, ManualSilenceMode.UNTIL_STOPPED, null)
        return R.string.toast_silent_enabled
    }

    private fun failure(context: Context): Int = when {
        !context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted -> R.string.toast_dnd_permission
        MosqueSilenceSettings.usesOwnedRules(context) -> R.string.manual_rule_failed_body
        else -> R.string.shortcut_silence_failed
    }
}
