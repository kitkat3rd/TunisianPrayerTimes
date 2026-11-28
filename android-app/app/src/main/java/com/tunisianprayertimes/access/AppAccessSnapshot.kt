package com.tunisianprayertimes.access

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.tunisianprayertimes.NotificationAccess
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.DhikrReminderScheduler
import com.tunisianprayertimes.mosque.MosqueObservationService
import com.tunisianprayertimes.mosque.MosqueReadiness
import com.tunisianprayertimes.mosque.MosqueSilenceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AppAccessSnapshot(
    val silenceIssues: List<Int>?,
    val issues: List<FeatureAccessIssue>,
    val platform: PlatformAccess? = null,
)

internal data class PlatformAccess(
    val dnd: Boolean,
    val exact: Boolean,
    val batteryExempt: Boolean,
    val phoneState: Boolean,
)

/** Inspect saved features and their current Android access without scheduling or activating them. */
internal suspend fun readAppAccess(
    context: Context,
    storedWakeAlarms: List<PrayerWakeConfig>? = null,
): AppAccessSnapshot = withContext(Dispatchers.IO) {
    val app = context.applicationContext
    val platform = readAccessPart("Android access") {
        PlatformAccess(
            dnd = app.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted,
            exact = DhikrReminderScheduler.exactAlarmsEnabled(app),
            batteryExempt = app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName),
            phoneState = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val silenceIssues = readAccessPart("prayer silence") {
        when {
            !PrefsManager.isAutomaticSilenceRequested(app) -> null
            !PrefsManager.canUsePlaceForAutomation(app) -> listOf(R.string.mosque_waiting_for_place)
            else -> MosqueSilenceSettings.mode(app)?.let { MosqueReadiness.forMode(app, it) }
                ?: listOf(R.string.mosque_invalid_mode)
        }
    }
    val issues = buildList {
        addAll(readAccessPart("silence repair actions") { silenceAccessIssues(app, silenceIssues.orEmpty()) }.orEmpty())
        addAll(readAccessPart("adhan and reminders") { PrayerReminderAccess.issues(app) }.orEmpty())
        addAll(readAccessPart("wake alarms") { WakeFeatureAccess.issues(app, storedWakeAlarms) }.orEmpty())
        addAll(readAccessPart("automatic city updates") { TravelFeatureAccess.issues(app) }.orEmpty())
    }
    AppAccessSnapshot(silenceIssues, issues.distinct(), platform)
}

/** A failed settings/store read leaves that part unknown, and the other checks can still finish. */
private inline fun <T> readAccessPart(feature: String, read: () -> T): T? = try {
    read()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Log.w("AppAccess", "Could not inspect $feature", failure)
    null
}

/** Prayer also shows setup issues such as a missing saved area. Only actionable access belongs here. */
private fun silenceAccessIssues(context: Context, issues: List<Int>): List<FeatureAccessIssue> = issues.mapNotNull { issue ->
    val action = when (issue) {
        R.string.mosque_needs_dnd -> AccessAction.DND
        R.string.mosque_needs_exact -> AccessAction.EXACT_ALARMS
        R.string.mosque_needs_precise, R.string.mosque_needs_background -> AccessAction.MOSQUE_SETUP
        R.string.mosque_needs_rules -> {
            if (Build.VERSION.SDK_INT < 29 || R.string.mosque_needs_dnd in issues) return@mapNotNull null
            AccessAction.MOSQUE_SETUP
        }
        R.string.mosque_needs_location -> AccessAction.DEVICE_LOCATION
        R.string.mosque_needs_notifications, R.string.mosque_observation_notifications_blocked -> AccessAction.NOTIFICATIONS
        R.string.mosque_background_restricted -> AccessAction.APP_DETAILS
        else -> return@mapNotNull null
    }
    val channel = if (issue == R.string.mosque_observation_notifications_blocked) {
        val observedChannel = MosqueObservationService.CHANNEL
        NotificationAccess.repairChannel(observedChannel, NotificationAccess.issue(context, observedChannel))
    } else null
    FeatureAccessIssue(R.string.auto_silence, issue, action, channel)
}
