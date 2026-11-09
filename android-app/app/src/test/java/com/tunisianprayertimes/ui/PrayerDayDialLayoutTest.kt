package com.tunisianprayertimes.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.engine.PrayerDay
import com.tunisianprayertimes.engine.PrayerEngine
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.method.PrayerMethods
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.ZoneSource
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The day dial keeps its texts apart from each other, from the ring and its dots, from the horizon suns, from the
 * midday sun and the crescent, and inside its panel, on the days of a year at three phone widths and every Android
 * font size, with a day of alarms on it. Days come from the shared engine at places with short and very long nights.
 */
class PrayerDayDialLayoutTest {
    private data class Place(val name: String, val latitude: Double, val longitude: Double, val zone: String, val method: String)

    private val places = listOf(
        Place("Tunis", 36.8065, 10.1815, "Africa/Tunis", "tunisia_18_18"),
        Place("Paris mwl", 48.8566, 2.3522, "Europe/Paris", "mwl"),
        Place("Paris gmp", 48.8566, 2.3522, "Europe/Paris", "gmp"),
        Place("London mwl", 51.5074, -0.1278, "Europe/London", "mwl"),
    )

    /** The panel inside the card on 320, 360 and 411 dp wide phones. */
    private val widths = listOf(272f, 312f, 363f)

    private val days: List<LocalDate> = generateSequence(LocalDate.of(2027, 1, 1)) { it.plusDays(2) }
        .takeWhile { it.year == 2027 }
        .toList() + listOf(LocalDate.of(2027, 6, 21), LocalDate.of(2027, 12, 21), LocalDate.of(2027, 12, 17))

    /** Android's font sizes, from the default to the largest (Android 14 and later reach 2.0). */
    private val fontScales = listOf(1.0f, 1.15f, 1.3f, 1.5f, 1.8f, 2.0f)

    @Test
    fun textsAndDrawingsNeverCollide() {
        val problems = mutableListOf<String>()
        for (fontScale in fontScales) for (width in widths) for (place in places) for (date in days) {
            val data = cardData(place, date) ?: continue
            problems += collisions(layoutDayDial(data, width, sizes(fontScale)), "${place.name} $date ${width.roundToInt()}dp x$fontScale")
        }
        assertTrue(problems.take(30).joinToString("\n") + "\n(${problems.size} in all)\n" + summary(problems), problems.isEmpty())
    }

    /**
     * Up to font size 1.3 the dial is the one the owner approved: its usual panel, every name on it. Larger sizes
     * make the panel taller before any name leaves the dial for the list under it: none does up to 1.5 on 360 dp
     * and wider phones, nor up to 1.8 on 411 dp phones.
     */
    @Test
    fun everyNameStaysOnTheDialUntilTheLargestSizes() {
        val problems = mutableListOf<String>()
        for (fontScale in fontScales) for (width in widths) for (place in places) for (date in days) {
            val data = cardData(place, date) ?: continue
            val dial = layoutDayDial(data, width, sizes(fontScale))
            val where = "${place.name} $date ${width.roundToInt()}dp x$fontScale"
            if (fontScale <= 1.3f && dial.panelHeight != DAY_DIAL_HEIGHT) problems += "$where: panel ${dial.panelHeight} dp"
            val allOnDial = fontScale <= 1.3f || (fontScale <= 1.5f && width >= 312f) || (fontScale <= 1.8f && width >= 363f)
            if (allOnDial && dial.crowdedTexts.isNotEmpty()) problems += "$where: ${dial.crowdedTexts} under the dial"
        }
        assertTrue(problems.take(20).joinToString("\n") + "\n(${problems.size} in all)", problems.isEmpty())
    }

