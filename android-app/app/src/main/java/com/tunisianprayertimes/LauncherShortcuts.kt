package com.tunisianprayertimes

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

private class LauncherShortcutAction(
    val id: String,
    val label: Int,
    val icon: Int,
    val intent: Intent,
    val longLabel: Int = label,
)

/** Dynamic shortcuts follow both the installed package (including .dev) and the live silence state. */
internal object LauncherShortcuts {
    fun refresh(context: Context) {
        runCatching {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return
            retirePinnedDevelopmentShortcuts(manager)
            val launcher = ComponentName(context, MainActivity::class.java)
            val silenced = SilenceStatus.isAppControlledSilenceActive(context)
            val actions = listOf(
                LauncherShortcutAction(
                    "wake_before_adhan", R.string.shortcut_before_adhan, R.drawable.ic_tab_alarms,
                    Intent(context, WakeShortcutActivity::class.java).setAction(WakeShortcutActions.BEFORE_ADHAN),
                    R.string.shortcut_before_adhan_long,
                ),
                LauncherShortcutAction(
                    "wake_nap", R.string.shortcut_nap, R.drawable.ic_adhkar_sleep,
                    Intent(context, WakeShortcutActivity::class.java).setAction(WakeShortcutActions.NAP),
                ),
                LauncherShortcutAction(
                    "silence_toggle",
                    if (silenced) R.string.shortcut_unsilence else R.string.shortcut_silence,
                    if (silenced) R.drawable.ic_volume_on else R.drawable.ic_volume_off,
                    Intent(context, SilenceShortcutActivity::class.java)
                        .setAction(if (silenced) SilenceShortcutActions.UNSILENCE else SilenceShortcutActions.SILENCE),
                ),
                LauncherShortcutAction(
                    "silence_30_minutes",
                    if (silenced) R.string.shortcut_silence_reset_30 else R.string.shortcut_silence_30,
                    R.drawable.ic_adhkar_clock,
                    Intent(context, SilenceShortcutActivity::class.java).setAction(SilenceShortcutActions.SILENCE_30_MINUTES),
                ),
                LauncherShortcutAction(
                    "hifz_review", R.string.shortcut_review, R.drawable.ic_tab_quran,
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClass(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_QURAN)
                        .putExtra(MainTabNavigation.EXTRA_QURAN_ACTION, MainTabNavigation.QURAN_ACTION_HIFZ_REVIEW),
                    R.string.shortcut_review_long,
                ),
            )
            val desired = actions.take(manager.maxShortcutCountPerActivity).mapIndexed { rank, action ->
                ShortcutInfo.Builder(context, action.id)
                    .setActivity(launcher)
                    .setShortLabel(context.getString(action.label))
                    .setLongLabel(context.getString(action.longLabel))
                    .setIcon(Icon.createWithResource(context, action.icon))
                    .setIntent(action.intent)
                    .setRank(rank)
                    .build()
            }
            val current = manager.dynamicShortcuts.associateBy { it.id }
            if (current.keys != desired.map { it.id }.toSet()) {
                manager.dynamicShortcuts = desired
            } else {
                val changed = desired.filter { next ->
                    val old = current.getValue(next.id)
                    old.shortLabel.toString() != next.shortLabel.toString() ||
                        old.longLabel.toString() != next.longLabel.toString() ||
                        old.rank != next.rank || old.intent?.action != next.intent?.action
                }
                // Avoid consuming Android's shortcut-update allowance for unchanged preference writes.
                if (changed.isNotEmpty()) manager.updateShortcuts(changed)
            }
        }.onFailure { Log.w("LauncherShortcuts", "Could not refresh launcher shortcuts", it) }
    }

    /** Retain the returned subscription: SharedPreferences stores its listeners weakly. */
    fun observe(context: Context): () -> Unit {
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val refresh = Runnable { refresh(app) }
        fun enqueue() {
            // A start/stop writes several fields; publish its final state once those writes finish.
            handler.removeCallbacks(refresh)
            handler.post(refresh)
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> enqueue() }
        val stores = listOf("prayer_silence_prefs", "silence_ownership", "wake_alarm_scheduler", "mosque_silence_settings")
            .map { app.getSharedPreferences(it, Context.MODE_PRIVATE) }
        stores.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = enqueue()
        }
        val filter = IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // This action is system-protected; no application-specific permission shim is needed.
            app.registerReceiver(receiver, filter)
        }
        refresh(app)
        return {
            stores.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }
            app.unregisterReceiver(receiver)
            handler.removeCallbacks(refresh)
        }
    }
}
