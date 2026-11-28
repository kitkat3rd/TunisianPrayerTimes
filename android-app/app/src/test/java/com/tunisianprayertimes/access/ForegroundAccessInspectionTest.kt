package com.tunisianprayertimes.access

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ForegroundAccessInspectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openingAndReturningCheckOnceEvenWithoutAPrayerWindow() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PrefsManager.setEnabled(app, false)
        PrefsManager.setAutoLocationUpdateEnabled(app, false)
        PrefsManager.setCallEndVibrationEnabled(app, true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)

        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.STARTED
        val completed = AtomicInteger()
        val tick = mutableIntStateOf(0)
        val access = mutableStateOf<AppAccessSnapshot?>(null)
        compose.setContent {
            LaunchedEffect(owner) {
                owner.lifecycle.inspectAccessWhenResumed {
                    access.value = readAppAccess(app, storedWakeAlarms = emptyList())
                    completed.incrementAndGet()
                }
            }
            AppAccessLayout(access.value?.issues.orEmpty(), mosqueEnabled = false) {
                Text(tick.intValue.toString())
            }
        }
        compose.waitForIdle()
        assertEquals(0, completed.get())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(10_000) { completed.get() == 1 }
        val warning = app.getString(R.string.feature_access_review_one, app.getString(R.string.call_end_vibration_title))
        compose.onNodeWithText(warning).assertExists()

        repeat(3) {
            compose.mainClock.advanceTimeBy(1_000)
            compose.runOnIdle { tick.intValue++ }
        }
        compose.waitForIdle()
        assertEquals(1, completed.get())
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        assertEquals(1, completed.get())

        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(10_000) { completed.get() == 2 }
        compose.onNodeWithText(warning).assertDoesNotExist()
    }
}
