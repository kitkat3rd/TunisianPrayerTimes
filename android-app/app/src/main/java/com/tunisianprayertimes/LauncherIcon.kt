package com.tunisianprayertimes

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * The colours of the app's logo. Each has its own launcher entry in the manifest (an alias of [MainActivity]),
 * and only one entry is on: the one the user chose.
 */
internal enum class LauncherIcon(
    private val alias: String,
    @DrawableRes val foreground: Int,
    @StringRes val label: Int,
) {
    Teal("LauncherTeal", R.drawable.ic_launcher_foreground, R.string.launcher_icon_teal),
    Blue("LauncherBlue", R.drawable.ic_launcher_blue_foreground, R.string.launcher_icon_blue);

    /** The package differs between builds (.dev); the class name does not. */
    fun component(context: Context) =
        ComponentName(context.packageName, MainActivity::class.java.name.replaceAfterLast('.', alias))

    companion object {
        /** The manifest turns [Teal] on, so another colour is the chosen one only after [choose] turned it on. */
        fun current(context: Context): LauncherIcon = entries.firstOrNull {
            it != Teal && context.packageManager.getComponentEnabledSetting(it.component(context)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: Teal

        fun choose(context: Context, icon: LauncherIcon) {
            if (icon == current(context)) return
            // The chosen entry goes on before the others go off, so the launcher always has one to show.
            for (entry in listOf(icon) + (entries - icon)) {
                context.packageManager.setComponentEnabledSetting(
                    entry.component(context),
                    if (entry == icon) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            // The long-press shortcuts belong to one launcher entry: move them to the new one.
            LauncherShortcuts.refresh(context)
        }
    }
}
