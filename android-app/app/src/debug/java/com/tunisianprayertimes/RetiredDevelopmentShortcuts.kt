package com.tunisianprayertimes

import android.content.pm.ShortcutManager

internal fun retirePinnedDevelopmentShortcuts(manager: ShortcutManager) {
    // Replacing the dynamic menu leaves copies that users pinned on their home screen.
    val retired = manager.pinnedShortcuts.filter {
        it.isEnabled && (it.id == "silence_walk_start" || it.id == "silence_walk_stop")
    }
    if (retired.isNotEmpty()) manager.disableShortcuts(retired.map { it.id })
}
