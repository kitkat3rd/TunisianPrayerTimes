package com.tunisianprayertimes

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.adhan.AdhanSettings
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.ZoneSource
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FirstRunSettingsTest {
    private lateinit var context: Context
    private lateinit var settings: SharedPreferences
    private val paris = PrayerPlace.Coordinates(4886000, 235000, null, "Europe/Paris", ZoneSource.CATALOGUE, "FR")
    private val sfax = Locality("sfax", "صفاقس", "", 1, 386, "صفاقس", manualReferenceId = "manual")

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        settings = context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
        settings.edit().clear().commit()
        setPackageDates(1000, 1000)
    }

    private fun setPackageDates(installed: Long, updated: Long) {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.firstInstallTime = installed
        info.lastUpdateTime = updated
        shadowOf(context.packageManager).installPackage(info)
    }

    @Test fun newInstallStartsQuietAndAnExplicitChangeSurvivesAnotherStartup() {
        PrefsManager.initializeFirstRun(context)
        assertTrue(PrefsManager.isFreshOnboarding(context))
        assertTrue(PrefsManager.isFirstLaunch(context))
        assertTrue(PrefsManager.isFirstRunSilencePending(context))
        assertFalse(PrefsManager.hasConfirmedPlace(context))
        assertFalse(PrefsManager.isFirstRunSetupSuggested(context))
        assertFalse(PrefsManager.isEnabled(context))
        assertFalse(PrefsManager.isAutoLocationUpdateEnabled(context))
        assertFalse(PrefsManager.isCallEndVibrationEnabled(context))
        assertEquals(ManualSilenceMode.DURATION, PrefsManager.getManualSilenceMode(context))
        assertEquals(30, PrefsManager.getManualSilenceDurationMinutes(context))
        PrefsManager.setManualSilenceMode(context, ManualSilenceMode.UNTIL_STOPPED)
        PrefsManager.setEnabled(context, true)
        PrefsManager.initializeFirstRun(context)
        assertEquals(ManualSilenceMode.UNTIL_STOPPED, PrefsManager.getManualSilenceMode(context))
        assertTrue(PrefsManager.isAutomaticSilenceRequested(context))
        assertFalse(PrefsManager.isEnabled(context))
        PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Local(sfax))
        assertTrue(PrefsManager.isEnabled(context))
    }

    @Test fun completedLegacyInstallKeepsInheritedSettingsAndExplicitOverrides() {
        settings.edit().putBoolean("first_launch_done", true).commit()
        PrefsManager.setAutoLocationUpdateEnabled(context, false)
        PrefsManager.initializeFirstRun(context)
        assertFalse(PrefsManager.isFirstLaunch(context))
        assertFalse(PrefsManager.isFirstRunSetupSuggested(context))
        assertFalse(PrefsManager.isFreshOnboarding(context))
        assertFalse(PrefsManager.isFirstRunSilencePending(context))
        assertTrue(PrefsManager.isEnabled(context))
        assertTrue(PrefsManager.isCallEndVibrationEnabled(context))
        assertFalse(PrefsManager.isAutoLocationUpdateEnabled(context))
    }

    @Test fun anUpdatedInstallWithoutAnySavedStateIsIneligibleForNewInstallMeasurement() {
        setPackageDates(1000, 2000)
        PrefsManager.initializeFirstRun(context)
        assertTrue(PrefsManager.isFirstLaunch(context))
        assertFalse(PrefsManager.isFreshOnboarding(context))
        assertTrue(PrefsManager.isFirstRunSilencePending(context))
    }

    @Test fun unfinishedLegacyTourWithSavedPlaceAndExplicitSettingsResumesAtTheTimetable() {
        PrefsManager.setGpsLocation(context, DelegationLocationResult.Success(Delegation(615, "", "", "")))
        PrefsManager.setCallEndVibrationEnabled(context, true)
        PrefsManager.initializeFirstRun(context)
        assertFalse(PrefsManager.isFirstLaunch(context))
        assertTrue(PrefsManager.hasConfirmedPlace(context))
        assertFalse(PrefsManager.isFreshOnboarding(context))
        assertFalse(PrefsManager.isFirstRunSilencePending(context))
        assertTrue(PrefsManager.isCallEndVibrationEnabled(context))
        assertFalse(PrefsManager.isAutoLocationUpdateEnabled(context))
        assertTrue(PrefsManager.getLocationSelection(context).fromGps)
    }

    @Test fun restoredFeatureSettingsSurviveAndDoNotImplyPlaceOrSilenceConsent() {
        context.getSharedPreferences("adhan_settings", Context.MODE_PRIVATE).edit().putBoolean("enabled_FAJR", true).commit()
        PrefsManager.markOnboardingRestored(context)
        PrefsManager.initializeFirstRun(context)
        assertTrue(AdhanSettings.isEnabled(context, Prayer.FAJR))
        assertFalse(PrefsManager.isEnabled(context))
        assertFalse(PrefsManager.hasConfirmedPlace(context))
        assertTrue(PrefsManager.isFirstLaunch(context))
        assertFalse(PrefsManager.isFreshOnboarding(context))
        assertFalse(PrefsManager.isFirstRunSilencePending(context))
    }

    @Test fun aPartialInstallKeepsTheRequestedScheduleDormantUntilAPlaceIsConfirmed() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.initializeFirstRun(context)
        assertFalse(PrefsManager.canUsePlaceForAutomation(context))
        PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Browse(PrayerPlace.Inm(615), null))
        assertFalse(PrefsManager.isEnabled(context))
        PrefsManager.dismissFirstRunSetup(context)
        assertFalse(PrefsManager.canUsePlaceForAutomation(context))
        PrefsManager.setPlaceProvisional(context, false)
        assertTrue(PrefsManager.isEnabled(context))
    }

    @Test fun interruptedLegacySkipDoesNotTurnBareDefaultTunisIntoAConfirmedPlace() {
        PrefsManager.setDelegationId(context, 615)
        PrefsManager.initializeFirstRun(context)
        assertEquals(PrayerPlace.Inm(615), PrefsManager.getPlace(context))
        assertFalse(PrefsManager.isFirstLaunch(context))
        assertFalse(PrefsManager.hasConfirmedPlace(context))
        assertFalse(PrefsManager.canUsePlaceForAutomation(context))
    }

    @Test fun anExistingScheduleForALegacySuggestionIsPreserved() {
        PrefsManager.markFirstLaunchDone(context)
        PrefsManager.setPlaceProvisional(context, true)
        PrefsManager.initializeFirstRun(context)
        assertFalse(PrefsManager.hasConfirmedPlace(context))
        assertTrue(PrefsManager.canUsePlaceForAutomation(context))
        assertTrue(PrefsManager.isEnabled(context))
        PrefsManager.setEnabled(context, false)
        PrefsManager.initializeFirstRun(context)
        assertFalse(PrefsManager.isEnabled(context))
    }

    @Test fun restoreCallbackAfterStartupCorrectsEligibilityToo() {
        PrefsManager.initializeFirstRun(context)
        assertTrue(PrefsManager.isFreshOnboarding(context))
        PrefsManager.markOnboardingRestored(context)
        PrefsManager.initializeFirstRun(context)
        assertFalse(PrefsManager.isFreshOnboarding(context))
        assertFalse(PrefsManager.isFirstRunSilencePending(context))
    }

    @Test fun aCopiedFeatureStoreAloneExcludesAnOtherwiseFreshLookingInstall() {
        context.getSharedPreferences("adhan_settings", Context.MODE_PRIVATE).edit().putBoolean("enabled_FAJR", true).commit()
        PrefsManager.initializeFirstRun(context)
        assertFalse(PrefsManager.isFreshOnboarding(context))
        assertTrue(PrefsManager.usesPlaceOnboarding(context))
        assertFalse(PrefsManager.isFirstRunSilencePending(context))
        assertFalse(PrefsManager.canUsePlaceForAutomation(context))
        assertTrue(AdhanSettings.isEnabled(context, Prayer.FAJR))
    }

    @Test fun providerMetadataDoesNotExcludeANewInstall() {
        context.getSharedPreferences("androidx.work.util.preferences", Context.MODE_PRIVATE).edit()
            .putBoolean("reschedule_needed", true).commit()
        context.filesDir.resolve("firebase-installation-metadata").writeText("{}")
        PrefsManager.initializeFirstRun(context)
        assertTrue(PrefsManager.isFreshOnboarding(context))
    }

    @Test fun cityAndSilenceAreIndependentPersistentDecisions() {
        PrefsManager.initializeFirstRun(context)
        PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Local(sfax))
        PrefsManager.initializeFirstRun(context)
        assertFalse(PrefsManager.isFirstLaunch(context))
        assertTrue(PrefsManager.hasConfirmedPlace(context))
        assertTrue(PrefsManager.isFirstRunSilencePending(context))
        assertFalse(PrefsManager.isEnabled(context))

        PrefsManager.declineFirstRunSilence(context)
        PrefsManager.declineFirstRunSilence(context)
        PrefsManager.initializeFirstRun(context)
        assertFalse(PrefsManager.isFirstRunSilencePending(context))
        assertFalse(PrefsManager.isEnabled(context))
        assertEquals("sfax", PrefsManager.getLocationSelection(context).localityId)
    }

    @Test fun continuingAfterEnablingSilencePreservesItsMasterSwitch() {
        PrefsManager.initializeFirstRun(context)
        PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Local(sfax))
        PrefsManager.setEnabled(context, true)
        PrefsManager.finishFirstRunSilenceOffer(context)
        PrefsManager.initializeFirstRun(context)
        assertFalse(PrefsManager.isFirstRunSilencePending(context))
        assertTrue(PrefsManager.isEnabled(context))
    }

    @Test fun declineClearsAPendingMasterAndOnlyALaterSettingsChoiceCanEnableIt() {
        PrefsManager.initializeFirstRun(context)
        PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Local(sfax))
        PrefsManager.setEnabled(context, true)
        val mode = AutomaticSilenceMode.TIME_ONLY

        PrefsManager.declineFirstRunSilence(context)
        PrefsManager.initializeFirstRun(context)
        assertFalse(PrefsManager.isAutomaticSilenceRequested(context))
        assertFalse(PrefsManager.isFirstRunSilencePending(context))
        assertFalse(PrefsManager.activateConfiguredSilence(context, mode, firstRun = true))
        assertTrue(PrefsManager.activateConfiguredSilence(context, mode, firstRun = false))
        PrefsManager.declineFirstRunSilence(context) // A stale onboarding screen cannot undo the later decision.
        assertTrue(PrefsManager.isEnabled(context))
        assertFalse(PrefsManager.isFirstRunSilencePending(context))
    }

    @Test fun confirmingABrowsedPlaceKeepsTheIndependentSilenceChoicePending() {
        PrefsManager.initializeFirstRun(context)
        PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Browse(paris, "Paris"))
        PrefsManager.initializeFirstRun(context)
        assertTrue(PrefsManager.isFirstRunSilencePending(context))
        assertFalse(PrefsManager.hasConfirmedPlace(context))
        assertFalse(PrefsManager.canUsePlaceForAutomation(context))
        assertTrue(PrefsManager.confirmSuggestedPlace(context, paris))
        assertTrue(PrefsManager.isFirstRunSilencePending(context))
        PrefsManager.finishFirstRunSilenceOffer(context)
        assertTrue(PrefsManager.hasConfirmedPlace(context))
        assertFalse(PrefsManager.isEnabled(context))
    }

    @Test fun anUnfinishedPreviousPlaceOnboardingStillGetsSilenceAfterAppUpdate() {
        setPackageDates(1000, 2000)
        settings.edit().putBoolean("place_onboarding_defaults_initialized", true)
            .putBoolean("fresh_place_onboarding", true).putBoolean("silence_enabled", false).commit()
        context.getSharedPreferences("adhan_settings", Context.MODE_PRIVATE).edit().putBoolean("enabled_FAJR", true).commit()
        PrefsManager.initializeFirstRun(context)
        assertTrue(PrefsManager.isFirstRunSilencePending(context))
        PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Local(sfax))
        PrefsManager.initializeFirstRun(context)
        assertTrue(PrefsManager.isFirstRunSilencePending(context))
    }

    @Test fun anAlreadyCompletedPreviousPlaceOnboardingSkipsTheNewOffer() {
        settings.edit().putBoolean("place_onboarding_defaults_initialized", true)
            .putBoolean("fresh_place_onboarding", true).putBoolean("first_launch_done", true)
            .putBoolean("silence_enabled", false).commit()
        PrefsManager.initializeFirstRun(context)
        assertFalse(PrefsManager.isFirstRunSilencePending(context))
        assertFalse(PrefsManager.isAutomaticSilenceRequested(context))
    }

    @Test fun anUnfinishedPreviousInstallWithAConfiguredMasterSkipsTheNewOffer() {
        settings.edit().putBoolean("place_onboarding_defaults_initialized", true)
            .putBoolean("fresh_place_onboarding", true).putBoolean("silence_enabled", true).commit()
        PrefsManager.initializeFirstRun(context)
        assertFalse(PrefsManager.isFirstRunSilencePending(context))
        assertTrue(PrefsManager.isAutomaticSilenceRequested(context))
    }

    @Test fun aPreviouslySelectedMosqueModeIsPreservedWhenTheMasterIsOff() {
        settings.edit().putBoolean("place_onboarding_defaults_initialized", true)
            .putBoolean("fresh_place_onboarding", true).putBoolean("silence_enabled", false).commit()
        val mosque = context.getSharedPreferences("mosque_silence_settings", Context.MODE_PRIVATE)
        mosque.edit().putString("mode", "TIME_AND_MOSQUE").commit()
        PrefsManager.initializeFirstRun(context)
        assertFalse(PrefsManager.isFirstRunSilencePending(context))
        assertFalse(PrefsManager.isAutomaticSilenceRequested(context))
        assertEquals("TIME_AND_MOSQUE", mosque.getString("mode", null))
    }

    @Test fun clearingSilenceOfferUpdatesOtherFirstRunScreensWithoutWakingLocationObservers() {
        PrefsManager.initializeFirstRun(context)
        PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Local(sfax))
        shadowOf(Looper.getMainLooper()).idle()
        var firstRunUpdates = 0
        var locationUpdates = 0
        val stopRouting = PrefsManager.observeFirstRunDecisions(context) { firstRunUpdates++ }
        val stopLocation = PrefsManager.observeLocationSelection(context) { locationUpdates++ }
        try {
            PrefsManager.finishFirstRunSilenceOffer(context)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, firstRunUpdates)
            assertEquals(0, locationUpdates)
        } finally {
            stopRouting()
            stopLocation()
        }
    }

    @Test fun browsingPersistsTheSuggestedPlaceAndFinishesWithoutConfirmingIt() {
        PrefsManager.initializeFirstRun(context)
        PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Browse(paris, "باريس"))
        PrefsManager.initializeFirstRun(context)
        assertFalse(PrefsManager.isFirstLaunch(context))
        assertFalse(PrefsManager.hasConfirmedPlace(context))
        assertTrue(PrefsManager.isFirstRunSetupSuggested(context))
        assertEquals(paris, PrefsManager.getPlace(context))
        assertEquals("باريس", PrefsManager.getPlaceNameSnapshot(context))
        PrefsManager.finishFirstRunSilenceOffer(context)
        PrefsManager.initializeFirstRun(context)
        assertFalse(PrefsManager.isFirstRunSilencePending(context))
        assertFalse(PrefsManager.hasConfirmedPlace(context))
        PrefsManager.setPlaceProvisional(context, false)
        assertTrue(PrefsManager.hasConfirmedPlace(context))
        PrefsManager.dismissFirstRunSetup(context)
        PrefsManager.initializeFirstRun(context)
        assertFalse(PrefsManager.isFirstRunSetupSuggested(context))
    }

    @Test fun aLateCompletionFromAnotherActivityCannotReplaceAConfirmedLocality() {
        PrefsManager.initializeFirstRun(context)
        assertTrue(PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Local(sfax)))
        assertFalse(PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Abroad(paris, "باريس", fromGps = true)))
        assertTrue(PrefsManager.hasConfirmedPlace(context))
        assertEquals(PrayerPlace.Inm(sfax.delegationId), PrefsManager.getPlace(context))
        assertEquals("manual", settings.getString("manual_reference_id", null))
    }

    @Test fun featurePickerReplacesOnlyAnUnconfirmedCityAndPreservesOtherChoices() {
        PrefsManager.initializeFirstRun(context)
        PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Browse(paris, "Paris"))
        assertFalse(PrefsManager.confirmSuggestedPlace(context, PrayerPlace.Inm(615)))
        assertTrue(PrefsManager.choosePlaceForFeature(context, FirstRunPlaceChoice.Local(sfax)))
        assertFalse(PrefsManager.choosePlaceForFeature(context, FirstRunPlaceChoice.Abroad(paris, "Paris", false)))
        assertTrue(PrefsManager.hasConfirmedPlace(context))
        assertEquals("sfax", PrefsManager.getLocationSelection(context).localityId)
        assertFalse(PrefsManager.isEnabled(context))
    }

    @Test fun completionNotifiesTheOtherActivitiesWhenAllLocationFieldsAlreadyMatch() {
        PrefsManager.initializeFirstRun(context)
        PrefsManager.setLocality(context, sfax)
        var observedCompletion = false
        val unsubscribe = PrefsManager.observeLocationSelection(context) { observedCompletion = !PrefsManager.isFirstLaunch(context) }
        try {
            PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Local(sfax))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(observedCompletion)
        } finally {
            unsubscribe()
        }
    }

    @Test fun aGpsChoiceKeepsItsOwnTimetableSourceAndRemovesManualReference() {
        PrefsManager.initializeFirstRun(context)
        PrefsManager.setLocality(context, sfax)
        val gps = DelegationLocationResult.Success(Delegation(615, "", "", ""), sfax)
        assertTrue(PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Gps(gps)))
        assertEquals(PrayerPlace.Inm(615), PrefsManager.getPlace(context))
        assertTrue(settings.getBoolean("location_from_gps", false))
        assertFalse(settings.contains("manual_reference_id"))
    }

    @Test fun aForeignCityChoiceClearsPreviousLocalityDetails() {
        PrefsManager.initializeFirstRun(context)
        PrefsManager.setLocality(context, sfax)
        assertTrue(PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Abroad(paris, "باريس", fromGps = false)))
        assertEquals(paris, PrefsManager.getPlace(context))
        assertFalse(settings.contains("locality_name"))
        assertTrue(PrefsManager.hasConfirmedPlace(context))
    }

    @Test fun browsingInTunisReplacesPreviousCoordinateDetails() {
        PrefsManager.initializeFirstRun(context)
        PrefsManager.setPlace(context, paris, "باريس", fromGps = true)
        assertTrue(PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Browse(PrayerPlace.Inm(615), null)))
        assertEquals(PrayerPlace.Inm(615), PrefsManager.getPlace(context))
        assertNull(PrefsManager.getPlaceNameSnapshot(context))
        assertFalse(PrefsManager.hasConfirmedPlace(context))
    }
}
