package com.tunisianprayertimes.wake

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import com.tunisianprayertimes.RingtonePreset
import com.tunisianprayertimes.WakePlaybackOptions
import com.tunisianprayertimes.ui.WakeSoundControls
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import java.io.File
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WakeAlarmTimingUiTest {
    @get:Rule val compose = createComposeRule()

    private fun payload() = WakeTriggerPayload(
        eventId = wakeMainEventId("late-alarm"), prayer = Prayer.FAJR, effectivePrayer = Prayer.FAJR,
        hour = 5, minute = 0, ringtone = RingtonePreset.DEFAULT_ALARM, vibrationOnly = false,
        wakeUpCheckEnabled = false, progressiveVolume = true, snoreTrackingEnabled = false,
        awakeCheckEnabled = false, isSubAlarm = false,
    )

    @Test
    fun delayedQueuedAlarmShowsTheLiveWallClockAndKeepsUpdating() {
        var nowMillis = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 6)
            set(Calendar.MINUTE, 42)
            set(Calendar.SECOND, 15)
        }.timeInMillis
        compose.setContent {
            TunisianPrayerTimesTheme {
                WakeAlertScreen(payload(), pendingCount = 2, motivation = null, clock = { nowMillis }, onStop = {})
            }
        }
        compose.onNodeWithTag("wake_alarm_clock").assertTextEquals(formatWakeTime(6, 42))
        nowMillis += 60_000L
        compose.mainClock.advanceTimeBy(1_100L)
        compose.onNodeWithTag("wake_alarm_clock").assertTextEquals(formatWakeTime(6, 43))
    }

    @Test
    fun soundSettingsOfferDefaultsAndSaveBothTimingsIndependently() {
        var playback by mutableStateOf(WakePlaybackOptions())
        val context = RuntimeEnvironment.getApplication()
        compose.setContent {
            TunisianPrayerTimesTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    WakeSoundControls("النغمة", playback) { playback = it }
                }
            }
        }
        fun choice(tag: String, label: String) = compose.onNode(
            hasText(label) and hasAnyAncestor(hasTestTag(tag)),
        )
        choice("wake_initial_vibration", context.getString(R.string.wake_editor_seconds_short, 30)).assertIsSelected()
        choice("wake_volume_step_interval", context.getString(R.string.wake_editor_minutes_short, 1)).assertIsSelected()
        compose.waitForIdle()
        val image = compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().last { it.isAttachedToWindow && it.width > 0 }
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File("build/reports/wake-timing/sound-settings.png").apply {
            parentFile!!.mkdirs()
            outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        image.recycle()
        choice("wake_initial_vibration", context.getString(R.string.wake_editor_no_initial_vibration))
            .performScrollTo().performClick()
        choice("wake_volume_step_interval", context.getString(R.string.wake_editor_seconds_short, 30))
            .performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(0, playback.initialVibrationSeconds)
            assertEquals(30, playback.volumeStepIntervalSeconds)
        }
        compose.onNodeWithText(context.getString(R.string.wake_editor_vibration_only_title)).performScrollTo().performClick()
        choice("wake_initial_vibration", context.getString(R.string.wake_editor_no_initial_vibration)).assertIsNotEnabled()
        choice("wake_volume_step_interval", context.getString(R.string.wake_editor_seconds_short, 30)).assertIsNotEnabled()
    }
}
