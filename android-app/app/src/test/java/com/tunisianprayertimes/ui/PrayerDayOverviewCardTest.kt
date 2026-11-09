package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What TalkBack reads on the day dial: the altitude line first, then the day in its order from Fajr round to the
 * night's last third and its alarms, each name with its time, each alarm once, and none of the words that only
 * name the picture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h900dp-xxhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrayerDayOverviewCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theDialIsReadInTheDaysOrderEachItemOnce() {
        compose.setContent {
            TunisianPrayerTimesTheme(fontFamily = AppFontFamily) {
                PrayerDayOverviewCard(dayWithWakeAlarms()) {}
            }
        }
        val read = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TraversalIndex))
            .fetchSemanticsNodes()
            .sortedBy { it.config[SemanticsProperties.TraversalIndex] }
            .map { node ->
                val description = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map(AnnotatedString::text)
                (description + text).joinToString(" ")
            }
        val expected = listOf("06:18", "07:48", "12:36", "14:55", "17:19", "18:49", "21:39", "01:59", "04:33", "04:38", "04:43", "04:50")
        val firstTimes = read.drop(1).map { line -> Regex("\\d\\d:\\d\\d").find(line)?.value }
        assertTrue("the altitude line comes first: ${read.firstOrNull()}", read.first().contains("ارتفاع الشمس"))
        assertEquals(expected, firstTimes)
        // Names and times are read together.
        assertTrue(read.any { it.contains("الفجر") && it.contains("06:18") })
        assertTrue(read.any { it.contains("الثلث الأخير") && it.contains("01:59") })
        // Each alarm once, and the picture's words not at all.
        listOf("04:33", "04:38", "04:43", "04:50").forEach { time ->
            val mentions = compose.onAllNodes(SemanticsMatcher("mentions $time") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains(time) }
            }).fetchSemanticsNodes().size
            assertEquals("$time is read once", 1, mentions)
        }
        // TalkBack stops on anything that can be tapped: none of it is an unnamed button (a dot whose time is written
        // beside it was one, between the top line and Fajr).
        compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
            val words = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().map(AnnotatedString::text)
            assertTrue("unnamed button at ${node.boundsInRoot}", words.any { it.isNotBlank() })
        }
        listOf("النهار", "الليل", DIAL_FAJR_TIME).forEach { word ->
            compose.onAllNodes(SemanticsMatcher("reads $word") { node ->
                node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == word }
            }).fetchSemanticsNodes().let { assertTrue("$word is not read", it.isEmpty()) }
        }
    }

    /**
     * At Android's largest text size a crowded day has names the dial cannot hold: they are listed under it, and
     * TalkBack still reads everything in the day's order, each prayer once.
     */
    @Test
    fun atTheLargestTextSizeTheListedNamesAreReadInTheirPlace() {
        compose.setContent {
            TunisianPrayerTimesTheme(fontFamily = AppFontFamily) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    // The page's 16 dp margins, as in the prayer tab.
                    Box(Modifier.width(360.dp).padding(horizontal = 16.dp)) {
                        PrayerDayOverviewCard(crowdedFridayInTunis()) {}
                    }
                }
            }
        }
        assertTrue("some names are listed under the dial", compose.onAllNodes(hasTestTag("prayer_day_unplaced")).fetchSemanticsNodes().isNotEmpty())
        val read = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TraversalIndex))
            .fetchSemanticsNodes()
            .sortedBy { it.config[SemanticsProperties.TraversalIndex] }
            .map { node ->
                val description = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map(AnnotatedString::text)
                (description + text).joinToString(" ")
            }
        fun minutes(time: String) = time.substring(0, 2).toInt() * 60 + time.substring(3).toInt()
        val fromFajr = read.drop(1).mapNotNull { line -> Regex("\\d\\d:\\d\\d").find(line)?.value }
            .map { time -> minutesBetweenInDay(minutes("04:01"), minutes(time)) }
        assertEquals("read in the day's order: $read", fromFajr.sorted(), fromFajr)
        listOf("الفجر", "الشروق", "الجمعة", "العصر", "المغرب", "العشاء", "الثلث الثاني", "الثلث الأخير").forEach { name ->
            val word = Regex("(^|\\s)${Regex.escape(name)}(\\s|$)")
            assertEquals("$name is read once: $read", 1, read.count { line -> word.containsMatchIn(line) })
        }
    }

    /** Friday 23 April 2027 in Tunis: on a 360 dp phone at text size 2.0, Asr and Isha have no room on the dial. */
    private fun crowdedFridayInTunis(): NightTimesCardData {
        fun hm(text: String) = text.substring(0, 2).toInt() * 60 + text.substring(3).toInt()
        fun prayer(p: Prayer, title: String, time: String) = NightTimelinePrayer(p, title, time, hm(time))
        fun alarm(id: String, time: String, sub: Boolean) = NightTimelineAlarm(id, id, "منبه", time, hm(time), sub)
        return NightTimesCardData(
            sunriseTime = "05:35",
            sunriseMinuteOfDay = hm("05:35"),
            currentMinuteOfDay = 0,
            showCurrentTime = false,
            thirds = listOf(
                NightThirdSegment("19:03", "22:02", hm("19:03"), hm("22:02")),
                NightThirdSegment("22:02", "01:01", hm("22:02"), hm("01:01") + 1440),
                NightThirdSegment("01:01", "04:00", hm("01:01") + 1440, hm("04:00") + 1440),
            ),
            timelinePrayers = listOf(
                prayer(Prayer.FAJR, "الفجر", "04:01"),
                prayer(Prayer.JOMOAA, "الجمعة", "13:00"),
                prayer(Prayer.ASR, "العصر", "16:02"),
                prayer(Prayer.MAGHRIB, "المغرب", "19:03"),
                prayer(Prayer.ISHA, "العشاء", "20:35"),
            ),
            timelineAlarms = listOf(
                alarm("night", "01:11", false),
                alarm("w0", "02:16", true),
                alarm("w1", "02:21", false),
                alarm("w2", "02:26", true),
                alarm("w3", "02:33", true),
                alarm("dhuhr", "12:10", false),
            ),
            selectedDateLabel = "23 أبريل 2027",
            sunAltitudeDeg = null,
        )
    }

    /** 8 November 2026 near Paris at 16:16, with four wake alarms before Fajr, as on the owner's phone. */
    private fun dayWithWakeAlarms(): NightTimesCardData {
        fun hm(text: String) = text.substring(0, 2).toInt() * 60 + text.substring(3).toInt()
        fun prayer(p: Prayer, title: String, time: String) = NightTimelinePrayer(p, title, time, hm(time))
        fun alarm(id: String, time: String, sub: Boolean) = NightTimelineAlarm(id, id, "الفجر", time, hm(time) + 1440, sub)
        return NightTimesCardData(
            sunriseTime = "07:48",
            sunriseMinuteOfDay = hm("07:48"),
            currentMinuteOfDay = hm("16:16"),
            showCurrentTime = true,
            thirds = listOf(
                NightThirdSegment("17:19", "21:39", hm("17:19"), hm("21:39")),
                NightThirdSegment("21:39", "01:59", hm("21:39"), hm("01:59") + 1440),
                NightThirdSegment("01:59", "06:20", hm("01:59") + 1440, hm("06:20") + 1440),
            ),
            timelinePrayers = listOf(
                prayer(Prayer.FAJR, "الفجر", "06:18"),
                prayer(Prayer.DHUHR, "الظهر", "12:36"),
                prayer(Prayer.ASR, "العصر", "14:55"),
                prayer(Prayer.MAGHRIB, "المغرب", "17:19"),
                prayer(Prayer.ISHA, "العشاء", "18:49"),
            ),
            timelineAlarms = listOf(
                alarm("a", "04:33", true),
                alarm("b", "04:38", false),
                alarm("c", "04:43", true),
                alarm("d", "04:50", true),
            ),
            selectedDateLabel = "الأحد 8 نوفمبر",
            sunAltitudeDeg = 7.6,
        )
    }
}
