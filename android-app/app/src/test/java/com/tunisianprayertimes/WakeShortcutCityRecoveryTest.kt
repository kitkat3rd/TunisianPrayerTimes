package com.tunisianprayertimes

import android.app.Application
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.wake.PrayerWakeRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WakeShortcutCityRecoveryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var controller: ActivityController<WakeShortcutActivity>

    @Before fun prepare() {
        runBlocking { PrayerWakeRepository(app).clearAllWakeConfigs() }
        PrefsManager.setDelegationId(app, PlaceResolution.TUNIS_DELEGATION_ID)
        PrefsManager.markFirstLaunchDone(app)
        PrefsManager.setPlaceProvisional(app, true)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
    }

    private fun openShortcut(action: String) {
        controller = Robolectric.buildActivity(
            WakeShortcutActivity::class.java,
            Intent(app, WakeShortcutActivity::class.java).setAction(action),
        ).setup()
    }

    @After fun closeShortcut() {
        try {
            if (::controller.isInitialized) controller.pause().stop().destroy()
        } finally {
            runBlocking { PrayerWakeRepository(app).clearAllWakeConfigs() }
        }
    }

    @Test fun confirmingPlaceAndRotatingWaitForAnotherTapToSetTheAlarm() {
        openShortcut(WakeShortcutActions.BEFORE_ADHAN)
        compose.onNodeWithTag("feature_confirm_suggested_place").performClick()
        compose.waitForIdle()
        assertTrue(PrefsManager.hasConfirmedPlace(app))
        controller.recreate()
        compose.waitForIdle()
        compose.onNodeWithText(app.getString(R.string.shortcut_before_adhan_long)).assertExists()
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        assertTrue(runBlocking { PrayerWakeRepository(app).getCurrentStore().alarms.isEmpty() })
        compose.onNodeWithText(app.getString(R.string.wake_access_exact_action)).performClick()
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, shadowOf(controller.get()).nextStartedActivity.action)
    }

    @Test fun cancellingPlaceAndShortcutLeavesTheCityAndAlarmsUntouched() {
        openShortcut(WakeShortcutActions.BEFORE_ADHAN)
        compose.onNodeWithTag("feature_cancel_place").performClick()
        compose.onNodeWithText(app.getString(R.string.shortcut_before_adhan_long)).assertExists()
        compose.onNodeWithText(app.getString(R.string.wake_editor_cancel)).performClick()
        assertTrue(controller.get().isFinishing)
        assertFalse(PrefsManager.hasConfirmedPlace(app))
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        assertTrue(runBlocking { PrayerWakeRepository(app).getCurrentStore().alarms.isEmpty() })
    }

    @Test fun napWaitsAcrossDeniedReturnAndRecreationThenSavesOnlyOnExplicitTap() {
        openShortcut(WakeShortcutActions.NAP)
        val openSettings = app.getString(R.string.wake_access_exact_action)
        compose.onNodeWithText(openSettings).performClick()
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, shadowOf(controller.get()).nextStartedActivity.action)
        controller.pause().resume()
        compose.onNodeWithText(openSettings).assertExists()
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        compose.onNodeWithText(openSettings).performClick()
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, shadowOf(controller.get()).nextStartedActivity.action)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        controller.recreate()
        compose.waitForIdle()
        compose.onNodeWithText(app.getString(R.string.shortcut_nap)).assertExists()
        val repository = PrayerWakeRepository(app)
        assertTrue(runBlocking { repository.getCurrentStore().alarms.isEmpty() })
        compose.onNodeWithText(app.getString(R.string.wake_editor_silence_inline_save)).performClick()
        compose.waitUntil(10_000) { runBlocking { repository.getCurrentStore().alarms.size == 1 } }
        val alarm = runBlocking { repository.getCurrentStore().alarms.single() }
        assertEquals(WakeMainAlarmMode.FROM_NOW, alarm.mainAlarm.mode)
        assertEquals(20, alarm.mainAlarm.oneOffOffsetMinutes)
    }
}
