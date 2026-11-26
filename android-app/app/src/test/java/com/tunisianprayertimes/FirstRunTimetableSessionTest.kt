package com.tunisianprayertimes

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.place.PrayerPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FirstRunTimetableSessionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var now = 4_000L
    private var allowed = true
    private val events = mutableListOf<Pair<String, String?>>()

    private fun open(fresh: Boolean = true, saved: Bundle? = null) =
        FirstRunTimetableSession.open(
            prefs = context.getSharedPreferences("test_analytics", Context.MODE_PRIVATE),
            freshOrdinaryLaunch = fresh,
            savedInstanceState = saved,
            collectionAllowed = { allowed },
            elapsedRealtime = { now },
            record = { name, bucket -> events += name to bucket },
        )

    @Test
    fun countsOnlyVisibleMatchingTimesAndKeepsPreviewDistinctAcrossRecreation() {
        val session = requireNotNull(open())
        val initial = PrefsManager.getPlace(context)
        now += 9_999
        session.timetableShown(context, initial, confirmed = true, hasPrayerTime = true)
        PrefsManager.completeFirstRun(context, FirstRunPlaceChoice.Browse(initial, label = null))
        session.timetableShown(context, initial, confirmed = true, hasPrayerTime = true)
        session.timetableShown(context, initial, confirmed = false, hasPrayerTime = false)
        session.timetableShown(context, initial, confirmed = false, hasPrayerTime = true)
        val state = Bundle().also(session::save)
        val restored = requireNotNull(open(fresh = false, saved = state))
        restored.timetableShown(context, initial, confirmed = false, hasPrayerTime = true)
        val chosen = PrayerPlace.Inm(999)
        PrefsManager.setDelegationId(context, chosen.delegationId)
        restored.timetableShown(context, initial, confirmed = true, hasPrayerTime = true)
        restored.timetableShown(context, chosen, confirmed = true, hasPrayerTime = false)
        now = 34_000
        restored.timetableShown(context, chosen, confirmed = true, hasPrayerTime = true)
        restored.timetableShown(context, chosen, confirmed = true, hasPrayerTime = true)
        assertEquals(
            listOf("first_run_started" to null, "first_suggested_timetable" to "0_9s", "first_confirmed_timetable" to "30_59s"),
            events,
        )
        assertNull(open(saved = state))
        assertNull(open())
    }

    @Test
    fun laterLaunchCannotCompleteAnAbandonedFirstLaunch() {
        assertNull(open(fresh = false))
        assertNull(open(saved = Bundle()))
        requireNotNull(open())
        assertNull(open())
        assertNull(open(fresh = false, saved = Bundle()))
        assertEquals(listOf("first_run_started" to null), events)
    }

    @Test
    fun excludedRegionOnFirstLaunchCannotLaterEnroll() {
        allowed = false
        assertNull(open())
        allowed = true
        assertNull(open())
        assertEquals(emptyList<Pair<String, String?>>(), events)
    }

    @Test
    fun firstValueIsNeverBackfilledAfterSelectingAPlaceWithCollectionDisabled() {
        val session = requireNotNull(open())
        val shown = PrefsManager.getPlace(context)
        PrefsManager.markFirstLaunchDone(context)
        allowed = false
        session.timetableShown(context, shown, confirmed = true, hasPrayerTime = true)
        allowed = true
        session.timetableShown(context, shown, confirmed = true, hasPrayerTime = true)
        assertEquals(listOf("first_run_started" to null), events)
    }
}