    /** How many of each kind of problem, by width and font size, with alarm times left out. */
    private fun summary(problems: List<String>): String = problems
        .map { problem ->
            val (where, what) = problem.split(": ", limit = 2)
            val setting = where.split(' ').takeLast(2).joinToString(" ")
            "$setting | ${what.replace(Regex("\\d\\d:\\d\\d"), "--")}"
        }
        .groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }
        .take(40)
        .joinToString("\n") { "${it.value}  ${it.key}" }

    /**
     * A time that finds no free spot is left out rather than drawn over something (its dot still opens it). In Tunisia
     * and France, on 360 dp and wider phones at the usual font sizes, more than one time is left out on at most one
     * day in a hundred, and the big words always stay. (London's short summer nights only test collisions.)
     */
    @Test
    fun onUsualPhonesAlmostEveryAlarmTimeIsShown() {
        val crowded = mutableListOf<String>()
        val wordless = mutableListOf<String>()
        var count = 0
        for (fontScale in listOf(1.0f, 1.15f)) for (width in listOf(312f, 363f)) for (place in places.filterNot { it.name.startsWith("London") }) for (date in days) {
            val data = cardData(place, date) ?: continue
            val dial = layoutDayDial(data, width, sizes(fontScale))
            val where = "${place.name} $date ${width.roundToInt()}dp x$fontScale"
            count++
            if (dial.dayWord == null || dial.nightWord == null) wordless += where
            val shown = dial.alarms.filter { it.showsLabel }.flatMap { it.labelAlarms }.map { it.id }.toSet()
            val missing = dial.alarms.flatMap { it.alarms }.filter { it.id !in shown }
            if (missing.size > 1) crowded += "$where: ${missing.size} times left out (${missing.joinToString { it.time }})"
        }
        assertTrue("big words left out:\n" + wordless.take(10).joinToString("\n"), wordless.isEmpty())
        assertTrue("${crowded.size} of $count days:\n" + crowded.take(10).joinToString("\n"), crowded.size * 100 <= count)
    }

    /**
     * The gold arc around "now" runs between the two events next to it, even when Isha comes after the second third
     * begins (Paris, 21 June, MWL: Isha 00:11, second third about 23:37): it never wraps round the ring.
     */
    @Test
    fun theArcAroundNowStaysBetweenItsNeighboursWhenIshaIsLate() {
        val data = requireNotNull(cardData(places[1], LocalDate.of(2027, 6, 21)))
        val isha = data.timelinePrayers.single { it.prayer == Prayer.ISHA }.minuteOfDay
        assertTrue("Isha after the second third begins", isha > data.thirds[1].startMinuteOfDay)
        val layout = layoutDayDial(data, 312f, sizes(1.15f))
        for (minute in (data.thirds[1].startMinuteOfDay..data.thirds[2].startMinuteOfDay + 30) step 5) {
            val (_, sweep) = layout.activeArc(minute)
            assertTrue("arc at minute $minute sweeps $sweep°", sweep < 60f)
        }
    }

    @Test
    fun theSunAndTheCrescentFindASpotOnAlmostEveryDay() {
        var days = 0
        var noSun = 0
        var noMoon = 0
        for (width in widths) for (place in places) for (date in this.days) {
            val data = cardData(place, date) ?: continue
            val layout = layoutDayDial(data, width, sizes(1.15f))
            days++
            if (layout.noonSun == null) noSun++
            if (layout.moon == null) noMoon++
        }
        assertTrue("no sun on $noSun of $days days", noSun * 20 <= days)
        assertTrue("no crescent on $noMoon of $days days", noMoon * 20 <= days)
    }

    // ---- What the card would show on [date] at [place], as SunriseAndNightTimesCard builds it ----

    private fun cardData(place: Place, date: LocalDate): NightTimesCardData? {
        val coordinates = PrayerPlace.Coordinates(
            latE5 = (place.latitude * 1e5).roundToInt(),
            lngE5 = (place.longitude * 1e5).roundToInt(),
            zoneId = place.zone,
            zoneSource = ZoneSource.USER,
        )
        val method = requireNotNull(PrayerMethods.byId(place.method)) { place.method }
        val zone = ZoneId.of(place.zone)
        val today = PrayerEngine.day(coordinates, date, method)
        val tomorrow = PrayerEngine.day(coordinates, date.plusDays(1), method)
        fun PrayerDay.at(event: TimeEvent): Instant? = this[event].at
        val fajr = today.at(TimeEvent.FAJR) ?: return null
        val sunrise = today.at(TimeEvent.SUNRISE) ?: return null
        val dhuhr = today.at(TimeEvent.DHUHR) ?: return null
        val asr = today.at(TimeEvent.ASR) ?: return null
        val maghrib = today.at(TimeEvent.MAGHRIB) ?: return null
        val isha = today.at(TimeEvent.ISHA) ?: return null
        val nextFajr = tomorrow.at(TimeEvent.FAJR) ?: return null
        val midnight = date.atStartOfDay(zone).toInstant()
        fun minute(instant: Instant): Int = ChronoUnit.MINUTES.between(midnight, instant).toInt()
        val clock = DateTimeFormatter.ofPattern("HH:mm")
        fun time(instant: Instant): String = clock.format(instant.atZone(zone))
        val third = (nextFajr.toEpochMilli() - maghrib.toEpochMilli()) / 3
        val secondThird = Instant.ofEpochMilli(maghrib.toEpochMilli() + third)
        val finalThird = Instant.ofEpochMilli(maghrib.toEpochMilli() + 2 * third)
        fun segment(from: Instant, to: Instant) = NightThirdSegment(time(from), time(to), minute(from), minute(to))
        val friday = date.dayOfWeek == DayOfWeek.FRIDAY
        val noon = if (friday) dhuhr.plusSeconds(25 * 60L) else dhuhr
        val prayers = listOf(
            NightTimelinePrayer(Prayer.FAJR, "الفجر", time(fajr), minute(fajr)),
            NightTimelinePrayer(if (friday) Prayer.JOMOAA else Prayer.DHUHR, if (friday) "الجمعة" else "الظهر", time(noon), minute(noon)),
            NightTimelinePrayer(Prayer.ASR, "العصر", time(asr), minute(asr)),
            NightTimelinePrayer(Prayer.MAGHRIB, "المغرب", time(maghrib), minute(maghrib)),
            NightTimelinePrayer(Prayer.ISHA, "العشاء", time(isha), minute(isha)),
        )
        // A wake alarm before Fajr with three extras, a night-prayer alarm in the last third, a reminder before
        // Dhuhr and one late in the evening: the calendar day's rings, as the card takes them.
        fun alarm(id: String, at: Instant, sub: Boolean): NightTimelineAlarm {
            val clockMinute = Math.floorMod(minute(at), 1440)
            return NightTimelineAlarm(id, id, "منبه", "%02d:%02d".format(clockMinute / 60, clockMinute % 60), clockMinute, sub)
        }
        val alarms = listOf(
            alarm("wake", fajr.minusSeconds(100 * 60L), sub = false),
            alarm("wake-1", fajr.minusSeconds(105 * 60L), sub = true),
            alarm("wake-2", fajr.minusSeconds(95 * 60L), sub = true),
            alarm("wake-3", fajr.minusSeconds(88 * 60L), sub = true),
            alarm("night", finalThird.plusSeconds(10 * 60L), sub = false),
            alarm("dhuhr", dhuhr.minusSeconds(15 * 60L), sub = false),
            alarm("late", midnight.plusSeconds(23 * 3600L), sub = false),
        ).sortedBy { it.minuteOfDay }
        return NightTimesCardData(
            sunriseTime = time(sunrise),
            sunriseMinuteOfDay = minute(sunrise),
            currentMinuteOfDay = 0,
            showCurrentTime = true,
            thirds = listOf(segment(maghrib, secondThird), segment(secondThird, finalThird), segment(finalThird, nextFajr)),
            timelinePrayers = prayers,
            timelineAlarms = alarms,
            selectedDateLabel = "الأحد 8 نوفمبر",
            sunAltitudeDeg = 0.0,
        )
    }

    /** Text sizes the card measured with the app's font at font size 1.15 (dp), scaled to [fontScale]. */
    private fun sizes(fontScale: Float): DialTextSizes {
        val k = fontScale / 1.15f
        fun nameWidth(name: String, em: Float) = name.length * em * 0.40f * k
        fun timeWidth(time: String, em: Float, perEm: Float) = time.length * em * perEm * k
        return DialTextSizes(
            horizon = { name, time -> DialSize(maxOf(nameWidth(name, 11.5f), timeWidth(time, 13.8f, 0.59f)), 39.4f * k) },
            prayer = { name, time -> DialSize(maxOf(nameWidth(name, 11.5f), timeWidth(time, 12.65f, 0.574f)), 36.7f * k) },
            alarmTime = { time -> DialSize(timeWidth(time, 11.5f, 0.527f), 17f * k) },
            twilight = DialSize(53.4f * k, 19.4f * k),
            dayWord = DialSize(49.4f * k, 38f * k),
            nightWord = DialSize(48.7f * k, 38f * k),
            sunAngle = DialSize(135f * k, 19f * k),
            dateLine = { date -> DialSize(date.length * 6.5f * k, 19f * k) },
        )
    }

    // ---- The checks ----

    private fun collisions(layout: DayDialLayout, where: String): List<String> {
        val problems = mutableListOf<String>()
        val width = layout.panelWidth
        val height = layout.panelHeight
        val r = layout.radius
        val center = Offset(layout.centerX, layout.centerY)
        // Labels in the list under the dial are not on it.
        val outsideTexts = listOf(
            "الشروق" to layout.sunrise,
            "المغرب" to layout.maghrib,
            "الفجر" to layout.fajr,
            "الثلث الثاني" to layout.secondThird,
            "الثلث الأخير" to layout.finalThird,
        ).filterNot { it.second.inList }.map { (name, label) -> name to label.rect }
        val insideTexts = listOfNotNull(layout.twilight?.let { "وقت الفجر" to it }) +
            layout.prayerLabels.filterNot { it.second.inList }.map { (prayer, label) -> prayer.title to label.rect }
        val alarmTexts = layout.alarms.filter { it.showsLabel }
            .map { "منبه ${it.time}" to Rect(it.labelX, it.labelY, it.labelX + it.labelWidth, it.labelY + it.labelHeight) }
        val words = listOfNotNull(
            layout.dayWord?.let { "النهار" to it },
            layout.nightWord?.let { "الليل" to it },
            layout.sunAngleLine?.let { "ارتفاع الشمس" to it },
        )
        val texts = outsideTexts + insideTexts + alarmTexts + words

        fun nearest(box: Rect) = hypot(center.x.coerceIn(box.left, box.right) - center.x, center.y.coerceIn(box.top, box.bottom) - center.y)
        fun farthest(box: Rect) = listOf(box.topLeft, box.topRight, box.bottomLeft, box.bottomRight).maxOf { hypot(it.x - center.x, it.y - center.y) }
        fun onRing(box: Rect) = nearest(box) < r + 2.7f && farthest(box) > r - 1.5f
        fun circleOn(c: Offset, radius: Float, box: Rect) =
            hypot(c.x.coerceIn(box.left, box.right) - c.x, c.y.coerceIn(box.top, box.bottom) - c.y) < radius

        texts.forEach { (name, box) ->
            if (box.left < 0f || box.top < 0f || box.right > width || box.bottom > height) problems += "$where: $name leaves the panel"
        }
        for (i in texts.indices) for (j in i + 1 until texts.size) {
            if (texts[i].second.overlaps(texts[j].second)) problems += "$where: ${texts[i].first} on ${texts[j].first}"
        }
        (outsideTexts + insideTexts + alarmTexts).forEach { (name, box) -> if (onRing(box)) problems += "$where: $name on the ring" }

        // No text sits across a line where the sky changes colour: half of it would be on day, half on night.
        val sunrisePoint = layout.point(layout.sunriseAngle)
        val sunsetPoint = layout.point(layout.sunsetAngle)
        val skyEdges = listOf(
            Offset(0f, center.y) to sunrisePoint,
            center to sunrisePoint,
            center to sunsetPoint,
            sunsetPoint to layout.sunsetBorderEnd,
        )
        texts.forEach { (name, box) ->
            if (skyEdges.any { (from, to) -> segmentHitsRect(from, to, box) }) problems += "$where: $name across the sky's edge"
        }
        // Texts with a fixed colour sit on the sky that colour reads on.
        layout.twilight?.let { if (layout.onDaySky(it.center)) problems += "$where: وقت الفجر (white) on the day sky" }
        layout.dayWord?.let { if (!layout.onDaySky(it.center)) problems += "$where: النهار (navy) on the night sky" }
        layout.nightWord?.let { if (layout.onDaySky(it.center)) problems += "$where: الليل (white) on the day sky" }
        // Each name sits on its own sky: Dhuhr and Asr on the day, Isha, Fajr and the thirds on the night.
        layout.prayerLabels.filterNot { it.second.inList }.forEach { (prayer, label) ->
            val day = layout.angleOf(prayer.minuteOfDay) <= layout.sunsetAngle
            if (layout.onDaySky(label.rect.center) != day) problems += "$where: ${prayer.title} on the ${if (day) "night" else "day"} sky"
        }
        listOf("الفجر" to layout.fajr, "الثلث الثاني" to layout.secondThird, "الثلث الأخير" to layout.finalThird)
            .filterNot { it.second.inList }
            .forEach { (name, label) -> if (layout.onDaySky(label.rect.center)) problems += "$where: $name on the day sky" }

        val dots = (listOf(layout.sunriseAngle, layout.sunsetAngle, layout.secondThirdAngle, layout.finalThirdAngle, layout.fajrAngle) +
            layout.prayerAngles).map { layout.point(it) } + layout.alarms.map { Offset(it.iconCenterX, it.iconCenterY) }
        texts.forEach { (name, box) -> if (dots.any { circleOn(it, 5.2f, box) }) problems += "$where: $name on a dot" }

        val sunriseSun = Rect(layout.sunriseSun.x - DIAL_SUN_REACH, layout.sunriseSun.y - DIAL_SUN_REACH, layout.sunriseSun.x + DIAL_SUN_REACH, layout.sunriseSun.y)
        texts.forEach { (name, box) ->
            if (box.overlaps(sunriseSun)) problems += "$where: $name on the sunrise sun"
            if (circleOn(layout.sunsetSun, DIAL_SUN_REACH, box)) problems += "$where: $name on the sunset sun"
            layout.noonSun?.let { if (circleOn(it, DIAL_SUN_REACH, box)) problems += "$where: $name on the midday sun" }
            layout.moon?.let { if (circleOn(it, DIAL_MOON_RADIUS, box)) problems += "$where: $name on the crescent" }
        }
        layout.noonSun?.let { if (hypot(it.x - center.x, it.y - center.y) < r + 2.7f + DIAL_SUN_REACH) problems += "$where: midday sun on the ring" }
        layout.moon?.let { if (hypot(it.x - center.x, it.y - center.y) < r + 2.7f + DIAL_MOON_RADIUS) problems += "$where: crescent on the ring" }

        // Leader lines cross no other text and no other line.
        val leaders = layout.alarms.filter { it.showsLabel }.mapNotNull { alarm ->
            val box = Rect(alarm.labelX, alarm.labelY, alarm.labelX + alarm.labelWidth, alarm.labelY + alarm.labelHeight)
            alarm.leader?.let { Triple("منبه ${alarm.time}", it, box) }
        } + (listOf("الفجر" to layout.fajr, "الثلث الثاني" to layout.secondThird, "الثلث الأخير" to layout.finalThird) +
            layout.prayerLabels.map { (prayer, label) -> prayer.title to label })
            .filterNot { (_, label) -> label.inList }
            .mapNotNull { (name, label) -> label.leaderFrom?.let { from -> leaderEnds(from, label.rect)?.let { Triple(name, it, label.rect) } } }
        leaders.forEach { (name, line, own) ->
            // A line ends at its own text's edge: drawn into the text, it would cross out its letters or digits.
            if (segmentHitsRect(line.first, line.second, own.deflate(1f))) problems += "$where: line of $name runs into its text"
            texts.filter { it.second != own }.forEach { (other, box) ->
                if (segmentHitsRect(line.first, line.second, box)) problems += "$where: line of $name crosses $other"
            }
        }
        for (i in leaders.indices) for (j in i + 1 until leaders.size) {
            val (a, b) = leaders[i].second
            val (c, d) = leaders[j].second
            if (segmentsCross(a, b, c, d)) problems += "$where: lines of ${leaders[i].first} and ${leaders[j].first} cross"
        }
        return problems
    }

    private fun segmentHitsRect(from: Offset, to: Offset, rect: Rect): Boolean {
        if (rect.contains(from) || rect.contains(to)) return true
        val corners = listOf(rect.topLeft, rect.topRight, rect.bottomRight, rect.bottomLeft)
        return (0 until 4).any { segmentsCross(from, to, corners[it], corners[(it + 1) % 4]) }
    }

    private fun segmentsCross(a: Offset, b: Offset, c: Offset, d: Offset): Boolean {
        fun cross(o: Offset, p: Offset, q: Offset) = (p.x - o.x) * (q.y - o.y) - (p.y - o.y) * (q.x - o.x)
        val d1 = cross(c, d, a)
        val d2 = cross(c, d, b)
        val d3 = cross(a, b, c)
        val d4 = cross(a, b, d)
        return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) && ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
    }
}
