package com.tunisianprayertimes.mosque.trace

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.mosque.SilenceWalkActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SilenceWalkAutoStartTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val system get() = shadowOf(app)

    @After fun releaseDatabase() = SilenceTraceStore.closeForTest()

    @Test fun startsOnEligibleForegroundAfterGrantWithoutPromptingOrDuplicating() {
        DebugWalkAutoStart.install(app)
        val unrelated = Robolectric.buildActivity(Activity::class.java).setup()
        system.grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        unrelated.pause().resume()
        assertNull(SilenceTraceStore.activeId(app))
        assertNull(system.nextStartedService)
        unrelated.pause().stop().destroy()

        system.denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        val walk = Robolectric.buildActivity(SilenceWalkActivity::class.java).setup()
        try {
            assertNull(SilenceTraceStore.activeId(app))
            assertNull(system.nextStartedService)
            assertNull(shadowOf(walk.get()).lastRequestedPermission)
            walk.windowFocusChanged(false)
            system.grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            walk.windowFocusChanged(true)
            shadowOf(Looper.getMainLooper()).idle()

            val id = requireNotNull(SilenceTraceStore.activeId(app))
            assertEquals(SilenceWalkRecorderService::class.java.name, system.nextStartedService?.component?.className)
            walk.pause().resume()
            assertEquals(id, SilenceTraceStore.activeId(app))
            assertNull(system.nextStartedService)

            // The screen's Stop must stick even if Android kills the process before delivery.
            SilenceWalkRecorderService.stop(walk.get(), id)
            requireNotNull(system.nextStartedService)
            walk.pause().stop()
            SilenceTraceStore.closeForTest()
            walk.restart().start().resume()
            assertNull(SilenceTraceStore.activeId(app))
            assertNull(system.nextStartedService)
        } finally {
            walk.pause().stop().destroy()
        }
    }

    @Test fun existingHistoryAllowsANewWalkAndAnInterruptedWalkRecoversOnVisibleReturn() {
        system.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val older = SilenceTraceStore.start(app)
        SilenceTraceStore.stop(app, older)
        SilenceTraceStore.closeForTest()

        DebugWalkAutoStart.install(app)
        assertNull(SilenceTraceStore.activeId(app))
        val walk = Robolectric.buildActivity(SilenceWalkActivity::class.java).setup()
        try {
            val interrupted = requireNotNull(SilenceTraceStore.activeId(app))
            assertNotEquals(older, interrupted)
            requireNotNull(system.nextStartedService)
            walk.pause().stop()
            SilenceTraceStore.closeForTest()
            assertNull(SilenceTraceStore.activeId(app))
            assertNull(system.nextStartedService)
            walk.restart().start().resume()
            val next = requireNotNull(SilenceTraceStore.activeId(app))
            assertNotEquals(interrupted, next)
            assertEquals("recording_interrupted", SilenceTraceStore.entries(app, interrupted).last().kind)
            assertEquals(listOf(next, interrupted, older), SilenceTraceStore.sessions(app).map { it.id })
        } finally {
            walk.pause().stop().destroy()
        }
    }

    @Test fun notificationStopSurvivesRestartAndAnOldStopCannotPauseANewerWalk() {
        system.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        DebugWalkAutoStart.install(app)
        val walk = Robolectric.buildActivity(SilenceWalkActivity::class.java).setup()
        val start = requireNotNull(system.nextStartedService)
        val service = Robolectric.buildService(SilenceWalkRecorderService::class.java, start).create()
        try {
            val original = requireNotNull(SilenceTraceStore.activeId(app))
            service.get().onStartCommand(start, 0, 1)
            val notification = requireNotNull(shadowOf(service.get()).lastForegroundNotification)
            val stopAction = notification.actions.single().actionIntent
            stopAction.send()
            val stop = requireNotNull(system.nextStartedService)
            service.get().onStartCommand(stop, 0, 2)
            assertNull(SilenceTraceStore.activeId(app))
            walk.pause().stop()
            SilenceTraceStore.closeForTest()
            walk.restart().start().resume()
            assertNull(SilenceTraceStore.activeId(app))
            assertNull(system.nextStartedService)

            assertTrue(SilenceWalkRecorderService.start(walk.get()))
            val manuallyStarted = requireNotNull(SilenceTraceStore.activeId(app))
            assertNotEquals(original, manuallyStarted)
            service.get().onStartCommand(requireNotNull(system.nextStartedService), 0, 3)
            service.get().onStartCommand(Intent(stop), 0, 4)
            assertEquals(manuallyStarted, SilenceTraceStore.activeId(app))
            walk.pause().stop()
            SilenceTraceStore.closeForTest()
            walk.restart().start().resume()
            val restarted = requireNotNull(SilenceTraceStore.activeId(app))
            assertNotEquals(manuallyStarted, restarted)
        } finally {
            service.destroy()
            walk.pause().stop().destroy()
        }
    }
}
