package com.tunisianprayertimes.adhan

import com.tunisianprayertimes.adhan.AdhanDecision.AppSilence
import com.tunisianprayertimes.adhan.AdhanDecision.Outcome
import com.tunisianprayertimes.adhan.AdhanDecision.Situation
import org.junit.Assert.assertEquals
import org.junit.Test

class AdhanDecisionTest {
    private fun situation(
        enabled: Boolean = true,
        lateMillis: Long = 2_000L,
        inCall: Boolean = false,
        userChoseAppSilence: Boolean = false,
        appSilence: AppSilence = AppSilence.NONE,
        phoneQuiet: Boolean = false,
        volumeZero: Boolean = false,
        alarmRinging: Boolean = false,
    ) = Situation(enabled, lateMillis, inCall, userChoseAppSilence, appSilence, phoneQuiet, volumeZero, alarmRinging)

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
    fun aZeroAdhanVolumeOnARingingPhoneSaysSoInsteadOfPlayingMuted() {
        assertEquals(Outcome.QUIET_ZERO_VOLUME, AdhanDecision.decide(situation(volumeZero = true)))
        // A silent phone reads zero too: it keeps its own reason.
        assertEquals(Outcome.QUIET_SILENT_PHONE, AdhanDecision.decide(situation(phoneQuiet = true, volumeZero = true)))
    }

    @Test
    fun aRingingAlarmKeepsTheAdhanQuiet() {
        assertEquals(Outcome.QUIET_ALARM_RINGING, AdhanDecision.decide(situation(alarmRinging = true)))
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
    fun everyAutomaticSilenceKeepsTheAdhanQuiet() {
        assertEquals(
            Outcome.QUIET_PRAYER_SILENCE,
            AdhanDecision.decide(situation(appSilence = AppSilence.AUTOMATIC, phoneQuiet = true)),
        )
    }

    @Test
    fun aDueSilenceWindowKeepsTheAdhanQuietBeforeTheSilenceAlarmArrives() {
        val silence = AdhanDecision.appSilence(autoActive = false, combined = false, windowActive = true)
        assertEquals(Outcome.QUIET_PRAYER_SILENCE, AdhanDecision.decide(situation(appSilence = silence, phoneQuiet = false)))
    }

    @Test
    fun mosqueSilenceKeepsTheAdhanQuietButStillShowsThePrayer() {
        assertEquals(Outcome.QUIET_PRAYER_SILENCE, AdhanDecision.decide(situation(appSilence = AppSilence.AT_MOSQUE, phoneQuiet = true)))
    }

    @Test
    fun automaticSilenceRemainsAuthoritativeBetweenWindows() {
        val silence = AdhanDecision.appSilence(autoActive = true, combined = false, windowActive = false)
        assertEquals(Outcome.QUIET_PRAYER_SILENCE, AdhanDecision.decide(situation(appSilence = silence)))
    }

    @Test
    fun aCombinedObservationWindowAloneDoesNotSilenceTheAdhan() {
        val silence = AdhanDecision.appSilence(autoActive = false, combined = true, windowActive = true)
        assertEquals(Outcome.PLAY, AdhanDecision.decide(situation(appSilence = silence)))
    }

    @Test
    fun noSilenceOrPresenceSilence() {
        assertEquals(AppSilence.NONE, AdhanDecision.appSilence(autoActive = false, combined = false, windowActive = false))
        assertEquals(AppSilence.AT_MOSQUE, AdhanDecision.appSilence(autoActive = true, combined = true, windowActive = true))
    }
}
