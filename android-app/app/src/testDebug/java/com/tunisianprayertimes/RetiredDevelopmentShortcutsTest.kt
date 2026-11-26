package com.tunisianprayertimes

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RetiredDevelopmentShortcutsTest {
    @Test fun startupRefreshRemovesRecorderActionsAndDisablesPreviouslyPinnedCopies() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(ShortcutManager::class.java)
        fun oldShortcut(id: String) = ShortcutInfo.Builder(context, id)
            .setActivity(ComponentName(context, MainActivity::class.java))
            .setShortLabel(id)
            .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
            .build()
        val start = oldShortcut("silence_walk_start")
        val stop = oldShortcut("silence_walk_stop")
        val otherPin = oldShortcut("user_pinned")
        manager.dynamicShortcuts = listOf(start, stop)
        assertTrue(manager.requestPinShortcut(stop, null))
        assertTrue(manager.requestPinShortcut(otherPin, null))

        val unsubscribe = LauncherShortcuts.observe(context)
        try {
            assertEquals(listOf("wake_before_adhan", "wake_nap", "silence_toggle", "silence_30_minutes", "hifz_review"),
                manager.dynamicShortcuts.sortedBy { it.rank }.map { it.id })
            assertThrows(IllegalArgumentException::class.java) { manager.requestPinShortcut(stop, null) }
            assertTrue(manager.requestPinShortcut(otherPin, null))
        } finally {
            unsubscribe()
        }
    }
}
