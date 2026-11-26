package com.tunisianprayertimes.mosque.trace

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.tunisianprayertimes.mosque.SilenceTraceReplay
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SilenceTraceReplayTest {
    @get:Rule val compose = createComposeRule()

    private val fix = TraceEntry(1, 1_000L, "route", "GPS", 36.8, 10.2, 7.0)

    @Test fun unobservedGapsAndFeatureReadingsCannotBecomeRouteSegments() {
        assertTrue(fix.copy(at = fix.at + TRACE_ROUTE_MAX_GAP_MS).connectsRouteFrom(fix))
        assertFalse(fix.copy(at = fix.at + TRACE_ROUTE_MAX_GAP_MS + 1).connectsRouteFrom(fix))
        assertFalse(fix.copy(at = 10L).connectsRouteFrom(fix))
        assertFalse(fix.copy(kind = "monitor_fix").connectsRouteFrom(fix))
        assertFalse(fix.connectsRouteFrom(fix.copy(kind = "test_arrived")))
        assertFalse(fix.connectsRouteFrom(fix.copy(latitude = null)))
    }

    @Test fun precedingRouteContextExpiresAndClearsAcrossProviderLoss() {
        fun event(id: Long, at: Long, kind: String = "device_audio") =
            TraceEntry(id, at, kind, "", null, null, null)
        val data = listOf(
            event(0, fix.at), fix, event(2, fix.at), event(3, fix.at + 15_000),
            event(4, fix.at + 15_001), fix.copy(id = 5, at = 17_000, kind = "monitor_fix"),
            event(6, 17_001), fix.copy(id = 7, at = 18_000), event(8, 18_001, "route_provider"),
            event(9, 18_002), fix.copy(id = 10, at = 19_000), event(11, 19_001),
        )
        assertArrayEquals(intArrayOf(-1, -1, 1, 1, -1, -1, -1, -1, -1, -1, -1, 10), nearbyRouteIndices(data))
    }

    @Test fun unlocatedEventsOnlyShowFreshRouteContextAndLabelItIndependently() {
        val recent = fix.copy(id = 2, at = 2_000L, kind = "rule_requested", detail = "تجربة",
            latitude = null, longitude = null, accuracyMeters = null)
        val stop = fix.copy(id = 3, at = 28_000L, kind = "rule_released", detail = "تجربة",
            latitude = null, longitude = null, accuracyMeters = null)
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SilenceTraceReplay(TraceSession(1, 0L, 28_000L, "Africa/Tunis"), listOf(fix, recent, stop))
            }
        }
        compose.onNodeWithText("بلا إحداثيات مسجّلة لهذا الحدث.").assertExists()
        compose.onNodeWithText("السابق").performScrollTo().performClick()
        compose.onNodeWithText("36.800000, 10.200000", substring = true).assertExists()
        compose.onNodeWithText("قبل الحدث بـ 1.0 ثانية", substring = true).assertExists()
        compose.onNodeWithText("الأحداث فقط").performScrollTo().performClick()
        compose.onNodeWithText("التالي").performScrollTo().performClick()
        compose.onNodeWithText("بلا إحداثيات مسجّلة لهذا الحدث.").assertExists()
        compose.onNodeWithText("36.800000, 10.200000", substring = true).assertDoesNotExist()
    }
}
