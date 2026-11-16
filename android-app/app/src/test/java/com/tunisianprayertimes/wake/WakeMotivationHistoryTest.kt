package com.tunisianprayertimes.wake

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.RingtonePreset
import com.tunisianprayertimes.WakeMainAlarmConfig
import com.tunisianprayertimes.WakeMainAlarmMode
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WakeMotivationHistoryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("wake_motivation_history", Context.MODE_PRIVATE)
    private val now = Instant.parse("2026-11-01T14:00:00Z")
    private val occurrence = now.toEpochMilli()
    private val generic = setOf(WakeMotivationTopic.GENERAL)

    @Before
    fun clearHistory() {
        prefs.edit().clear().commit()
    }

    private fun payload(eventId: String = wakeMainEventId("rotation"), trigger: Long = occurrence) = WakeTriggerPayload(
        eventId = eventId, prayer = Prayer.FAJR, effectivePrayer = Prayer.FAJR,
        mainAlarmMode = WakeMainAlarmMode.FIXED_TIME,
        hour = 15, minute = 0, ringtone = RingtonePreset.DEFAULT_ALARM, vibrationOnly = false,
        wakeUpCheckEnabled = false, progressiveVolume = false, snoreTrackingEnabled = false,
        awakeCheckEnabled = true, isSubAlarm = eventId.startsWith("wake:sub:"),
        triggerAtMillis = trigger, occurrenceAtMillis = occurrence,
    )

    private fun select(key: String, topics: Set<WakeMotivationTopic> = generic) =
        WakeMotivationHistory.select(context, key, topics, "rotation", 1L)

    @Test
    fun mainThenEachExtraThenTheFinalAwakeCheckEachGetADifferentMessage() = runBlocking {
        val rings = listOf(
            payload(),
            payload(wakeSubAlarmEventId("rotation", "extra-1"), occurrence + 300_000L),
            payload(wakeSubAlarmEventId("rotation", "extra-2"), occurrence + 600_000L),
        )
        val messages = rings.map { resolveWakeMotivation(context, it, now) } + resolveAwakeCheckMotivation(
            context, rings.last().eventId, rings.last().triggerAtMillis + 180_000L, occurrence, now,
        )
        assertEquals(messages.size, messages.toSet().size)
        messages.forEach { assertEquals(generic, it.topics) }
    }

    @Test
    fun multipleOccurrencesOfTheSameTimerOnTheSameDayRotate() = runBlocking {
        val messages = (0L..5L).map { index ->
            val time = occurrence + index * 600_000L
            resolveWakeMotivation(context, payload(trigger = time).copy(occurrenceAtMillis = time), now)
        }
        assertEquals(messages.size, messages.toSet().size)
    }

    @Test
    fun differentParentAlarmsDoNotRepeatEvenWhenTheirDailySeedsCollide() = runBlocking {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val first = resolveWakeMotivation(context, payload(wakeMainEventId("Aa")), now)
        val second = resolveWakeMotivation(context, payload(wakeMainEventId("BB")), now)
        assertNotEquals(first, second)
    }

    @Test
    fun aFollowUpHasItsOwnIdentityEvenWithTheSameEventAndTimestamps() = runBlocking {
        val ring = resolveWakeMotivation(context, payload(), now)
        val check = resolveAwakeCheckMotivation(context, payload().eventId, occurrence, occurrence, now)
        val laterCheck = resolveAwakeCheckMotivation(context, payload().eventId, occurrence + 1L, occurrence, now)
        assertEquals(3, setOf(ring, check, laterCheck).size)
        assertEquals(check, resolveAwakeCheckMotivation(context, payload().eventId, occurrence, occurrence, now))
    }

    @Test
    fun savedSelectionsSurviveAReopenAndDoNotConsumeAnotherRotationSlot() {
        val first = select("first")
        val second = select("second")
        val stored = prefs.getString("selections", null)
        val reopenedContext = context.createConfigurationContext(context.resources.configuration)
        assertEquals(first, WakeMotivationHistory.select(reopenedContext, "first", generic, "rotation", 99L))
        assertEquals(stored, prefs.getString("selections", null))
        assertNotEquals(first, second)
        assertTrue(select("third") !in setOf(first, second))
    }

    @Test
    fun theEntireEligiblePoolIsShownBeforeRepeatingAndThenTheOldestReturns() {
        val pool = WakeMotivations.eligible(generic)
        val selected = pool.indices.map { select("occurrence-$it") }
        assertEquals(pool.toSet(), selected.toSet())
        assertEquals(selected.first(), select("next-cycle"))
        assertEquals(selected[1], select("next-cycle-2"))
    }

    @Test
    fun changingContextStillExcludesIneligibleMessagesAndPrefersUnseenEligibleOnes() {
        val allTopics = WakeMotivationTopic.entries.toSet()
        val allMessages = WakeMotivations.all.indices.map { select("all-$it", allTopics) }
        assertEquals(WakeMotivations.all.toSet(), allMessages.toSet())
        val oldestGeneral = allMessages.first { it.topics == generic }
        assertEquals(oldestGeneral, select("ordinary-alarm"))
    }

    @Test
    fun awakeChecksForFixedTimeAlarmsDoNotInheritThePlaceholderPrayer() = runBlocking {
        PrayerWakeRepository(context).saveWakeConfig(PrayerWakeConfig(
            id = "fixed", prayer = Prayer.FAJR,
            mainAlarm = WakeMainAlarmConfig(mode = WakeMainAlarmMode.FIXED_TIME),
        ))
        repeat(WakeMotivations.eligible(generic).size) { index ->
            val message = resolveAwakeCheckMotivation(context, wakeMainEventId("fixed"), index.toLong(), occurrence, now)
            assertEquals(generic, message.topics)
        }
    }

    @Test
    fun historyIsBoundedAndDamagedPreferencesDoNotPreventSelection() {
        repeat(120) { select("occurrence-$it") }
        assertEquals(96, JSONArray(prefs.getString("selections", null)).length())
        prefs.edit().putString("selections", "not-json").commit()
        assertTrue(select("after-corruption") in WakeMotivations.eligible(generic))
        assertEquals(1, JSONArray(prefs.getString("selections", null)).length())
    }
}
