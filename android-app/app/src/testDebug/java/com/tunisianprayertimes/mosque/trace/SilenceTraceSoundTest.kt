package com.tunisianprayertimes.mosque.trace

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.AndroidAutoConnection
import com.tunisianprayertimes.AndroidAutoSilenceTest.ProjectionProvider
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceModeController
import com.tunisianprayertimes.mosque.SilenceCoordinator
import com.tunisianprayertimes.mosque.SilenceOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SilenceTraceSoundTest {
    @After fun releaseTraceDatabase() = SilenceTraceStore.closeForTest()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun grantDndAccess() {
        val notifications = context.getSystemService(NotificationManager::class.java)
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    @Test fun repeatedRuleReconciliationRecordsOnlyChangesSubmittedToAndroid() {
        assertTrue(SilenceCoordinator.enrollOrRepair(context))
        val session = SilenceTraceStore.start(context)
        try {
            assertTrue(SilenceCoordinator.setWake(context, true))
            assertTrue(SilenceCoordinator.setWake(context, true))
            assertTrue(SilenceCoordinator.setWake(context, false))
            assertTrue(SilenceCoordinator.setWake(context, false))
            val writes = SilenceTraceStore.entries(context, session).filter { it.kind in setOf("rule_requested", "rule_released") }
            assertEquals(listOf("rule_requested", "rule_released"), writes.map { it.kind })
            assertTrue(writes.all { it.detail == context.getString(SilenceOwner.WAKE.title) })
        } finally {
            SilenceTraceStore.stop(context, session)
        }
    }

    @Test fun legacyManualSilenceAndRestorationAreBoundedByTheRecording() {
        val session = SilenceTraceStore.start(context)
        try {
            assertTrue(SilenceModeController.setManualSilent(context))
            assertTrue(SilenceModeController.setManualNormal(context))
        } finally {
            SilenceTraceStore.stop(context, session)
        }
        assertTrue(SilenceModeController.setManualSilent(context))
        val events = SilenceTraceStore.entries(context, session)
        assertEquals(listOf("silence_requested", "sound_restore_requested"), events
            .filter { it.detail == context.getString(SilenceOwner.MANUAL.title) }.map { it.kind })
    }

    @Test fun projectionPauseIsRecordedForAnActiveRuleAndOncePerSuppressedEpisode() {
        val projection = Robolectric.setupContentProvider(ProjectionProvider::class.java, AndroidAutoConnection.AUTHORITY)
        assertTrue(SilenceCoordinator.enrollOrRepair(context))
        assertTrue(SilenceCoordinator.set(context, SilenceOwner.PRAYER, true))
        val session = SilenceTraceStore.start(context)
        try {
            projection.state = 2
            SilenceModeController.reconcileAndroidAutoSilence(context)
            assertTrue(SilenceCoordinator.set(context, SilenceOwner.PRAYER, true))
            assertTrue(SilenceCoordinator.set(context, SilenceOwner.MOSQUE, true))
            assertTrue(SilenceCoordinator.set(context, SilenceOwner.MOSQUE, true))
            assertTrue(SilenceCoordinator.set(context, SilenceOwner.MOSQUE, false))
            assertTrue(SilenceCoordinator.set(context, SilenceOwner.MOSQUE, true))
            val events = SilenceTraceStore.entries(context, session).filter { it.kind == "silence_paused" }
            assertEquals(3, events.size)
            assertTrue(events.all { it.detail.contains(context.getString(R.string.walk_context_car)) })
            assertEquals(1, events.count { it.detail.contains(context.getString(SilenceOwner.PRAYER.title)) })
        } finally {
            SilenceTraceStore.stop(context, session)
        }
        val next = SilenceTraceStore.start(context)
        try {
            assertTrue(SilenceCoordinator.set(context, SilenceOwner.MOSQUE, true))
            assertEquals(1, SilenceTraceStore.entries(context, next).count { it.kind == "silence_paused" })
        } finally {
            SilenceTraceStore.stop(context, next)
        }
    }
}
