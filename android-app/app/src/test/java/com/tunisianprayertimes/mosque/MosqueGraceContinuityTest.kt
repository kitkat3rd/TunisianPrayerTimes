package com.tunisianprayertimes.mosque

import android.app.Application
import android.content.Intent
import com.tunisianprayertimes.AndroidAutoConnection
import com.tunisianprayertimes.AndroidAutoSilenceTest.ProjectionProvider
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.SilenceModeController
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.silence.SilenceWindow
import java.time.Duration
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, shadows = [AvailablePlayServices::class],
    instrumentedPackages = ["com.google.android.gms.common"])
class MosqueGraceContinuityTest : MosqueGraceFixture() {
    @Test fun lateEndAlarmKeepsTheOriginalDeadline() {
        begin()
        confirm()
        endAlarm(45)

        assertTrue(requested())
        assertEquals(window.end.plusSeconds(900).toEpochMilli(), MosqueSilenceMonitor.endWall(context))
    }

    @Test fun disconnectingAndroidAutoCannotRestartAReleasedVisitDuringGrace() {
        val projection = Robolectric.setupContentProvider(ProjectionProvider::class.java, AndroidAutoConnection.AUTHORITY)
        begin()
        confirm()
        endAlarm()
        assertTrue(requested())

        projection.state = 2
        SilenceModeController.reconcileAndroidAutoSilence(context)
        assertFalse(requested())
        projection.state = 0
        at(1)
        // A new rule write journals its intent before validating the visit.
        assertFalse(SilenceCoordinator.set(context, SilenceOwner.MOSQUE, true))
        MosqueSilenceMonitor.reconcile(context, canStart = true, deliberate = true)
        assertFalse(requested())
    }

    @Test fun movingTheScheduledEndBreaksTheOldOccurrenceAuthorization() {
        PrefsManager.setConfig(context, Prayer.ISHA, PrayerSilenceConfig(afterMinutes = 30))
        begin()
        confirm()
        endAlarm()
        assertTrue(requested())

        PrefsManager.setConfig(context, Prayer.ISHA, PrayerSilenceConfig(afterMinutes = 25))
        val changed = requireNotNull(SilenceScheduler.windowOf(context, Prayer.ISHA, window.prayerDay))
        assertEquals(window.end.minusSeconds(300), changed.end)
        MosqueSilenceMonitor.reconcile(context, canStart = true)
        assertFalse(requested())
    }

    @Test fun overlappingNominalPrayersUseTheLaterEndAndKeepItsOwnDismissalTarget() {
        val isha = setEveningWindows(maghribEndAfterIshaStart = 1, ishaDuration = 3)
        begin(Prayer.MAGHRIB)
        confirm() // Both prayers are active.
        endAlarm()
        assertTrue(requested())
        assertTrue(MosqueSilenceMonitor.hasWindow(context))
        fix(60)
        fix(119)
        at(120)
        MosqueSilenceMonitor.reconcile(context)

        assertTrue(requested())
        assertFalse(MosqueSilenceMonitor.hasWindow(context))
        assertEquals(isha.end.plusSeconds(900).toEpochMilli(), MosqueSilenceMonitor.endWall(context))
        MosqueSilenceMonitor.skip(context, setOf(MosquePrayerActions.key(isha)))
        assertFalse(requested())
    }

    @Test fun staleAndPartialSkipActionsPreserveTheRemainingPrayerDuringGrace() {
        val isha = setEveningWindows(maghribEndAfterIshaStart = 2, ishaDuration = 2)
        begin(Prayer.MAGHRIB)
        assertEquals(isha.end, window.end)
        confirm()
        endAlarm()
        val staleKey = "${Prayer.ISHA}@${isha.prayerDay.minusDays(1)}"
        val maghribKey = MosquePrayerActions.key(window)
        val ishaKey = MosquePrayerActions.key(isha)

        fun skip(key: String) {
            val intent = Intent(MosqueControlReceiver.SKIP)
                .putStringArrayListExtra("occurrences", arrayListOf(key))
            MosqueControlReceiver().onReceive(context, intent)
        }
        fun targets() = AdaptiveMosqueMonitor.periods(context).targets.map(MosquePrayerActions::key).toSet()

        assertEquals(setOf(maghribKey, ishaKey), targets())
        skip(staleKey)
        assertFalse(MosqueSilenceMonitor.isDismissed(context, staleKey))
        assertEquals(setOf(maghribKey, ishaKey), targets())
        assertEquals(token, requireNotNull(plan()).token)
        assertTrue(requested())

        skip(maghribKey)
        assertTrue(MosqueSilenceMonitor.isDismissed(context, maghribKey))
        assertEquals(setOf(ishaKey), targets())
        assertEquals(token, requireNotNull(plan()).token)
        assertTrue(requested())
        skip(ishaKey)
        assertFalse(requested())
        assertNull(plan())
    }

    @Test fun missingAnEntireLaterPrayerCannotGrantAnotherGracePeriod() {
        val isha = setEveningWindows(maghribEndAfterIshaStart = -14, ishaDuration = 1)
        begin(Prayer.MAGHRIB)
        confirm()
        endAlarm()
        for (second in 60L..780L step 60L) fix(second)
        fix(825)
        assertTrue(requested())
        assertTrue(wall < isha.start)
        // The last fix is only 76 seconds old, but the next callback missed Isha entirely.
        endAlarm(901)
        assertTrue(wall > isha.end)
        assertFalse(requested())
        assertNull(plan())
    }

    @Test fun aNewNominalWindowCanSilenceAfterThePreviousVisitLostItsGrace() {
        val isha = setEveningWindows(maghribEndAfterIshaStart = -1, ishaDuration = 3)
        begin(Prayer.MAGHRIB)
        confirm()
        endAlarm()
        assertTrue(requested())
        SilenceCoordinator.releaseAutoRules(context, AutomaticSilencePause.ANDROID_AUTO)
        MosqueSilenceMonitor.reconcile(context)
        assertFalse(requested())

        at(60)
        assertEquals(isha.start, wall)
        token = requireNotNull(AdaptiveMosqueMonitor.reconcile(context, canStart = true)).token
        fix(60)
        fix(68)
        fix(76)
        assertTrue(requested())
        assertTrue(MosqueSilenceMonitor.hasWindow(context))
    }

    private fun setEveningWindows(maghribEndAfterIshaStart: Int, ishaDuration: Int): SilenceWindow {
        Prayer.entries.forEach {
            PrefsManager.setPrayerSilenceEnabled(context, it, it == Prayer.MAGHRIB || it == Prayer.ISHA)
        }
        PrefsManager.setConfig(context, Prayer.ISHA, PrayerSilenceConfig(afterMinutes = ishaDuration))
        val day = LocalDate.of(2026, 6, 1)
        val isha = requireNotNull(SilenceScheduler.windowOf(context, Prayer.ISHA, day))
        val maghrib = requireNotNull(SilenceScheduler.windowOf(context, Prayer.MAGHRIB, day))
        val offset = Duration.between(maghrib.adhan, isha.start).toMinutes().toInt() + maghribEndAfterIshaStart
        PrefsManager.setConfig(context, Prayer.MAGHRIB, PrayerSilenceConfig(endOffsetMinutes = offset))
        return isha
    }
}
