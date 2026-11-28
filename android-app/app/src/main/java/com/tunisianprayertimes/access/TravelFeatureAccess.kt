package com.tunisianprayertimes.access

import android.content.Context
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import com.tunisianprayertimes.DelegationLocator
import com.tunisianprayertimes.NotificationAccess
import com.tunisianprayertimes.PlaceResolution
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R

internal object TravelFeatureAccess {
    fun issues(context: Context): List<FeatureAccessIssue> {
        if (!PrefsManager.isAutoLocationUpdateEnabled(context) || !PrefsManager.canUsePlaceForAutomation(context)) {
            return emptyList()
        }
        return buildList {
            val feature = R.string.auto_location_title
            if (!DelegationLocator.hasLocationPermission(context)) {
                add(FeatureAccessIssue(feature, R.string.access_city_location_missing, AccessAction.APP_DETAILS))
            } else if (PrefsManager.isBackgroundTravelEnabled(context) &&
                !DelegationLocator.hasBackgroundLocationPermission(context)) {
                add(FeatureAccessIssue(R.string.travel_in_background, R.string.travel_allow_in_settings, AccessAction.APP_DETAILS))
            }
            if (!LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))) {
                add(FeatureAccessIssue(feature, R.string.mosque_needs_location, AccessAction.DEVICE_LOCATION))
            }
            val channel = PlaceResolution.LOCATION_CHANNEL
            val notificationIssue = NotificationAccess.issue(context, channel)
            if (notificationIssue != null) {
                val repairChannel = NotificationAccess.repairChannel(channel, notificationIssue)
                add(FeatureAccessIssue(R.string.access_city_change_alerts, R.string.travel_notification_enable,
                    AccessAction.NOTIFICATIONS, repairChannel))
            }
        }
    }
}
