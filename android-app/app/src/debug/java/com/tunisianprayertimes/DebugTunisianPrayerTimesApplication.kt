package com.tunisianprayertimes

import com.tunisianprayertimes.mosque.trace.DebugWalkAutoStart

class DebugTunisianPrayerTimesApplication : TunisianPrayerTimesApplication() {
    override fun onCreate() {
        super.onCreate()
        DebugWalkAutoStart.install(this)
    }
}
