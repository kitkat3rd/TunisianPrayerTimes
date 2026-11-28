package com.tunisianprayertimes.mosque

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAlarmManager

@Implements(AlarmManager::class)
class MosqueDeadlineAlarmShadow : ShadowAlarmManager() {
    var beforeSet: ((Long) -> Unit)? = null
    var beforeCancel: (() -> Unit)? = null

    @Implementation
    override fun setExactAndAllowWhileIdle(type: Int, elapsed: Long, operation: PendingIntent) {
        if (shadowOf(operation).savedIntent.action == MosqueControlReceiver.DEADLINE) beforeSet?.invoke(elapsed)
        super.setExactAndAllowWhileIdle(type, elapsed, operation)
    }

    @Implementation
    override fun cancel(operation: PendingIntent) {
        if (shadowOf(operation).savedIntent.action == MosqueControlReceiver.DEADLINE) beforeCancel?.invoke()
        super.cancel(operation)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class,
    shadows = [AvailablePlayServices::class, MosqueDeadlineAlarmShadow::class],
    instrumentedPackages = ["com.google.android.gms.common"])
class MosqueReleaseDeadlineTest : MosqueGraceFixture() {
    private val alarms get() = Shadow.extract<MosqueDeadlineAlarmShadow>(context.getSystemService(AlarmManager::class.java))

    @Test fun refusedExpiryAlarmCannotLeaveATemporaryMosqueRuleActive() {
        begin(secondsBeforeEnd = 180)
        val requestedAtAlarmWrite = mutableMapOf<Long, Boolean>()
        alarms.beforeSet = { deadline ->
            requestedAtAlarmWrite[deadline] = requested()
            throw SecurityException("exact alarm access revoked during registration")
        }
        fix(-180, inside = false)
        fix(-145)
        assertEquals(false, requestedAtAlarmWrite[SystemClock.elapsedRealtime() + 45_000])
        assertFalse(requestedAtAlarmWrite.containsValue(true))
        assertFalse(requested())
    }

    @Test fun anEarlierPendingWakeIsKeptAndADeliveredWakeCanBeRearmed() {
        val first = SystemClock.elapsedRealtime() + 30_000
        val later = first + 20_000
        // An old process or boot may have left the previous version's journal behind.
        context.getSharedPreferences("mosque_presence", Context.MODE_PRIVATE).edit()
            .putLong("deadline_elapsed", first).commit()
        fun pending() = alarms.scheduledAlarms.single {
            shadowOf(it.operation).savedIntent.action == MosqueControlReceiver.DEADLINE
        }.triggerAtTime
        assertTrue(MosqueSilenceMonitor.armDeadline(context, first))
        assertEquals(first, pending())
        assertTrue(MosqueSilenceMonitor.armDeadline(context, later))
        assertEquals(first, pending())
        MosqueSilenceMonitor.deadlineDelivered(context)
        assertTrue(MosqueSilenceMonitor.armDeadline(context, later))
        assertEquals(later, pending())
    }

    @Test fun stoppingAVisitReleasesTheRuleBeforeCancellingItsWakeup() {
        begin()
        confirm()
        val requestedAtCancellation = mutableListOf<Boolean>()
        alarms.beforeCancel = { requestedAtCancellation.add(requested()) }
        MosqueSilenceMonitor.stopLocked(context, needsRearm = false)
        assertEquals(listOf(false), requestedAtCancellation)
        alarms.beforeCancel = null
    }
}
