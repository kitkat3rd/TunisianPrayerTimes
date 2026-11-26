package com.tunisianprayertimes

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.tunisianprayertimes.mosque.MosqueSettingsActivity
import com.tunisianprayertimes.mosque.SilenceOwner

internal object SilenceAccessSettings {
    // Android Settings supports this per-app page on some devices. The public action opens the access list.
    internal const val DND_ACCESS_DETAIL = "android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS"

    fun openDnd(context: Context) = open(
        context,
        Intent(DND_ACCESS_DETAIL, Uri.fromParts("package", context.packageName, null)),
        fallback = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
    )

    fun openExactAlarms(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            open(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null)))
        }
    }

    fun openRules(context: Context, owner: SilenceOwner) = open(context, MosqueSettingsActivity.setupIntent(context, owner))

    fun openDisabledRule(context: Context, id: String) {
        val sound = Intent(Settings.ACTION_SOUND_SETTINGS)
        if (Build.VERSION.SDK_INT >= 35) {
            open(context, Intent(Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS)
                .putExtra(Settings.EXTRA_AUTOMATIC_ZEN_RULE_ID, id), fallback = sound)
        } else {
            open(context, sound)
        }
    }

    private fun open(context: Context, intent: Intent, fallback: Intent? = null) {
        fun launch(target: Intent): Boolean {
            if (context !is Activity) target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(target)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
        if (!launch(intent) && fallback?.let(::launch) != true && !launch(Intent(Settings.ACTION_SETTINGS))) {
            Toast.makeText(context, R.string.manual_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }
}
