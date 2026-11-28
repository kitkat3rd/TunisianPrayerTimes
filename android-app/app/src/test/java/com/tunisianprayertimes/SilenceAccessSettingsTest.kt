package com.tunisianprayertimes

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SilenceAccessSettingsTest {
    private fun settingsAttempts(vararg failures: RuntimeException): List<String?> {
        val actions = mutableListOf<String?>()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
            override fun startActivity(intent: Intent) {
                actions.add(intent.action)
                failures.getOrNull(actions.lastIndex)?.let { throw it }
            }
        }
        SilenceAccessSettings.openDnd(context)
        return actions
    }

    @Test fun missingDetailPageOpensThePublicDndAccessList() {
        assertEquals(
            listOf(SilenceAccessSettings.DND_ACCESS_DETAIL, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
            settingsAttempts(ActivityNotFoundException()),
        )
    }

    @Test fun restrictedDetailPageAndMissingListFallBackToSystemSettings() {
        assertEquals(
            listOf(SilenceAccessSettings.DND_ACCESS_DETAIL, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, Settings.ACTION_SETTINGS),
            settingsAttempts(SecurityException(), ActivityNotFoundException()),
        )
    }

    @Test fun unavailableBatteryPageFallsBackToDetailsForTheSameInstallation() {
        val intents = mutableListOf<Intent>()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
            override fun startActivity(intent: Intent) {
                intents.add(intent)
                if (intents.size == 1) throw ActivityNotFoundException()
            }
        }
        SilenceAccessSettings.openAppBattery(context)
        assertEquals(listOf(SilenceAccessSettings.APP_BATTERY_DETAIL, Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
            intents.map { it.action })
        assertEquals(List(2) { "package:${context.packageName}" }, intents.map { it.dataString })
    }
}
