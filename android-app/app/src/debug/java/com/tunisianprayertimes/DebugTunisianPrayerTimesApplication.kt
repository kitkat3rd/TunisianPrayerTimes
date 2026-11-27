package com.tunisianprayertimes

import com.tunisianprayertimes.mosque.trace.SilenceWalkReports

class DebugTunisianPrayerTimesApplication : TunisianPrayerTimesApplication() {
    override fun onCreate() {
        super.onCreate()
        SilenceWalkReports.install(this)
    }
}
