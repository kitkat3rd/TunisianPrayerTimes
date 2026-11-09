package com.tunisianprayertimes.adhan

import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.adhan.AdhanDecision.AppSilence
import com.tunisianprayertimes.adhan.AdhanDecision.Outcome
import com.tunisianprayertimes.adhan.AdhanDecision.Situation
import com.tunisianprayertimes.adhan.AdhanDecision.Window
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class AdhanDecisionTest {
    private val day = LocalDate.of(2026, 11, 9)
    private val adhan = 1_800_000_000_000L
    private val minute = 60_000L

    private fun situation(
        enabled: Boolean = true,
        lateMillis: Long = 2_000L,
        inCall: Boolean = false,
        userChoseAppSilence: Boolean = false,
        appSilence: AppSilence = AppSilence.NONE,
        phoneQuiet: Boolean = false,
    ) = Situation(enabled, lateMillis, inCall, userChoseAppSilence, appSilence, phoneQuiet)

    @Test
    fun anAdhanOnTimeOnARingingPhonePlays() {
        assertEquals(Outcome.PLAY, AdhanDecision.decide(situation()))
    }

    @Test
    fun aPrayerSwitchedOffSinceItsAlarmWasArmedGivesNothing() {
        assertEquals(Outcome.NOTHING, AdhanDecision.decide(situation(enabled = false)))
    }

    @Test
    fun thePhoneTheUserMadeQuietStaysQuiet() {
        assertEquals(Outcome.QUIET_SILENT_PHONE, AdhanDecision.decide(situation(phoneQuiet = true)))
        assertEquals(Outcome.QUIET_SILENT_PHONE, AdhanDecision.decide(situation(userChoseAppSilence = true)))
    }

    @Test
    fun aCallKeepsTheAdhanQuiet() {
        assertEquals(Outcome.QUIET_IN_CALL, AdhanDecision.decide(situation(inCall = true)))
    }

    @Test
    fun aLateAdhanDoesNotSoundAndAVeryLateOneShowsNothing() {
        assertEquals(Outcome.PLAY, AdhanDecision.decide(situation(lateMillis = AdhanDecision.LATE_SOUND_MILLIS)))
        assertEquals(Outcome.QUIET_LATE, AdhanDecision.decide(situation(lateMillis = AdhanDecision.LATE_SOUND_MILLIS + 1)))
        assertEquals(Outcome.NOTHING, AdhanDecision.decide(situation(lateMillis = AdhanDecision.LATE_NOTICE_MILLIS + 1)))
    }

    @Test
    fun itsOwnPrayerSilenceBegunWithItIsLiftedEvenThoughThePhoneIsSilencedByIt() {
        assertEquals(
            Outcome.LIFT_AND_PLAY,
            AdhanDecision.decide(situation(appSilence = AppSilence.OWN_FROM_ADHAN, phoneQuiet = true)),
        )
    }

    @Test
    fun anotherSilenceOfTheAppKeepsTheAdhanQuiet() {
        assertEquals(Outcome.QUIET_SILENT_PHONE, AdhanDecision.decide(situation(appSilence = AppSilence.OTHER, phoneQuiet = true)))
    }

    @Test
    fun atTheMosqueNothingShows() {
        assertEquals(Outcome.NOTHING, AdhanDecision.decide(situation(appSilence = AppSilence.AT_MOSQUE, phoneQuiet = true)))
    }

    @Test
    fun aWindowOfThisPrayerStartingWithItsAdhanIsItsOwn() {
        assertEquals(AppSilence.OWN_FROM_ADHAN, classify(listOf(Window(Prayer.MAGHRIB, day, adhan))))
        // Within the tolerance: the silence alarm can come a little before the adhan's.
        assertEquals(AppSilence.OWN_FROM_ADHAN, classify(listOf(Window(Prayer.MAGHRIB, day, adhan - 30_000L))))
    }

    @Test
    fun aWindowThatBeganBeforeTheAdhanOrBelongsElsewhereIsNotItsOwn() {
        // The user silences ten minutes before Maghrib: they meant to be at the mosque by its adhan.
        assertEquals(AppSilence.OTHER, classify(listOf(Window(Prayer.MAGHRIB, day, adhan - 10 * minute))))
        assertEquals(AppSilence.OTHER, classify(listOf(Window(Prayer.ASR, day, adhan + minute))))
        assertEquals(AppSilence.OTHER, classify(listOf(Window(Prayer.MAGHRIB, day.minusDays(1), adhan))))
        assertEquals(
            AppSilence.OTHER,
            classify(listOf(Window(Prayer.MAGHRIB, day, adhan), Window(Prayer.ASR, day, adhan - 60 * minute))),
        )
        // On but in no window: kept on between windows.
        assertEquals(AppSilence.OTHER, classify(emptyList()))
    }

    @Test
    fun noSilenceOrPresenceSilence() {
        assertEquals(AppSilence.NONE, classify(listOf(Window(Prayer.MAGHRIB, day, adhan)), autoActive = false))
        assertEquals(AppSilence.AT_MOSQUE, classify(emptyList(), combined = true))
    }

    private fun classify(windows: List<Window>, autoActive: Boolean = true, combined: Boolean = false) =
        AdhanDecision.appSilence(autoActive, combined, windows, Prayer.MAGHRIB, day, adhan)
}
