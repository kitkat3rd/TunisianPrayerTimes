package com.tunisianprayertimes.adhan

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import org.junit.After
import org.junit.Assert.assertEquals
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
class AdhanSettingsUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    private lateinit var context: Context
    private lateinit var controller: ActivityController<AdhanSettingsActivity>

    @Before
    fun showSettings() {
        context = ApplicationProvider.getApplicationContext()
        PrefsManager.markFirstLaunchDone(context)
        context.getSharedPreferences("adhan_settings", Context.MODE_PRIVATE).edit().clear().commit()
        controller = Robolectric.buildActivity(AdhanSettingsActivity::class.java).setup()
        compose.waitForIdle()
    }

    @After
    fun closeSettings() {
        controller.pause().stop().destroy()
    }

    private fun allPrayersSwitch() = compose.onNodeWithText(context.getString(R.string.adhan_all_prayers))

    @Test
    fun cityConfirmationReturnsToTheRequestedAdhanAndWaitsForExplicitEnable() {
        PrefsManager.setPlaceProvisional(context, true)
        val fajr = AdhanSettings.prayerName(context, Prayer.FAJR)
        compose.onNodeWithText(fajr).performScrollTo().performClick()
        compose.onNodeWithTag("feature_cancel_place").performClick()
        compose.onNodeWithText(fajr).assertIsOff().performClick()
        controller.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("feature_confirm_suggested_place").performClick()
        compose.onNodeWithText(context.getString(R.string.adhan_enable_prayer_question, fajr)).assertExists()
        assertEquals(emptyList<Prayer>(), AdhanSettings.enabledPrayers(context))
        compose.onNodeWithText(context.getString(R.string.adhan_enable_confirm)).performClick()
        assertEquals(listOf(Prayer.FAJR), AdhanSettings.enabledPrayers(context))
    }

    @Test
    fun provisionalPlaceCannotStartAnAdhanButAnExistingChoiceCanBeTurnedOff() {
        allPrayersSwitch().performClick()
        PrefsManager.setPlaceProvisional(context, true)
        allPrayersSwitch().performClick().assertIsOff()
        allPrayersSwitch().performClick()
        compose.onNodeWithText(context.getString(R.string.prayer_feature_place_required)).assertExists()
        assertEquals(emptyList<Prayer>(), AdhanSettings.enabledPrayers(context))
        compose.onNodeWithText(context.getString(R.string.jomoaa_time_cancel)).performClick()
        allPrayersSwitch().assertIsOff()
    }

    @Test
    fun inexactTimingAndAdvanceWarningRecoverOnResume() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        controller.pause().resume()
        allPrayersSwitch().performClick()
        compose.onNodeWithText(context.getString(R.string.adhan_warning_needs_exact), substring = true).assertExists()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        controller.pause().resume()
        compose.onNodeWithText(context.getString(R.string.adhan_exact_alarms_off), substring = true).assertDoesNotExist()
        allPrayersSwitch().assertIsOn()
    }

    @Test
    fun disabledChannelOffersItsOwnSystemSettingOnlyForAnEnabledAdhan() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        val channelId = AdhanPlaybackService.CHANNEL_PLAYING
        val name = context.getString(R.string.adhan_channel_playing)
        manager.createNotificationChannel(NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_NONE))
        controller.pause().resume()
        val message = context.getString(R.string.adhan_channel_off, name)
        compose.onNodeWithText(message).assertDoesNotExist()
        allPrayersSwitch().performClick()
        compose.onNodeWithText(message).assertExists()
        compose.onNodeWithText(context.getString(R.string.adhan_open_channel)).performScrollTo().performClick()
        val systemSettings = shadowOf(controller.get()).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, systemSettings.action)
        assertEquals(channelId, systemSettings.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test
    fun downgradedAdvanceChannelOnlyNeedsHigherImportanceForAnEnabledWarning() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        val warning = context.getString(R.string.adhan_channel_soon)
        val quiet = context.getString(R.string.adhan_channel_quiet)
        manager.createNotificationChannel(NotificationChannel(AdhanPlaybackService.CHANNEL_SOON, warning, NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(AdhanPlaybackService.CHANNEL_QUIET, quiet, NotificationManager.IMPORTANCE_LOW))
        controller.pause().resume()
        allPrayersSwitch().performClick()
        val message = context.getString(R.string.adhan_low_full_screen_channel, warning)
        compose.onNodeWithText(message).assertExists()
        compose.onNodeWithText(context.getString(R.string.adhan_low_full_screen_channel, quiet)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.adhan_open_channel)).performScrollTo().performClick()
        val channel = shadowOf(controller.get()).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, channel.action)
        assertEquals(AdhanPlaybackService.CHANNEL_SOON, channel.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        compose.onNodeWithText(context.getString(R.string.adhan_warn_before)).performScrollTo().performClick()
        compose.onNodeWithText(message).assertDoesNotExist()
    }

    @Test
    fun allPrayersSwitchEnablesAndDisablesEveryAdhan() {
        allPrayersSwitch().assertIsOff().performClick()

        allPrayersSwitch().assertIsOn()
        assertEquals(AdhanSettings.prayers, AdhanSettings.enabledPrayers(context))
        compose.onNodeWithText(AdhanSettings.prayerName(context, Prayer.JOMOAA)).assertIsOn()

        controller.recreate()
        compose.waitForIdle()
        allPrayersSwitch().assertIsOn()

        allPrayersSwitch().performClick()

        allPrayersSwitch().assertIsOff()
        assertEquals(emptyList<Prayer>(), AdhanSettings.enabledPrayers(context))
    }

    @Test
    fun individualSwitchUpdatesAllPrayersSwitchAndBulkEnableRestoresIt() {
        allPrayersSwitch().performClick()

        compose.onNodeWithText(AdhanSettings.prayerName(context, Prayer.FAJR)).performClick()

        allPrayersSwitch().assertIsOff()
        assertEquals(AdhanSettings.prayers - Prayer.FAJR, AdhanSettings.enabledPrayers(context))

        allPrayersSwitch().performClick()

        allPrayersSwitch().assertIsOn()
        assertEquals(AdhanSettings.prayers, AdhanSettings.enabledPrayers(context))
    }
}
