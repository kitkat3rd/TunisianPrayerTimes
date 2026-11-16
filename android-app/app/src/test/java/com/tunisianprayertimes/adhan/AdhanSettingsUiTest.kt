package com.tunisianprayertimes.adhan

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
