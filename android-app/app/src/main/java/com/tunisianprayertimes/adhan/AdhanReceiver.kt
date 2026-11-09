package com.tunisianprayertimes.adhan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceModeController
import com.tunisianprayertimes.adhan.AdhanScheduler.occurrence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The adhans' alarms: an adhan's time, or the safety alarm that brings back a silence an adhan held. */
class AdhanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            AdhanScheduler.ACTION_ADHAN -> {
                val occurrence = intent.occurrence() ?: return
                Log.d(TAG, "Adhan time of $occurrence")
                // At once, inside the alarm: Android lets an exact alarm start the player now. The player decides.
                if (!AdhanPlaybackService.start(app, occurrence) && AdhanSettings.isEnabled(app, occurrence.prayer)) {
                    AdhanPlaybackService.postQuietNotice(app, occurrence, R.string.adhan_quiet_failed)
                }
                // The next adhans.
                AdhanScheduler.refreshLater(app)
            }
            AdhanScheduler.ACTION_RESUME_SILENCE -> {
                val result = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        SilenceModeController.releaseAdhanHold(app, force = true)
                    } catch (error: Exception) {
                        Log.w(TAG, "Could not bring the prayer silence back", error)
                    } finally {
                        result.finish()
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "AdhanReceiver"
    }
}
