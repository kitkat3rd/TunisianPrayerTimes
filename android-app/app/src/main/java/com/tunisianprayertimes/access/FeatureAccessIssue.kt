package com.tunisianprayertimes.access

import androidx.annotation.StringRes

internal enum class AccessAction {
    MOSQUE_SETUP, EXACT_ALARMS, NOTIFICATIONS, FULL_SCREEN, APP_DETAILS, DEVICE_LOCATION, DND, WAKE_RULE,
}

internal data class FeatureAccessIssue(
    @StringRes val feature: Int,
    @StringRes val message: Int,
    val action: AccessAction,
    val channelId: String? = null,
)
