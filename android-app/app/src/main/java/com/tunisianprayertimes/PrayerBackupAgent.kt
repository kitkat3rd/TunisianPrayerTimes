package com.tunisianprayertimes

import android.app.backup.BackupAgentHelper

/** Keep Android's full-backup rules and identify restored installs even when no first-run state transferred. */
class PrayerBackupAgent : BackupAgentHelper() {
    override fun onRestoreFinished() {
        super.onRestoreFinished()
        PrefsManager.markOnboardingRestored(this)
    }
}
