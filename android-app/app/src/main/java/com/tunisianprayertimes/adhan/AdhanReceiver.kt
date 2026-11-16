package com.tunisianprayertimes.adhan

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhan.AdhanScheduler.occurrence
import com.tunisianprayertimes.adhan.AdhanScheduler.putOccurrence
import kotlin.concurrent.thread

/** Delivers an adhan's time, and its warning just before, without changing the silencer. */
class AdhanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            AdhanScheduler.ACTION_ADHAN -> {
                val occurrence = intent.occurrence() ?: return
                Log.d(TAG, "Adhan time of $occurrence")
                // The warning has done its work: the adhan or its quiet notice takes its place.
                AdhanPlaybackService.cancelWarning(app)
                // A prayer switched off since its alarm was armed, or this adhan cancelled from its warning: no
                // player, so no notification flashes by.
                if (AdhanSettings.isEnabled(app, occurrence.prayer)) {
                    if (AdhanSettings.isSkipped(app, occurrence.prayer, occurrence.prayerDay)) {
                        // The time has come all the same: the silent notice says so.
                        AdhanPlaybackService.postQuietNotice(app, occurrence, R.string.adhan_quiet_cancelled)
                    } else if (!AdhanPlaybackService.start(app, occurrence)) {
                        // At once, inside the alarm: Android lets an exact alarm start the player now. The player decides.
                        AdhanPlaybackService.postQuietNotice(app, occurrence, R.string.adhan_quiet_failed)
                    }
                }
                // The next adhans, before Android may freeze the process once this broadcast ends.
                inBackground { AdhanScheduler.refresh(app) }
            }
            AdhanScheduler.ACTION_ADHAN_SOON -> {
                val occurrence = intent.occurrence() ?: return
                if (!AdhanSettings.isEnabled(app, occurrence.prayer) || !AdhanSettings.warnsBefore(app)) return
                // The forecast reads the silence windows: off the main thread, still inside the alarm's allowance.
                inBackground { AdhanPlaybackService.warnBeforeAdhan(app, occurrence) }
            }
            ACTION_SKIP -> {
                val occurrence = intent.occurrence() ?: return
                Log.d(TAG, "Adhan of $occurrence cancelled from its warning")
                AdhanSettings.skip(app, occurrence.prayer, occurrence.prayerDay)
                AdhanPlaybackService.cancelWarning(app)
            }
        }
    }

    private fun inBackground(work: () -> Unit) {
        val pending = goAsync()
        thread(name = "adhan-receiver") {
            try {
                work()
            } catch (error: Exception) {
                Log.w(TAG, "Adhan work failed", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AdhanReceiver"
        private const val ACTION_SKIP = "com.tunisianprayertimes.adhan.ACTION_SKIP"
        private const val SKIP_CODE = 760_098

        /** «إلغاء هذا الأذان» in the warning: only this adhan, the prayer's switch stays on. */
        internal fun skipIntent(context: Context, occurrence: AdhanScheduler.Occurrence): PendingIntent = PendingIntent.getBroadcast(
            context, SKIP_CODE,
            Intent(context, AdhanReceiver::class.java)
                .setAction(ACTION_SKIP)
                .setData(Uri.parse("tunisianprayertimes://adhan-skip/${occurrence.prayer.name}/${occurrence.prayerDay}"))
                .putOccurrence(occurrence),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
