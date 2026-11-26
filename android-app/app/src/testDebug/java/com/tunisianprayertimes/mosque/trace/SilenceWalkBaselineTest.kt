package com.tunisianprayertimes.mosque.trace

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.MosquePeriods
import com.tunisianprayertimes.silence.EndResolution
import com.tunisianprayertimes.silence.SilenceWindow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SilenceWalkBaselineTest {
    @After fun releaseTraceDatabase() = SilenceTraceStore.closeForTest()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val adhan = Instant.parse("2026-06-01T12:00:00Z")
    private val zone = ZoneId.of("Africa/Tunis")
    private val lateWindow = SilenceWindow(Prayer.DHUHR, LocalDate.parse("2026-06-01"), adhan,
        adhan.plusSeconds(1200), adhan.plusSeconds(2400), EndResolution.AS_SET, false)

    @Test fun aWalkBetweenEarlyObservationAndLatePrayerReportsTheGapAndUpcomingWindow() {
        val lines = SilenceWalkBaseline.schedule(context, listOf(lateWindow), adhan, zone, combined = true)
        assertTrue(context.getString(R.string.walk_schedule_no_watch) in lines)
        assertTrue(context.getString(R.string.walk_schedule_no_silence) in lines)
        assertTrue(lines.any { it.contains("13:20 +01:00") && it.contains("13:40 +01:00") && it.contains("13:55 +01:00") })
    }

    @Test fun timeOnlyWalkDoesNotSuggestMosqueObservationIsRunning() {
        val lines = SilenceWalkBaseline.schedule(context, listOf(lateWindow), lateWindow.start, zone, combined = false)
        assertTrue(lines.single().contains(context.getString(R.string.prayer_dhuhr)))
        assertFalse(context.getString(R.string.walk_schedule_no_silence) in lines)
    }

    @Test fun recordingStartedDuringAConfirmedContinuationShowsTheNominalEndAndTheLatestRelease() {
        val now = lateWindow.end.plusSeconds(90)
        // A clock correction can make the elapsed-time ceiling arrive before the scheduled cap.
        val cap = lateWindow.end.plusSeconds(600).toEpochMilli()
        val windows = listOf(lateWindow)
        val observed = MosquePeriods.from(windows, now.toEpochMilli()).copy(targets = windows, continuationEnd = cap)
        val lines = SilenceWalkBaseline.schedule(context, windows, now, zone, combined = true, observedPeriods = observed)
        assertTrue(lines.any { it.contains("13:40 +01:00") && it.contains("13:50 +01:00") && it.contains(context.getString(R.string.prayer_dhuhr)) })
        assertFalse(context.getString(R.string.walk_schedule_no_watch) in lines)
        assertFalse(context.getString(R.string.walk_schedule_no_silence) in lines)
    }

    @Test fun aWalkWithAutomaticSilenceOffRecordsThatSettingWithoutTurningItOn() {
        PrefsManager.setEnabled(context, false)
        val id = SilenceTraceStore.start(context)
        try {
            SilenceWalkBaseline.capture(context, id)
            val entries = SilenceTraceStore.entries(context, id)
            assertTrue(entries.any { it.kind == "test_context" && it.detail.contains(context.getString(R.string.mosque_master_off)) })
            assertFalse(entries.any { it.kind == "prayer_schedule" })
            assertFalse(PrefsManager.isAutomaticSilenceRequested(context))
        } finally {
            SilenceTraceStore.stop(context, id)
            SilenceTraceStore.delete(context, id)
        }
    }
}
