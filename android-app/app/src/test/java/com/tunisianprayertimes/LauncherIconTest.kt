package com.tunisianprayertimes

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LauncherIconTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val packages: PackageManager = context.packageManager

    @Test fun eachColourHasItsLauncherEntryInTheManifest() {
        for (icon in LauncherIcon.entries) {
            val entry = packages.getActivityInfo(icon.component(context), PackageManager.MATCH_DISABLED_COMPONENTS)
            assertEquals(MainActivity::class.java.name, entry.targetActivity)
            assertEquals(icon == LauncherIcon.Teal, entry.enabled)
        }
    }

    @Test fun theChosenColourBecomesTheOnlyLauncherEntryAndTakesTheShortcuts() {
        val shortcuts = context.getSystemService(ShortcutManager::class.java)
        LauncherShortcuts.refresh(context)
        assertEquals(LauncherIcon.Teal, LauncherIcon.current(context))

        for (chosen in listOf(LauncherIcon.Blue, LauncherIcon.Teal)) {
            LauncherIcon.choose(context, chosen)

            assertEquals(chosen, LauncherIcon.current(context))
            for (icon in LauncherIcon.entries) {
                assertEquals(
                    if (icon == chosen) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    packages.getComponentEnabledSetting(icon.component(context)),
                )
            }
            assertTrue(shortcuts.dynamicShortcuts.isNotEmpty())
            assertTrue(shortcuts.dynamicShortcuts.all { it.activity == chosen.component(context) })
        }
    }
}
