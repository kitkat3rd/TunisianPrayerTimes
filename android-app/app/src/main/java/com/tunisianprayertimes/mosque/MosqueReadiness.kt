package com.tunisianprayertimes.mosque

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.tunisianprayertimes.NotificationAccess
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import com.tunisianprayertimes.mosque.presence.MAX_WAKE_CIRCLES

object MosqueReadiness {
    internal fun forMode(context: Context, mode: AutomaticSilenceMode?, areas: SilenceAreaRead? = null): List<Int> =
        if (mode == AutomaticSilenceMode.TIME_AND_MOSQUE) issues(context, areas ?: PrivateSilenceAreaStore.read(context))
        else timeIssues(context)

    fun timeIssues(context: Context): List<Int> = buildList {
        if (!context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) add(R.string.mosque_needs_dnd)
        if (Build.VERSION.SDK_INT >= 31 && !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) add(R.string.mosque_needs_exact)
        if (MosqueSilenceSettings.usesOwnedRules(context) && !SilenceCoordinator.ruleReady(context, SilenceOwner.PRAYER)) add(R.string.mosque_needs_rules)
    }
    internal fun ownedRuleIssues(context: Context, owner: SilenceOwner): List<Int> = buildList {
        if (!context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) add(R.string.mosque_needs_dnd)
        if (!SilenceCoordinator.canRequest(context, owner)) add(R.string.mosque_needs_rules)
    }
    fun issues(context: Context, store: SilenceAreaRead = PrivateSilenceAreaStore.read(context)): List<Int> = buildList {
        if (Build.VERSION.SDK_INT < 29) add(R.string.mosque_needs_android)
        if (!MosqueSilenceSettings.usesOwnedRules(context) || !SilenceCoordinator.ruleReady(context, SilenceOwner.MOSQUE)) add(R.string.mosque_needs_rules)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) add(R.string.mosque_needs_precise)
        if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) add(R.string.mosque_needs_background)
        if (!MosqueSilenceSettings.backgroundDisclosureAccepted(context)) add(R.string.mosque_needs_disclosure)
        val locations = context.getSystemService(LocationManager::class.java)
        val enabled = if (Build.VERSION.SDK_INT >= 28) locations.isLocationEnabled else
            locations.isProviderEnabled(LocationManager.GPS_PROVIDER) || locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!enabled) add(R.string.mosque_needs_location)
        if (!context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) add(R.string.mosque_needs_dnd)
        if (Build.VERSION.SDK_INT >= 31 && !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) add(R.string.mosque_needs_exact)
        notificationIssue(context)?.let(::add)
        if (Build.VERSION.SDK_INT >= 28 && context.getSystemService(ActivityManager::class.java).isBackgroundRestricted) add(R.string.mosque_background_restricted)
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) add(R.string.mosque_needs_play_services)
        if (!store.available) add(R.string.mosque_store_unavailable)
        else if (store.areas.none { it.geometry.enabled }) add(R.string.mosque_no_areas)
        else if (store.areas.filter { it.geometry.enabled }.sumOf { it.geometry.polygons.size } > MAX_WAKE_CIRCLES) add(R.string.mosque_too_many_areas)
    }
    fun locationIssue(issue: Int) = issue == R.string.mosque_needs_precise ||
        issue == R.string.mosque_needs_background || issue == R.string.mosque_needs_location
    internal fun notificationIssue(context: Context): Int? =
        when (NotificationAccess.issue(context, MosqueObservationService.CHANNEL)) {
            null -> null
            NotificationAccess.Issue.APP_BLOCKED -> R.string.mosque_needs_notifications
            else -> R.string.mosque_observation_notifications_blocked
        }
    fun ready(context: Context) = issues(context).isEmpty()
}
