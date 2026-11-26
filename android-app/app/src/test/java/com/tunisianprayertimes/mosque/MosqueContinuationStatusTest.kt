package com.tunisianprayertimes.mosque

import android.app.Application
import android.text.format.DateFormat
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode
import com.tunisianprayertimes.mosque.presence.PresenceState
import com.tunisianprayertimes.mosque.presence.WindowCoverage
import com.tunisianprayertimes.silence.EndResolution
import com.tunisianprayertimes.silence.SilenceWindow
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MosqueContinuationStatusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun checkingDepartureAfterCutoffShowsLatestEndAndLetsTheUserSkipTheHeldPrayer() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val adhan = Instant.parse("2026-03-10T18:30:00Z")
        val prayer = SilenceWindow(Prayer.ISHA, LocalDate.parse("2026-03-10"), adhan, adhan,
            adhan.plusSeconds(20 * 60), EndResolution.AS_SET, false)
        val key = MosquePrayerActions.key(prayer)
        val cap = prayer.end.toEpochMilli() + MosquePeriods.CONTINUATION_MILLIS
        val periods = MosquePeriods(WindowCoverage(adhan.toEpochMilli(), cap, setOf(key)), null, listOf(prayer),
            continuationEnd = cap)
        var skipped = emptySet<String>()
        compose.setContent {
            MosqueSilenceStatus(AutomaticSilenceMode.TIME_AND_MOSQUE, enabled = true, issues = emptyList(),
                state = PresenceState.EXITING, periods = periods, phoneSilent = true, releasePending = false,
                canUndo = false, onRecheck = {}, onSkip = { skipped = it }, onManual = {}, onUndo = {})
        }
        val capClock = DateFormat.getTimeFormat(app).format(Date(cap))
        compose.onNodeWithText(app.getString(R.string.mosque_continuation_until, capClock)).assertExists()
        compose.onNodeWithText(app.getString(R.string.mosque_adaptive_early)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.mosque_recheck)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.mosque_adaptive_manual, MosquePrayerActions.label(app, prayer)))
            .assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.mosque_adaptive_skip, MosquePrayerActions.name(app, prayer)))
            .performClick()
        assertEquals(setOf(key), skipped)
    }
}
