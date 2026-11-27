package com.tunisianprayertimes.mosque.trace

import android.Manifest
import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class SilenceWalkStepsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = shadowOf(app.getSystemService(SensorManager::class.java))
    private lateinit var steps: SilenceWalkSteps

    @After fun closeRecorder() {
        if (::steps.isInitialized) steps.close()
        SilenceTraceStore.closeForTest()
    }

    private fun sensor(type: Int): Sensor = ShadowSensor.newInstance(type).also { manager.addSensor(it) }

    private fun start(granted: Boolean = true): Long {
        if (granted) shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        else shadowOf(app).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        val id = SilenceTraceStore.start(app)
        steps = SilenceWalkSteps(app, id)
        steps.start()
        return id
    }

    private fun event(sensor: Sensor, at: Long, value: Float) =
        SensorEventBuilder.newBuilder().setSensor(sensor).setTimestamp(at).setValues(floatArrayOf(value)).build()

    private fun advance(millis: Long): Long {
        ShadowSystemClock.advanceBy(Duration.ofMillis(millis))
        return SystemClock.elapsedRealtimeNanos()
    }

    private fun entries(id: Long) = SilenceTraceStore.entries(app, id)

    @Test fun detectorIsPreferredAndBatchedEventsKeepTheirAcquisitionTime() {
        val detector = sensor(Sensor.TYPE_STEP_DETECTOR)
        val counter = sensor(Sensor.TYPE_STEP_COUNTER)
        val id = start()
        val started = entries(id).single { it.kind == "recording_started" }.at
        val listener = manager.listeners.single()
        assertTrue(manager.hasListener(listener, detector))
        assertFalse(manager.hasListener(listener, counter))
        assertTrue(entries(id).any { it.kind == "step_detector_started" })

        val first = advance(500)
        val second = advance(700)
        advance(4_000)
        listener.onSensorChanged(event(detector, SystemClock.elapsedRealtimeNanos() + 1_000_000_000L, 1f))
        listOf(first, first, second).forEach { listener.onSensorChanged(event(detector, it, 1f)) }
        val measured = entries(id).filter { it.kind == "walk_steps" }
        assertEquals(listOf("1", "1"), measured.map { it.detail })
        assertEquals(listOf(started + 500, started + 1_200), measured.map { it.at })
        assertTrue(measured.all { it.latitude == null && it.longitude == null })
    }

    @Test fun counterStartsFromItsFirstReadingAndRebasesAfterADeviceReset() {
        val counter = sensor(Sensor.TYPE_STEP_COUNTER)
        val id = start()
        val listener = manager.listeners.single()
        assertTrue(manager.hasListener(listener, counter))
        assertTrue(entries(id).any { it.kind == "step_counter_started" })
        assertFalse(entries(id).any { it.kind == "step_counter_ready" })

        fun reading(value: Float) = listener.onSensorChanged(event(counter, advance(1_000), value))
        reading(15_000f)
        assertTrue(entries(id).any { it.kind == "step_counter_ready" })
        assertFalse(entries(id).any { it.kind == "walk_steps" })
        reading(15_004f)
        reading(15_004f)
        reading(0f)
        reading(2f)
        assertEquals(listOf("4", "2"), entries(id).filter { it.kind == "walk_steps" }.map { it.detail })
    }

    @Test fun endedListenersAndReadingsBeforeTheNextSessionCannotAddSteps() {
        val detector = sensor(Sensor.TYPE_STEP_DETECTOR)
        val firstId = start()
        val old = manager.listeners.single()
        val previousTime = advance(1_000)
        old.onSensorChanged(event(detector, previousTime, 1f))
        steps.close()
        assertFalse(manager.hasListener(old))
        SilenceTraceStore.stop(app, firstId)
        val previous = entries(firstId)

        advance(500)
        val nextId = start()
        val current = manager.listeners.single()
        val nextTime = advance(1_000)
        old.onSensorChanged(event(detector, nextTime, 1f))
        current.onSensorChanged(event(detector, previousTime, 1f))
        assertFalse(entries(nextId).any { it.kind == "walk_steps" })
        current.onSensorChanged(event(detector, nextTime, 1f))
        assertEquals(listOf("1"), entries(nextId).filter { it.kind == "walk_steps" }.map { it.detail })
        assertEquals(previous, entries(firstId))
    }

    @Test fun missingActivityPermissionLeavesTheWalkActiveAndExplainsUnavailableSteps() {
        sensor(Sensor.TYPE_STEP_DETECTOR)
        val id = start(granted = false)
        assertUnavailable(id)
    }

    @Test fun aPhoneWithoutStepHardwareCanStillRecordItsRoute() {
        val id = start()
        assertUnavailable(id)
    }

    @Test fun aRejectedSensorRegistrationDoesNotClaimToBeMeasuringSteps() {
        sensor(Sensor.TYPE_STEP_DETECTOR)
        sensor(Sensor.TYPE_STEP_COUNTER)
        manager.setForceListenersToFail(true)
        val id = start()
        assertUnavailable(id)
    }

    private fun assertUnavailable(id: Long) {
        assertEquals(id, SilenceTraceStore.activeId(app))
        assertTrue(manager.listeners.isEmpty())
        assertTrue(entries(id).single { it.kind == "step_sensor_unavailable" }.detail.isNotBlank())
        assertNull(entries(id).firstOrNull { it.kind == "step_detector_started" || it.kind == "step_counter_started" })
        assertTrue(SilenceTraceStore.append(app, "route", "gps", latitude = 36.8, longitude = 10.2, sessionId = id))
    }
}
