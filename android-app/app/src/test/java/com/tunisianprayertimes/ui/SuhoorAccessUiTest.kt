package com.tunisianprayertimes.ui

import android.Manifest
import android.app.Application
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.tunisianprayertimes.R
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SuhoorAccessUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun tonightRequiresExactAccessAndExplainsBlockedNotificationsWithoutPrompting() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        val activity = controller.setup().get()
        try {
            shadowOf(activity.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            activity.setContent {
                SuhoorAlarmSetupSheet(System.currentTimeMillis() + 7_200_000L, 30,
                    FajrAlarmPersistence.GENTLE, "إضافة منبه السحور", { _, _ -> }, {})
            }
            compose.onNodeWithText("إضافة منبه السحور").assertIsNotEnabled()
            compose.onNodeWithText(activity.getString(R.string.wake_access_exact_action)).assertExists()
            compose.onNodeWithText(activity.getString(R.string.wake_access_notifications_action)).assertExists()
            assertNull(shadowOf(activity).lastRequestedPermission)
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            controller.pause().resume()
            compose.onNodeWithText("إضافة منبه السحور").assertIsEnabled()
            compose.onNodeWithText(activity.getString(R.string.wake_access_notifications_action)).assertExists()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun aFutureSuhoorShowsTheDateAndRequiresSchedulingAccess() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        val activity = controller.setup().get()
        try {
            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            activity.setContent {
                SuhoorAlarmSetupSheet(
                    System.currentTimeMillis() + 2 * 86_400_000L, 30, FajrAlarmPersistence.GENTLE,
                    "إضافة منبه السحور", { _, _ -> }, {}, fastDayLabel = "الاثنين 30 نوفمبر",
                )
            }
            compose.onNodeWithText("منبّه السحور يوم الاثنين 30 نوفمبر").assertExists()
            compose.onNodeWithText("إضافة منبه السحور").assertIsNotEnabled()
            compose.onNodeWithText(activity.getString(R.string.wake_access_exact_action)).assertExists()
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
