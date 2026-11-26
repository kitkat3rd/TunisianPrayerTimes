package com.tunisianprayertimes.mosque.presence

enum class PresenceState {
    IDLE,
    ACQUIRING,
    VISIT_CONFIRMED,
    EXITING,
    INACTIVE_NEEDS_SETUP,
    INACTIVE_NEEDS_REARM,
    SCHEDULE_FALLBACK,
}
