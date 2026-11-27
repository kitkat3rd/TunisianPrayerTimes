package com.tunisianprayertimes.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.AndroidAutoConnection
import com.tunisianprayertimes.AndroidAutoSilenceTest.ProjectionProvider
import com.tunisianprayertimes.DelayMode
import com.tunisianprayertimes.FirstRunPlaceChoice
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.mosque.MosqueSilenceSettings
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.platform.SharedAndroid
import com.tunisianprayertimes.wake.displayName
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w412dp-h900dp-mdpi", application = Application::class)
class PrayerHeroForecastTest {
    @get:Rule val compose = createComposeRule()
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val originalZone = TimeZone.getDefault()
    private val phoneZone = ZoneId.of("America/New_York")
    private lateinit var countdown: NextPrayerCountdownInfo
    private var repairs = 0
    private var manualActions = 0

    @Before fun configurePrayer() {
        TimeZone.setDefault(TimeZone.getTimeZone(phoneZone))
        SharedAndroid.init(context)
        val place = PrayerPlace.Inm(615)
        PrefsManager.initializeFirstRun(context)
        assertTrue(PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Browse(place, null)))
        assertTrue(PrefsManager.confirmSuggestedPlace(context, place))
        val day = LocalDate.of(2026, 11, 26)
        val prayer = PrayerTimesRepository.prayerOccurrences(context, day).single { it.prayer == Prayer.DHUHR }
        countdown = NextPrayerCountdownInfo(prayer.prayer, prayer.at, day, place.zone)
        PrefsManager.setConfig(context, Prayer.DHUHR, PrayerSilenceConfig(
            delayMode = DelayMode.FIXED_TIME, delayFixedHour = 12, delayFixedMinute = 24, afterMinutes = 18,
        ))
    }

    @After fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    private fun show(
        mode: AutomaticSilenceMode,
        enabled: Boolean = true,
        hasDnd: Boolean = true,
        issues: List<Int>? = emptyList(),
        current: PhoneSilenceReason? = null,
        next: NextPrayerCountdownInfo? = countdown,
        now: Instant = countdown.at.minusSeconds(3600),
        silenceActive: Boolean = false,
        displayZone: ZoneId = phoneZone,
        width: Dp = 412.dp,
        fontScale: Float = 1f,
    ) {
        TimeZone.setDefault(TimeZone.getTimeZone(displayZone))
        PrefsManager.setEnabled(context, enabled)
        MosqueSilenceSettings.saveMode(context, mode)
        compose.setContent {
            PrayerHeroPreviewFrame(width, fontScale) {
                NextPrayerHeroCard(
                    countdown = next,
                    prayerName = next?.prayer?.displayName(context) ?: context.getString(R.string.prayer_data_unavailable_title),
                    currentTimeMillis = now.toEpochMilli(),
                    isPhoneSilenced = current != null,
                    hasDnd = hasDnd,
                    silenceReason = current,
                    autoSilenceEnabled = enabled,
                    silenceActionPending = false,
                    silenceActive = silenceActive,
                    manualActionLabel = if (silenceActive) context.getString(R.string.manual_silence_action_stop)
                        else context.getString(R.string.manual_silence_action_duration, formatCountdownRemaining(30 * 60_000L, 0L)),
                    onChooseManualDuration = {},
                    configuredMode = mode,
                    setupIssues = issues,
                    onRepairSilence = { repairs++ },
                    phoneZone = displayZone,
                    onManualSilenceClick = { manualActions++ },
                )
            }
        }
    }

    // At this date the saved Tunis window (12:24–12:42) is six hours earlier on the phone.
    private fun assertWindowShown(resource: Int, prayer: Prayer = Prayer.DHUHR, start: Int = 6 * 60 + 24, end: Int = 6 * 60 + 42) {
        val message = context.getString(resource, prayer.displayName(context), prayerClockText(start), prayerClockText(end))
        compose.waitUntil(10_000) { compose.onAllNodesWithText(message, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(message, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun timeOnlyShowsTheScheduledWindowInThePhonesZone() {
        show(AutomaticSilenceMode.TIME_ONLY)
        assertWindowShown(R.string.next_prayer_silence_window)
        compose.onNodeWithTag("prayer_silence_repair").assertDoesNotExist()
    }

    @Test fun afterDhuhrAdhanItsDelayedSilenceIsNamedUnderTheAsrCountdown() {
        val day = countdown.prayerDay
        val asr = PrayerTimesRepository.prayerOccurrences(context, day).single { it.prayer == Prayer.ASR }
        val now = day.atTime(12, 23).atZone(countdown.placeZone).toInstant()
        assertTrue(countdown.at < now && now < asr.at)
        show(AutomaticSilenceMode.TIME_ONLY, next = NextPrayerCountdownInfo(Prayer.ASR, asr.at, day, countdown.placeZone), now = now)
        assertWindowShown(R.string.next_prayer_silence_window)
        compose.onNodeWithTag("next_prayer_summary").assertIsDisplayed()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun activeDhuhrShowsItsEndAlongsideTheLaterAsrForecastAndStopAction() {
        val day = countdown.prayerDay
        val zone = countdown.placeZone
        val now = day.atTime(12, 30).atZone(zone).toInstant()
        val asr = PrayerTimesRepository.prayerOccurrences(context, day).single { it.prayer == Prayer.ASR }
        PrefsManager.setConfig(context, Prayer.ASR, PrayerSilenceConfig(delayMinutes = 10, afterMinutes = 15))
        val current = requireNotNull(currentScheduledSilence(SilenceScheduler.undismissedWindows(context, now), now))
        assertEquals(Prayer.DHUHR, current.prayer)
        show(AutomaticSilenceMode.TIME_ONLY, current = current, next = NextPrayerCountdownInfo(Prayer.ASR, asr.at, day, zone),
            now = now, silenceActive = true, displayZone = zone, width = 360.dp)

        val asrMinute = asr.at.atZone(zone).toLocalTime().toSecondOfDay() / 60
        assertWindowShown(R.string.next_prayer_silence_window, Prayer.ASR, asrMinute + 10, asrMinute + 25)
        val active = context.getString(R.string.hero_current_scheduled_prayer, Prayer.DHUHR.displayName(context), "12:42")
        compose.onNodeWithText(active).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.manual_silence_action_stop)).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.HERO_SILENCE_DURATION).assertDoesNotExist()
        compose.savePrayerHeroPreview("active-schedule")
    }

    @Test fun androidAutoPausesTheMosqueForecastAndDisconnectionRestoresIt() {
        val projection = Robolectric.setupContentProvider(ProjectionProvider::class.java, AndroidAutoConnection.AUTHORITY)
        show(AutomaticSilenceMode.TIME_AND_MOSQUE)
        assertWindowShown(R.string.next_prayer_silence_mosque_window)

        projection.state = 2
        context.sendBroadcast(Intent(AndroidAutoConnection.ACTION_UPDATED))
        val paused = context.getString(R.string.next_prayer_silence_android_auto, context.getString(R.string.prayer_dhuhr))
        compose.waitUntil(10_000) { compose.onAllNodesWithText(paused).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(paused).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.HERO_PHONE_SILENCE_BUTTON).performClick()
        assertEquals(1, manualActions)

        projection.state = 0
        context.sendBroadcast(Intent(AndroidAutoConnection.ACTION_UPDATED))
        assertWindowShown(R.string.next_prayer_silence_mosque_window)
    }

    @Test fun mosqueWindowAndThePhonesCurrentSoundStatusCanBothBeRead() {
        show(AutomaticSilenceMode.TIME_AND_MOSQUE, current = PhoneSilenceReason(PhoneSilenceReasonSource.EXTERNAL))
        assertWindowShown(R.string.next_prayer_silence_mosque_window)
        compose.onNodeWithText(context.getString(R.string.phone_status_silent_external), substring = true).assertIsDisplayed()
    }

    @Test fun missingExactAlarmAccessOffersRepairInPlaceOfAWindow() {
        show(AutomaticSilenceMode.TIME_ONLY, issues = listOf(R.string.mosque_needs_exact))
        compose.onNodeWithTag("prayer_silence_forecast").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.mosque_needs_exact), substring = true).assertIsDisplayed()
        compose.onNodeWithTag("prayer_silence_repair").performClick()
        assertEquals(1, repairs)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun mosqueModeWithOnlyLocationUnavailableShowsFallbackAndRecovery() {
        show(AutomaticSilenceMode.TIME_AND_MOSQUE, issues = listOf(R.string.mosque_needs_location),
            displayZone = countdown.placeZone, width = 320.dp, fontScale = 1.8f)
        val fallback = context.getString(R.string.hero_location_fallback)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(fallback, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(fallback, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hero_complete_setup), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.mosque_needs_location), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(TestTags.HERO_PHONE_SILENCE_BUTTON).assertIsDisplayed()
        compose.onNodeWithTag(TestTags.HERO_SILENCE_DURATION).assertIsDisplayed()
        compose.savePrayerHeroPreview("location-recovery")
        compose.onNodeWithTag("prayer_silence_repair").performClick()
        assertEquals(1, repairs)
    }

    @Test fun locationRecoveryWhileNoForecastIsAvailableShowsOnlyTheKnownStatus() {
        show(AutomaticSilenceMode.TIME_AND_MOSQUE, issues = listOf(R.string.mosque_needs_background), next = null)
        compose.onNodeWithText(context.getString(R.string.hero_location_unavailable), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.mosque_needs_background), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.hero_location_fallback), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.hero_complete_setup), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("prayer_silence_repair").performClick()
        assertEquals(1, repairs)
    }

    @Test fun aDisabledPrayerWithLocationUnavailableDoesNotPromiseScheduledFallback() {
        PrefsManager.setPrayerSilenceEnabled(context, Prayer.DHUHR, false)
        show(AutomaticSilenceMode.TIME_AND_MOSQUE, issues = listOf(R.string.mosque_needs_precise))
        val off = context.getString(R.string.next_prayer_silence_off_prayer, Prayer.DHUHR.displayName(context))
        val message = context.getString(R.string.hero_location_unavailable_status, off)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(message, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(message, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hero_location_fallback), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun mosqueModeWithMissingDndCannotPromiseALocationFallback() {
        show(AutomaticSilenceMode.TIME_AND_MOSQUE, hasDnd = false,
            issues = listOf(R.string.mosque_needs_location, R.string.mosque_needs_dnd))
        compose.onNodeWithTag("prayer_silence_forecast").assertDoesNotExist()
        compose.onNodeWithTag("prayer_silence_repair").performClick()
        assertEquals(1, repairs)
    }

    @Test fun optedOutProfileShowsTheNextPrayerWithoutASetupPrompt() {
        show(AutomaticSilenceMode.TIME_ONLY, enabled = false, hasDnd = false, issues = null)
        compose.onNodeWithTag("next_prayer_summary").assertIsDisplayed()
        compose.onNodeWithTag("prayer_silence_repair").assertDoesNotExist()
        compose.onNodeWithTag("prayer_silence_forecast").assertDoesNotExist()
    }

    @Test fun unavailablePrayerTimesKeepManualSilenceAndSetupRecoveryReachable() {
        show(AutomaticSilenceMode.TIME_ONLY, issues = listOf(R.string.mosque_needs_exact), next = null)
        compose.onNodeWithTag("prayer_silence_forecast").assertDoesNotExist()
        compose.onNodeWithTag("prayer_silence_repair").assertIsDisplayed().performClick()
        compose.onNodeWithTag(TestTags.HERO_PHONE_SILENCE_BUTTON).assertIsDisplayed().performClick()
        assertEquals(1, repairs)
        assertEquals(1, manualActions)
    }
}
