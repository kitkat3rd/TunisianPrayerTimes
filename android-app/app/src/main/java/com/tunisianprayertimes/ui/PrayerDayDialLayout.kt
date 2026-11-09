package com.tunisianprayertimes.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.tunisianprayertimes.Prayer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** The day dial's panel height at the usual text sizes (dp). */
internal const val DAY_DIAL_HEIGHT = 442f

/** A larger text size makes the panel taller in these steps (dp), up to [DAY_DIAL_MAX_HEIGHT]. */
private const val DAY_DIAL_HEIGHT_STEP = 40f
private const val DAY_DIAL_MAX_HEIGHT = DAY_DIAL_HEIGHT + 8 * DAY_DIAL_HEIGHT_STEP

/** The tips of a drawn sun's rays, from its centre (dp). */
internal const val DIAL_SUN_REACH = 23f

/** The crescent's radius (dp). */
internal const val DIAL_MOON_RADIUS = 8.5f

private const val MARKER_RADIUS = 5.2f

/** Air kept between a text and the ring: the grey outer ring ends at R + 2.7, the white ring starts at R - 1.5. */
private const val RING_CLEAR_OUTSIDE = 5f
private const val RING_CLEAR_INSIDE = 4f

/** A text block's size on the dial (dp), measured at the phone's font and font size. */
internal data class DialSize(val width: Float, val height: Float)

/**
 * What the dial's texts measure (dp). The card measures them with the phone's font size, so the layout keeps them
 * apart at any text size rather than trusting fixed boxes.
 */
internal class DialTextSizes(
    /** A horizon or night-third label: its name over its time. */
    val horizon: (label: String, time: String) -> DialSize,
    /** A prayer's small label inside the ring: its name over its time. */
    val prayer: (label: String, time: String) -> DialSize,
    /** An alarm's time. */
    val alarmTime: (time: String) -> DialSize,
    val twilight: DialSize,
    val dayWord: DialSize,
    val nightWord: DialSize,
    /** The sun's altitude line at the top, with its widest value. */
    val sunAngle: DialSize,
    /** The date line at the top, shown instead of the altitude on another day than today. */
    val dateLine: (date: String) -> DialSize,
)

/**
 * A placed text block (dp); [leaderFrom] is its marker when the block had to move away from it. [inList]: the dial
 * had no free spot for it even at its tallest (the largest text sizes), so it is written in the list under the dial
 * instead, never over anything.
 */
internal data class DialLabel(val rect: Rect, val leaderFrom: Offset? = null, val inList: Boolean = false)

internal data class NightTimelineAlarmPlacement(
    val id: String,
    val alarms: List<NightTimelineAlarm>,
    val iconCenterX: Float,
    val iconCenterY: Float,
    val nearNonAlarmMarker: Boolean,
    val labelCenterX: Float,
    val labelCenterY: Float,
    val labelX: Float,
    val labelY: Float,
    val labelWidth: Float,
    val labelHeight: Float,
    /** The time shown: this dot's, or its run's range. */
    val time: String,
    /** The drawn line from the dot to the time, when the time sits away from its dot. */
    val leader: Pair<Offset, Offset>? = null,
    /** False for a dot whose time is shown by its run's range label. */
    val showsLabel: Boolean = true,
    /** What tapping the time opens: this dot's alarms, or every alarm of the run its range covers. */
    val labelAlarms: List<NightTimelineAlarm> = alarms,
) {
    val isCluster: Boolean = alarms.size > 1
    val hasSubAlarm: Boolean = alarms.any { alarm -> alarm.isSubAlarm }
}

/**
 * Where everything sits on the day dial (dp, panel coordinates). The dial is a 24-hour clock with sunrise at 9
 * o'clock, turning clockwise. Every text is placed against one set of obstacles (the ring, its dots, the horizon
 * suns, the lines where the sky changes colour, the texts placed before it and their leader lines), and the midday
 * sun and the midnight crescent go last, into a spot no text uses, or are left out.
 */
internal class DayDialLayout(
    val panelWidth: Float,
    val panelHeight: Float,
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val sunriseAngle: Float,
    val daylightSweep: Float,
    val sunsetAngle: Float,
    val twilightSweep: Float,
    val fajrAngle: Float,
    val secondThirdAngle: Float,
    val finalThirdAngle: Float,
    val sunriseSun: Offset,
    val sunsetSun: Offset,
    val sunsetBorderEnd: Offset,
    val prayerAngles: List<Float>,
    val sunrise: DialLabel,
    val maghrib: DialLabel,
    val fajr: DialLabel,
    val secondThird: DialLabel,
    val finalThird: DialLabel,
    /** «وقت الفجر» in the wedge from Fajr to sunrise; null where it has no room. */
    val twilight: Rect?,
    /** The big words, each null when a small dial has no room left for it. */
    val dayWord: Rect?,
    val nightWord: Rect?,
    /** The line at the top: the sun's altitude today, the date on another day. */
    val sunAngleLine: Rect,
    val prayerLabels: List<Pair<NightTimelinePrayer, DialLabel>>,
    val alarms: List<NightTimelineAlarmPlacement>,
    /** The sun at midday, centre of its rays; null when no free spot was left for it. */
    val noonSun: Offset?,
    /** The crescent at the middle of the night; null when no free spot was left for it. */
    val moon: Offset?,
    private val angleOfMinute: (Int) -> Float,
    private val segments: List<Pair<Int, Int>>,
    private val fallbackSegment: Pair<Int, Int>,
    /** Names that found no free spot in this panel: they are written in the list under the dial. */
    val crowdedTexts: List<String> = emptyList(),
) {
    fun point(angleDegrees: Float, distance: Float = radius): Offset {
        val radians = angleDegrees * PI / 180.0
        return Offset(centerX + distance * cos(radians).toFloat(), centerY + distance * sin(radians).toFloat())
    }

    fun angleOf(minuteOfDay: Int): Float = angleOfMinute(minuteOfDay)

    /** Whether [spot] lies on the day sky: the ring's day half inside it, above the horizon and sunset ray outside. */
    fun onDaySky(spot: Offset): Boolean =
        onDaySkyAt(spot, Offset(centerX, centerY), radius, sunriseAngle, sunsetAngle, daySkyOutline)

    /** The day sky outside the ring: above the horizon and the sunset ray, round the panel's corners. */
    val daySkyOutline: List<Offset> by lazy {
        daySkyPolygon(panelWidth, panelHeight, centerY, point(sunriseAngle), point(sunsetAngle), sunsetBorderEnd)
    }

    /** The night sky outside the ring: below the horizon and the sunset ray, round the panel's corners. */
    val nightSkyOutline: List<Offset> by lazy {
        buildList {
            add(Offset(0f, centerY))
            add(point(sunriseAngle))
            add(point(sunsetAngle))
            add(sunsetBorderEnd)
            if (sunsetBorderEnd.y <= 0.5f) add(Offset(panelWidth, 0f))
            add(Offset(panelWidth, panelHeight))
            add(Offset(0f, panelHeight))
        }
    }

    /** The arc between the two events around [minuteOfDay]: its start angle and its clockwise sweep. */
    fun activeArc(minuteOfDay: Int): Pair<Float, Float> {
        val (start, end) = segments.firstOrNull { (from, to) ->
            val length = minutesBetweenInDay(from, to)
            length > 0 && minutesBetweenInDay(from, minuteOfDay) < length
        } ?: fallbackSegment
        val startAngle = angleOf(start)
        val sweep = (angleOf(end) - startAngle).let { if (it >= 0f) it else it + 360f }
        return startAngle to sweep
    }
}

/**
 * The dial in a panel [panelWidth] dp wide, as tall as its texts need: the usual [DAY_DIAL_HEIGHT], or taller when
 * a large text size leaves a prayer, horizon or night-third label no free spot. The ring keeps its size, so its
 * dots stay as far apart, and the extra room goes above and below it. Only those labels decide the height (not the
 * alarm times or the pictures), so it changes little from one day to the next. A label with no free spot even then
 * goes in the list under the dial ([DialLabel.inList]); the panel takes the height that leaves the fewest there.
 */
internal fun layoutDayDial(
    data: NightTimesCardData,
    panelWidth: Float,
    sizes: DialTextSizes,
): DayDialLayout {
    // Each height is tried with the labels alone (they come first, so they land where the whole layout puts them),
    // in the usual order: Fajr and the thirds of the night, then the other prayers. When that leaves a label out
    // at every height, the prayers go first: they matter more than the thirds, which then take what room is left.
    var bestHeight = DAY_DIAL_HEIGHT
    var bestPrayersFirst = false
    var fewestCrowded = Int.MAX_VALUE
    search@ for (prayersFirst in listOf(false, true)) {
        var height = DAY_DIAL_HEIGHT
        while (height <= DAY_DIAL_MAX_HEIGHT) {
            val crowded = crowdedWeight(layoutDayDialIn(data, panelWidth, sizes, height, labelsOnly = true, prayersFirst).crowdedTexts)
            if (crowded < fewestCrowded) {
                fewestCrowded = crowded
                bestHeight = height
                bestPrayersFirst = prayersFirst
            }
            if (crowded == 0) break@search
            height += DAY_DIAL_HEIGHT_STEP
        }
    }
    return layoutDayDialIn(data, panelWidth, sizes, bestHeight, labelsOnly = false, bestPrayersFirst)
}

/**
 * How much a layout leaves off the dial: a prayer in the list under it weighs more than a third of the night, and
 * the sunset ray cutting the line at the top (drawn over it, not moved to the list) weighs most.
 */
private fun crowdedWeight(names: List<String>): Int = names.sumOf { name ->
    when (name) {
        DIAL_SECOND_THIRD, DIAL_FINAL_THIRD -> 1
        SUN_LINE_CUT -> 100
        else -> 3
    }
}

private const val SUN_LINE_CUT = "sunAngleLine"

/**
 * The dial in a panel of [panelHeight]; [labelsOnly] stops after the event and prayer labels. [prayersFirst] places
 * the prayers' labels (Fajr's among them) before the night's thirds.
 */
private fun layoutDayDialIn(
    data: NightTimesCardData,
    panelWidth: Float,
    sizes: DialTextSizes,
    panelHeight: Float,
    labelsOnly: Boolean,
    prayersFirst: Boolean,
): DayDialLayout {
    val fajrMinute = data.timelinePrayers.firstOrNull { it.prayer == Prayer.FAJR }?.minuteOfDay ?: data.thirds[2].endMinuteOfDay
    val maghribMinute = data.timelinePrayers.firstOrNull { it.prayer == Prayer.MAGHRIB }?.minuteOfDay ?: data.thirds[0].startMinuteOfDay
    val daylightMinutes = minutesBetweenInDay(data.sunriseMinuteOfDay, maghribMinute)
    val daylightSweep = daylightMinutes / (24f * 60f) * 360f
    val sunriseAngle = 180f
    val sunsetAngle = sunriseAngle + daylightSweep
    val radius = if (panelWidth - 86f > 262f) 131f else (panelWidth - 86f) / 2f
    val cx = panelWidth / 2f
    val cy = panelHeight / 2f
    fun point(angleDegrees: Float, distance: Float = radius): Offset {
        val radians = angleDegrees * PI / 180.0
        return Offset(cx + distance * cos(radians).toFloat(), cy + distance * sin(radians).toFloat())
    }

    val sunrisePoint = point(sunriseAngle)
    val sunsetPoint = point(sunsetAngle)
    val sunriseSun = Offset(sunrisePoint.x - 26f, cy)
    val rayX = (sunsetPoint.x - cx) / radius
    val rayY = (sunsetPoint.y - cy) / radius
    val toBorder = run {
        val horizontal = when {
            rayX > 0f -> (panelWidth - sunsetPoint.x) / rayX
            rayX < 0f -> -sunsetPoint.x / rayX
            else -> Float.POSITIVE_INFINITY
        }
        val vertical = when {
            rayY > 0f -> (panelHeight - sunsetPoint.y) / rayY
            rayY < 0f -> -sunsetPoint.y / rayY
            else -> Float.POSITIVE_INFINITY
        }
        minOf(horizontal, vertical).coerceAtLeast(0f)
    }
    // The line at the top, centred: the sun's altitude today, the date on another day. Only its text blocks the
    // space around it.
    val showsSunAngle = data.showCurrentTime && data.sunAltitudeDeg != null
    val sunAngleLine = run {
        val size = if (showsSunAngle) sizes.sunAngle else sizes.dateLine(data.selectedDateLabel)
        val half = (size.width / 2f).coerceAtMost(panelWidth / 2f - 12f)
        Rect(cx - half, 14f, cx + half, 14f + size.height)
    }
    // The setting sun sits halfway along its ray to the panel's edge, or as near halfway as keeps it clear of the
    // sun's altitude line (on short days the ray climbs to the top of the panel).
    val sunsetSun = run {
        val halfway = toBorder / 2f
        val closest = RING_CLEAR_OUTSIDE + DIAL_SUN_REACH
        (0..60).asSequence()
            .flatMap { step -> sequenceOf(halfway - step * 2f, halfway + step * 2f) }
            .filter { along -> along >= closest }
            .map { along -> Offset(sunsetPoint.x + rayX * along, sunsetPoint.y + rayY * along) }
            .firstOrNull { sun -> !circleHitsRect(sun, DIAL_SUN_REACH + 2f, sunAngleLine) }
            ?: Offset(sunsetPoint.x + rayX * halfway, sunsetPoint.y + rayY * halfway)
    }
    val sunsetBorderEnd = Offset(sunsetPoint.x + rayX * toBorder, sunsetPoint.y + rayY * toBorder)
    val daySkyOutline = daySkyPolygon(panelWidth, panelHeight, cy, sunrisePoint, sunsetPoint, sunsetBorderEnd)
    fun onDaySky(spot: Offset): Boolean = onDaySkyAt(spot, Offset(cx, cy), radius, sunriseAngle, sunsetAngle, daySkyOutline)

    val twilightMinutes = minutesBetweenInDay(fajrMinute, data.sunriseMinuteOfDay)
    val twilightSweep = twilightMinutes / (24f * 60f) * 360f
    val nightSweep = (360f - daylightSweep - twilightSweep).coerceAtLeast(0f)
    val nightMinutes = minutesBetweenInDay(maghribMinute, fajrMinute).coerceAtLeast(1)
    fun nightAngle(minuteOfDay: Int): Float =
        sunsetAngle + nightSweep * (minutesBetweenInDay(maghribMinute, minuteOfDay).toFloat() / nightMinutes.toFloat())
    val secondThirdAngle = nightAngle(data.thirds[1].startMinuteOfDay)
    val finalThirdAngle = nightAngle(data.thirds[2].startMinuteOfDay)
    val fajrAngle = nightAngle(fajrMinute)
    fun angleOf(minuteOfDay: Int): Float {
        val fromSunrise = minutesBetweenInDay(data.sunriseMinuteOfDay, minuteOfDay)
        val fromMaghrib = minutesBetweenInDay(maghribMinute, minuteOfDay)
        return when {
            fromSunrise <= daylightMinutes ->
                sunriseAngle + daylightSweep * (fromSunrise.toFloat() / daylightMinutes.coerceAtLeast(1).toFloat())
            fromMaghrib <= nightMinutes -> nightAngle(minuteOfDay)
            else -> fajrAngle + twilightSweep *
                (minutesBetweenInDay(fajrMinute, minuteOfDay).toFloat() / twilightMinutes.coerceAtLeast(1).toFloat())
        }
    }

    val middayMinute = data.timelinePrayers.firstOrNull { it.prayer == Prayer.DHUHR || it.prayer == Prayer.JOMOAA }?.minuteOfDay
        ?: data.sunriseMinuteOfDay
    val asrMinute = data.timelinePrayers.firstOrNull { it.prayer == Prayer.ASR }?.minuteOfDay ?: middayMinute
    val ishaMinute = data.timelinePrayers.firstOrNull { it.prayer == Prayer.ISHA }?.minuteOfDay ?: maghribMinute
    // The arc around "now" runs between the two events next to it on the dial, in the dial's own order: an Isha after
    // the second third begins (Paris in June) would otherwise make it wrap round the whole ring.
    val boundaries = listOf(
        fajrMinute, data.sunriseMinuteOfDay, middayMinute, asrMinute, maghribMinute, ishaMinute,
        data.thirds[1].startMinuteOfDay, data.thirds[2].startMinuteOfDay,
    )
        .distinctBy { normalizedMinuteOfDay(it) }
        .sortedBy { minutesBetweenInDay(data.sunriseMinuteOfDay, it) }
    val segments = boundaries.indices.map { index -> boundaries[index] to boundaries[(index + 1) % boundaries.size] }

    val space = DialSpace(panelWidth, panelHeight, cx, cy, radius)
    val prayerAngles = data.timelinePrayers.map { prayer -> angleOf(prayer.minuteOfDay) }
    val eventAngles = listOf(sunriseAngle, sunsetAngle, secondThirdAngle, finalThirdAngle, fajrAngle)
    val nonAlarmMarkerCenters = (eventAngles + prayerAngles).map { angle -> point(angle) }
    nonAlarmMarkerCenters.forEach { center -> space.addCircle(center, MARKER_RADIUS + 1.5f) }
    // The horizon suns: only the sunrise sun's upper half shows above the horizon line.
    space.addHard(Rect(sunriseSun.x - DIAL_SUN_REACH, sunriseSun.y - DIAL_SUN_REACH, sunriseSun.x + DIAL_SUN_REACH, sunriseSun.y))
    space.addHard(Rect(sunsetSun.x - DIAL_SUN_REACH, sunsetSun.y - DIAL_SUN_REACH, sunsetSun.x + DIAL_SUN_REACH, sunsetSun.y + DIAL_SUN_REACH))
    space.addHard(sunAngleLine)
    // Where the sky changes colour (the horizon, the sunset ray and the two lines from the centre), a text across the
    // line would be half on day and half on night: no text sits across them.
    space.addSkyEdge(Offset(0f, cy), sunrisePoint)
    space.addSkyEdge(Offset(cx, cy), sunrisePoint)
    space.addSkyEdge(Offset(cx, cy), sunsetPoint)
    space.addSkyEdge(sunsetPoint, sunsetBorderEnd)

    fun clampedCenter(center: Offset, size: DialSize): Offset = Offset(
        center.x.coerceIn(4f + size.width / 2f, (panelWidth - 4f - size.width / 2f).coerceAtLeast(4f + size.width / 2f)),
        center.y.coerceIn(4f + size.height / 2f, (panelHeight - 4f - size.height / 2f).coerceAtLeast(4f + size.height / 2f)),
    )
    fun boxAt(center: Offset, size: DialSize) =
        Rect(center.x - size.width / 2f, center.y - size.height / 2f, center.x + size.width / 2f, center.y + size.height / 2f)
    fun radialExtent(angleDegrees: Float, size: DialSize): Float {
        val radians = angleDegrees * PI / 180.0
        return abs(cos(radians).toFloat()) * size.width / 2f + abs(sin(radians).toFloat()) * size.height / 2f
    }

    val crowdedTexts = mutableListOf<String>()
    // On short days the sunset ray climbs to the top of the panel. At a large text size the line at the top is wide
    // enough for the ray to cut it; in a taller panel the ray reaches the side first.
    if (segmentHitsRect(sunsetPoint, sunsetBorderEnd, sunAngleLine)) crowdedTexts += SUN_LINE_CUT

    /** A text that moved more than this (dp) from its spot beside its dot is joined to the dot by a line. */
    fun leaderFor(box: Rect, preferred: Offset, marker: Offset?): Offset? =
        marker?.takeIf { hypot(box.center.x - preferred.x, box.center.y - preferred.y) > 12f }

    fun keepText(box: Rect, leaderFrom: Offset?): DialLabel {
        space.addHard(box)
        if (leaderFrom != null) {
            leaderEnds(leaderFrom, box)?.let { (from, to) -> space.addSegment(from, to) }
        }
        return DialLabel(box, leaderFrom)
    }

    /**
     * A text at [preferred] or the nearest free spot on [side], with its line when it moved away; null if none. With
     * [daySky], it stays on that sky: a day prayer's name on the night half would read as a night event.
     */
    fun tryText(
        preferred: Offset,
        size: DialSize,
        side: RingSide,
        directionDegrees: Float,
        marker: Offset?,
        daySky: Boolean? = null,
    ): DialLabel? {
        val box = space.place(clampedCenter(preferred, size), size, side, directionDegrees, maxShift = 150f) { candidate ->
            if (daySky != null && onDaySky(candidate.center) != daySky) return@place false
            // A text that leaves its dot is joined to it by a line, and that line must cross nothing either.
            val from = leaderFor(candidate, preferred, marker) ?: return@place true
            val line = leaderEnds(from, candidate) ?: return@place true
            space.segmentClear(line.first, line.second, ignoring = candidate)
        } ?: return null
        return keepText(box, leaderFor(box, preferred, marker))
    }

    /** No free spot in this panel: the panel is tried taller, and at the tallest the text goes in the list. */
    fun crowdedText(preferred: Offset, size: DialSize, name: String): DialLabel {
        crowdedTexts += name
        return DialLabel(boxAt(clampedCenter(preferred, size), size), inList = true)
    }

    fun horizonText(label: String, time: String, sun: Offset): DialLabel {
        val size = sizes.horizon(label, time)
        val preferred = Offset(sun.x, sun.y - DIAL_SUN_REACH - 3f - size.height / 2f)
        return tryText(preferred, size, RingSide.Outside, -90f, marker = null) ?: crowdedText(preferred, size, label)
    }

    /**
     * Fajr and the night's thirds: outside the ring by their dot, else inside it by their dot (a large font), on the
     * night sky either way.
     */
    fun outsideText(label: String, time: String, angleDegrees: Float): DialLabel {
        val size = sizes.horizon(label, time)
        val marker = point(angleDegrees)
        val outside = point(angleDegrees, radius + 7f + radialExtent(angleDegrees, size))
        val inside = point(angleDegrees, (radius - 12f - radialExtent(angleDegrees, size)).coerceAtLeast(20f))
        return tryText(outside, size, RingSide.Outside, angleDegrees, marker, daySky = false)
            ?: tryText(inside, size, RingSide.Inside, angleDegrees + 180f, marker, daySky = false)
            ?: crowdedText(outside, size, label)
    }

    val alarmDots = fitAlarmDots(
        data = data,
        space = space,
        radius = radius,
        sizes = sizes,
        angleOf = ::angleOf,
        point = ::point,
        barrierAngles = (listOf(sunriseAngle, sunriseAngle + 360f) + eventAngles.drop(1) + prayerAngles).distinct().sorted(),
        nonAlarmMarkerCenters = nonAlarmMarkerCenters,
    )

    val maghribTime = data.timelinePrayers.firstOrNull { it.prayer == Prayer.MAGHRIB }?.time ?: data.thirds[0].startTime
    val fajrTime = data.timelinePrayers.firstOrNull { it.prayer == Prayer.FAJR }?.time ?: data.thirds[2].endTime
    val sunriseLabel = horizonText(DIAL_SUNRISE, data.sunriseTime, sunriseSun)
    val maghribLabel = horizonText(DIAL_MAGHRIB, maghribTime, sunsetSun)
    fun prayerLabelsPlaced() = data.timelinePrayers.zip(prayerAngles)
        .filter { (prayer, _) -> prayer.prayer != Prayer.FAJR && prayer.prayer != Prayer.MAGHRIB }
        .map { (prayer, angleDegrees) ->
            val size = sizes.prayer(prayer.title, prayer.time)
            val preferred = point(angleDegrees, (radius - 12f - radialExtent(angleDegrees, size)).coerceAtLeast(20f))
            // Inside the ring next to its dot, or the nearest free spot just outside it; when neither is free (a large
            // font), outside it by its dot. On its own sky: the day's prayers on the day half, Isha on the night.
            val marker = point(angleDegrees)
            val daySide = angleDegrees <= sunsetAngle
            val outside = point(angleDegrees, radius + 7f + radialExtent(angleDegrees, size))
            prayer to (
                tryText(preferred, size, RingSide.Either, angleDegrees + 180f, marker, daySky = daySide)
                    ?: tryText(outside, size, RingSide.Outside, angleDegrees, marker, daySky = daySide)
                    ?: crowdedText(preferred, size, prayer.title)
                )
        }
    fun thirdsPlaced() = outsideText(DIAL_FINAL_THIRD, data.thirds[2].startTime, finalThirdAngle) to
        outsideText(DIAL_SECOND_THIRD, data.thirds[1].startTime, secondThirdAngle)

    val fajrLabel = outsideText(DIAL_FAJR, fajrTime, fajrAngle)
    val thirds: Pair<DialLabel, DialLabel>
    val prayerLabels: List<Pair<NightTimelinePrayer, DialLabel>>
    if (prayersFirst) {
        prayerLabels = prayerLabelsPlaced()
        thirds = thirdsPlaced()
    } else {
        thirds = thirdsPlaced()
        prayerLabels = prayerLabelsPlaced()
    }
    val (finalThirdLabel, secondThirdLabel) = thirds
    // The two big words name the halves of the day. The prayer times come first: a word steps aside for them, and on
    // a dial too small for both it is left out.
    fun placeWord(top: Float, size: DialSize, awayDegrees: Float): Rect? =
        space.place(Offset(cx, top + size.height / 2f), size, RingSide.Inside, awayDegrees, maxShift = 24f)?.also { space.addHard(it) }
    val dayWord = if (labelsOnly) null else placeWord(cy - radius + radius * 0.48f, sizes.dayWord, -90f)
    val nightWord = if (labelsOnly) null else placeWord(cy + radius * 0.22f, sizes.nightWord, 90f)
    // The wedge's name, like the big words, only names what the picture shows: it stays inside its wedge, from Fajr
    // to sunrise, and is left out where the wedge has no room for it.
    val twilightLabel = if (labelsOnly) null else run {
        val angle = fajrAngle + twilightSweep * 0.52f
        space.place(clampedCenter(point(angle, radius * 0.58f), sizes.twilight), sizes.twilight, RingSide.Inside, angle + 180f, maxShift = 40f) { candidate ->
            var at = atan2(candidate.center.y - cy, candidate.center.x - cx) * 180f / PI.toFloat()
            while (at < sunriseAngle) at += 360f
            at >= fajrAngle && at <= sunriseAngle + 360f
        }?.also { space.addHard(it) }
    }

    val alarms = if (labelsOnly) emptyList() else placeAlarmLabels(alarmDots, space, radius, Offset(cx, cy), sizes)

    // The sun at midday and the crescent at the middle of the night, last: each takes the nearest spot no text uses.
    val middayAngle = sunriseAngle + daylightSweep / 2f
    val noonSun = if (labelsOnly) null else space.placeCircle(
        start = point(middayAngle, radius + RING_CLEAR_OUTSIDE + DIAL_SUN_REACH + 1f),
        radius = DIAL_SUN_REACH,
        directionDegrees = middayAngle,
    )
    val midnightAngle = sunsetAngle + nightSweep / 2f
    val moon = if (labelsOnly) null else space.placeCircle(
        start = point(midnightAngle, radius + RING_CLEAR_OUTSIDE + DIAL_MOON_RADIUS + 12f),
        radius = DIAL_MOON_RADIUS + 1f,
        directionDegrees = midnightAngle,
    )

    return DayDialLayout(
        panelWidth = panelWidth,
        panelHeight = panelHeight,
        centerX = cx,
        centerY = cy,
        radius = radius,
        sunriseAngle = sunriseAngle,
        daylightSweep = daylightSweep,
        sunsetAngle = sunsetAngle,
        twilightSweep = twilightSweep,
        fajrAngle = fajrAngle,
        secondThirdAngle = secondThirdAngle,
        finalThirdAngle = finalThirdAngle,
        sunriseSun = sunriseSun,
        sunsetSun = sunsetSun,
        sunsetBorderEnd = sunsetBorderEnd,
        prayerAngles = prayerAngles,
        sunrise = sunriseLabel,
        maghrib = maghribLabel,
        fajr = fajrLabel,
        secondThird = secondThirdLabel,
        finalThird = finalThirdLabel,
        twilight = twilightLabel,
        dayWord = dayWord,
        nightWord = nightWord,
        sunAngleLine = sunAngleLine,
        prayerLabels = prayerLabels,
        alarms = alarms,
        noonSun = noonSun,
        moon = moon,
        angleOfMinute = ::angleOf,
        segments = segments,
        fallbackSegment = data.sunriseMinuteOfDay to maghribMinute,
        crowdedTexts = crowdedTexts,
    )
}

internal const val DIAL_SUNRISE = "الشروق"
internal const val DIAL_MAGHRIB = "المغرب"
internal const val DIAL_FAJR = "الفجر"
internal const val DIAL_SECOND_THIRD = "الثلث الثاني"
internal const val DIAL_FINAL_THIRD = "الثلث الأخير"

/** The wedge from Fajr to sunrise: the Fajr prayer's time («الشفق» is the evening glow after sunset). */
internal const val DIAL_FAJR_TIME = "وقت الفجر"

/** The drawn part of a leader line from [marker] to [box]: off the marker dot, ending at the box's edge. */
internal fun leaderEnds(marker: Offset, box: Rect): Pair<Offset, Offset>? {
    val dx = box.center.x - marker.x
    val dy = box.center.y - marker.y
    val length = hypot(dx, dy)
    if (length < 1f) return null
    val ux = dx / length
    val uy = dy / length
    // The box edge along the line: the smaller of the two slab exits from the centre, backwards.
    val toEdgeX = if (abs(ux) > 1e-4f) (box.width / 2f) / abs(ux) else Float.POSITIVE_INFINITY
    val toEdgeY = if (abs(uy) > 1e-4f) (box.height / 2f) / abs(uy) else Float.POSITIVE_INFINITY
    val endDistance = length - minOf(toEdgeX, toEdgeY) - 2f
    val startDistance = MARKER_RADIUS + 2f
    if (endDistance <= startDistance) return null
    return Offset(marker.x + ux * startDistance, marker.y + uy * startDistance) to
        Offset(marker.x + ux * endDistance, marker.y + uy * endDistance)
}

/**
 * The alarms' dots on the ring. They depend only on the times, so they go on the dial before any text, and every
 * text keeps clear of them.
 */
private fun fitAlarmDots(
    data: NightTimesCardData,
    space: DialSpace,
    radius: Float,
    sizes: DialTextSizes,
    angleOf: (Int) -> Float,
    point: (Float, Float) -> Offset,
    barrierAngles: List<Float>,
    nonAlarmMarkerCenters: List<Offset>,
): List<AlarmDot> {
    if (data.timelineAlarms.isEmpty()) return emptyList()
    val minMarkerSpacing = MARKER_RADIUS * 2f + 3.2f
    // Non-alarm markers are ordering barriers: alarms on either side of a prayer or event stay on their side.
    fun gapIndex(angleDegrees: Float): Int = barrierAngles.count { barrier -> barrier < angleDegrees }

    class Candidate(val alarm: NightTimelineAlarm, val angleDegrees: Float, val gapIndex: Int)
    class Draft(candidates: List<Candidate>, val desiredAngleDegrees: Float, val gapIndex: Int) {
        val candidates: List<Candidate> = candidates.sortedBy { it.angleDegrees }
        val alarms: List<NightTimelineAlarm> = candidates.map { it.alarm }.sortedBy { it.minuteOfDay }
    }

    val candidates = data.timelineAlarms.map { alarm ->
        val angle = angleOf(alarm.minuteOfDay)
        Candidate(alarm, angle, gapIndex(angle))
    }
    // Only alarms at the same minute and between the same barriers share a marker.
    val clusters = mutableListOf<MutableList<Candidate>>()
    candidates.sortedBy { it.angleDegrees }.forEach { candidate ->
        val previous = clusters.lastOrNull()
        if (previous != null && previous.first().gapIndex == candidate.gapIndex &&
            previous.any { it.alarm.minuteOfDay == candidate.alarm.minuteOfDay }
        ) {
            previous += candidate
        } else {
            clusters += mutableListOf(candidate)
        }
    }
    if (clusters.size > 1) {
        val first = clusters.first()
        val last = clusters.last()
        if (last.first().gapIndex == first.first().gapIndex &&
            last.any { left -> first.any { right -> left.alarm.minuteOfDay == right.alarm.minuteOfDay } }
        ) {
            last += first
            clusters.removeAt(0)
        }
    }

    val markerMinGap = MARKER_RADIUS + 5.2f + 2f
    val markerMinAngleGap = markerMinGap / radius * (180f / PI.toFloat())
    val alarmMinAngleGap = minMarkerSpacing / radius * (180f / PI.toFloat())
    fun gapBounds(gapIndex: Int): Pair<Float, Float> {
        val lower = if (gapIndex > 0) barrierAngles[gapIndex - 1] else barrierAngles.first() - 360f
        val upper = if (gapIndex < barrierAngles.size) barrierAngles[gapIndex] else barrierAngles.last() + 360f
        return (lower + markerMinAngleGap) to (upper - markerMinAngleGap)
    }

    fun fitAngles(drafts: List<Draft>): List<Pair<Draft, Float>>? {
        if (drafts.isEmpty()) return emptyList()
        val (minAngle, maxAngle) = gapBounds(drafts.first().gapIndex)
        if (minAngle > maxAngle) return null
        val sorted = drafts.sortedBy { it.desiredAngleDegrees }
        val angles = sorted.map { it.desiredAngleDegrees.coerceIn(minAngle, maxAngle) }.toMutableList()
        for (index in 1 until angles.size) {
            angles[index] = angles[index].coerceAtLeast(angles[index - 1] + alarmMinAngleGap)
        }
        if (angles.last() > maxAngle) {
            angles[angles.lastIndex] = maxAngle
            for (index in angles.lastIndex - 1 downTo 0) {
                angles[index] = angles[index].coerceAtMost(angles[index + 1] - alarmMinAngleGap)
            }
        }
        if (angles.first() < minAngle || angles.last() > maxAngle) return null
        return sorted.zip(angles)
    }

    fun mergeClosest(drafts: List<Draft>): List<Draft> {
        if (drafts.size < 2) return drafts
        val sorted = drafts.sortedBy { it.desiredAngleDegrees }
        val mergeIndex = (0 until sorted.lastIndex).minByOrNull { index ->
            sorted[index + 1].desiredAngleDegrees - sorted[index].desiredAngleDegrees
        } ?: return sorted
        val merged = sorted[mergeIndex].candidates + sorted[mergeIndex + 1].candidates
        val mergedDraft = Draft(merged, merged.map { it.angleDegrees }.average().toFloat(), sorted[mergeIndex].gapIndex)
        return sorted.filterIndexed { index, _ -> index != mergeIndex + 1 }
            .mapIndexed { index, draft -> if (index == mergeIndex) mergedDraft else draft }
    }

    fun layoutGap(drafts: List<Draft>): List<Pair<Draft, Float>> {
        var pending = drafts
        while (pending.size > 1) {
            fitAngles(pending)?.let { return it }
            pending = mergeClosest(pending)
        }
        return fitAngles(pending) ?: pending.map { draft ->
            val (minAngle, maxAngle) = gapBounds(draft.gapIndex)
            // Close prayer markers can leave less room than the preferred padding: keep the group between them.
            draft to if (minAngle <= maxAngle) draft.desiredAngleDegrees.coerceIn(minAngle, maxAngle) else (minAngle + maxAngle) / 2f
        }
    }

    val markers = clusters
        .map { cluster ->
            val sorted = cluster.sortedBy { it.angleDegrees }
            val anchor = sorted[sorted.size / 2]
            Draft(sorted, anchor.angleDegrees, anchor.gapIndex)
        }
        .groupBy { it.gapIndex }
        .toSortedMap()
        .flatMap { (_, drafts) -> layoutGap(drafts) }
        .sortedBy { (draft, _) -> draft.desiredAngleDegrees }
    return markers.map { (draft, visualAngle) ->
        val desired = point(draft.desiredAngleDegrees, radius)
        val time = draft.alarms.first().time
        AlarmDot(
            alarms = draft.alarms,
            angle = visualAngle,
            marker = point(visualAngle, radius),
            time = time,
            size = sizes.alarmTime(time),
            nudged = nonAlarmMarkerCenters.any { hypot(it.x - desired.x, it.y - desired.y) < markerMinGap },
        ).also { dot -> space.addCircle(dot.marker, ALARM_DOT_REACH) }
    }
}

/** An alarm's dot on the ring (one or more alarms at one spot) and the time it shows. */
private class AlarmDot(
    val alarms: List<NightTimelineAlarm>,
    val angle: Float,
    val marker: Offset,
    val time: String,
    val size: DialSize,
    val nudged: Boolean,
)

/** An alarm dot with its halo (cluster or nudged dots get a ring of 6.9 dp), and some air. */
private const val ALARM_DOT_REACH = 8.4f

/** The alarms' times, once every other text is placed. */
private fun placeAlarmLabels(
    dots: List<AlarmDot>,
    space: DialSpace,
    radius: Float,
    center: Offset,
    sizes: DialTextSizes,
): List<NightTimelineAlarmPlacement> {
    /** A placed time: [alarms] are what tapping it opens (one alarm, or the whole run behind a range). */
    class Label(val box: Rect, val leader: Pair<Offset, Offset>?, val text: String, val alarms: List<NightTimelineAlarm>)
    val labels = arrayOfNulls<Label>(dots.size)

    fun box(center: Offset, size: DialSize) =
        Rect(center.x - size.width / 2f, center.y - size.height / 2f, center.x + size.width / 2f, center.y + size.height / 2f)

    fun keep(index: Int, label: Label) {
        labels[index] = label
        space.addSoft(label.box.inflate(3f))
        label.leader?.let { (from, to) -> space.addSegment(from, to) }
    }

    /** Lines that cross no text, dot or line already placed, none of [items] but their own, and not each other. */
    fun linesClear(lines: List<Pair<Offset, Offset>?>, items: List<Rect>): Boolean = lines.indices.all { position ->
        val line = lines[position] ?: return@all true
        space.segmentClear(line.first, line.second) &&
            items.indices.none { other -> other != position && segmentHitsRect(line.first, line.second, items[other]) } &&
            lines.indices.none { other ->
                other != position && lines[other]?.let { segmentsCross(line.first, line.second, it.first, it.second) } == true
            }
    }

    /**
     * Times of alarms whose dots sit close together go one under the other ([stacked], where the ring runs up and
     * down) or side by side (where it runs across), each level with its own dot and pushed apart only as far as the
     * times need, in the dots' order so no line crosses another. [besideEach]: each time the same distance from the
     * ring at its own height, so the list follows the ring's curve; else all in one straight column or row. The
     * nearest free spot wins, outside or inside the ring. False when it fits nowhere.
     */
    fun placeListAs(run: List<Int>, stacked: Boolean, besideEach: Boolean): Boolean {
        fun along(index: Int) = if (stacked) dots[index].marker.y else dots[index].marker.x
        fun across(index: Int) = if (stacked) dots[index].marker.x else dots[index].marker.y
        fun length(index: Int) = if (stacked) dots[index].size.height else dots[index].size.width
        val ordered = run.sortedBy(::along)
        val gap = 3f
        val packed = FloatArray(ordered.size)
        ordered.forEachIndexed { position, index ->
            packed[position] = if (position == 0) {
                along(index)
            } else {
                maxOf(along(index), packed[position - 1] + length(ordered[position - 1]) / 2f + gap + length(index) / 2f)
            }
        }
        // Spread evenly around the dots rather than only downwards.
        val drift = ordered.indices.map { packed[it] - along(ordered[it]) }.average().toFloat()
        val thickness = ordered.maxOf { if (stacked) dots[it].size.width else dots[it].size.height }
        val outwardSign = if (run.map(::across).average() < (if (stacked) center.x else center.y)) -1f else 1f
        // Where the ring itself is at a given height (a given x for a row), on the run's side of the dial.
        fun ringAcross(along: Float): Float {
            val offset = along - (if (stacked) center.y else center.x)
            return (if (stacked) center.x else center.y) + outwardSign * sqrt((radius * radius - offset * offset).coerceAtLeast(0f))
        }
        val outerEdge = if (outwardSign < 0f) run.minOf(::across) else run.maxOf(::across)
        val innerEdge = if (outwardSign < 0f) run.maxOf(::across) else run.minOf(::across)
        val sides = listOf(Triple(RingSide.Outside, outerEdge, outwardSign), Triple(RingSide.Inside, innerEdge, -outwardSign))
        var away = 0f
        while (away <= 90f) {
            for ((side, edge, sign) in sides) {
                for (shift in LIST_SHIFTS) {
                    val items = ordered.mapIndexed { position, index ->
                        val alongCenter = packed[position] - drift + shift
                        val acrossCenter = (if (besideEach) ringAcross(alongCenter) else edge) + sign * (MARKER_RADIUS + 4f + thickness / 2f + away)
                        box(if (stacked) Offset(acrossCenter, alongCenter) else Offset(alongCenter, acrossCenter), dots[index].size)
                    }
                    if (items.any { !space.fits(it, side) || !space.clear(it) }) continue
                    val lines = ordered.mapIndexed { position, index -> leaderEnds(dots[index].marker, items[position]) }
                    // A list is for times beside their dots: one that ends up far away gives way to a range.
                    if (lines.any { line -> line != null && hypot(line.second.x - line.first.x, line.second.y - line.first.y) > LIST_MAX_LINE }) continue
                    if (!linesClear(lines, items)) continue
                    ordered.forEachIndexed { position, index ->
                        keep(index, Label(items[position], lines[position], dots[index].time, dots[index].alarms))
                    }
                    return true
                }
            }
            away += 3f
        }
        return false
    }

    /** Beside each dot first, then a straight column or row; the ring's own direction first, then the other. */
    fun placeList(run: List<Int>): Boolean {
        val middle = run.map { dots[it].angle }.average() * PI / 180.0
        val upright = abs(cos(middle)) >= abs(sin(middle))
        return placeListAs(run, upright, besideEach = true) || placeListAs(run, upright, besideEach = false) ||
            placeListAs(run, !upright, besideEach = true) || placeListAs(run, !upright, besideEach = false)
    }

    /**
     * The nearest free spot for a text of [size] joined to [dot], with its line: straight inside, straight outside,
     * then sideways. A spot whose line would cross another text, dot or line is not free. Null when none is.
     */
    fun search(dot: AlarmDot, size: DialSize): Pair<Rect, Pair<Offset, Offset>?>? {
        val radialX = cos(dot.angle * PI / 180.0).toFloat()
        val radialY = sin(dot.angle * PI / 180.0).toFloat()
        val minDistance = ALARM_DOT_REACH + 1f + abs(radialX) * size.width / 2f + abs(radialY) * size.height / 2f
        val inward = atan2((center.y - dot.marker.y).toDouble(), (center.x - dot.marker.x).toDouble())
        var distance = minDistance
        while (distance <= minDistance + ALARM_SEARCH_REACH) {
            for (offset in ALONE_DIRECTIONS) {
                val direction = inward + offset * PI / 180.0
                val labelCenter = Offset(dot.marker.x + distance * cos(direction).toFloat(), dot.marker.y + distance * sin(direction).toFloat())
                val labelBox = box(labelCenter, size)
                if (!space.fits(labelBox, RingSide.Either) || !space.clear(labelBox)) continue
                // The line stops at the time's edge, never inside its digits.
                val leader = leaderEnds(dot.marker, labelBox)
                if (leader != null && !space.segmentClear(leader.first, leader.second, ignoring = labelBox)) continue
                return labelBox to leader
            }
            distance += 4f
        }
        return null
    }

    /**
     * No room for every time of a run: one label with its first and last times, which opens the run's list. Joined
     * to the middle dot, or to the one nearest it that has room.
     */
    fun placeRange(run: List<Int>): Boolean {
        val text = "${dots[run.first()].time}–${dots[run.last()].time}"
        val size = sizes.alarmTime(text)
        val middle = run.size / 2
        for (anchor in run.indices.sortedBy { abs(it - middle) }.map { run[it] }) {
            val (labelBox, leader) = search(dots[anchor], size) ?: continue
            keep(anchor, Label(labelBox, leader, text, run.flatMap { dots[it].alarms }))
            return true
        }
        return false
    }

    /** A time is never drawn over anything: with no free spot it is left out, and its dot still opens the alarm. */
    fun placeAlone(index: Int) {
        val dot = dots[index]
        val (labelBox, leader) = search(dot, dot.size) ?: return
        keep(index, Label(labelBox, leader, dot.time, dot.alarms))
    }

    // Runs of dots closer than a line of text apart: each time beside its dot when they fit, else one range.
    val runs = mutableListOf<MutableList<Int>>()
    dots.indices.forEach { index ->
        val last = runs.lastOrNull()?.last()
        if (last != null && hypot(dots[index].marker.x - dots[last].marker.x, dots[index].marker.y - dots[last].marker.y) <= ALARM_RUN_GAP) {
            runs.last() += index
        } else {
            runs += mutableListOf(index)
        }
    }
    // The longest runs are the hardest to fit: they choose first.
    runs.sortedByDescending { it.size }.forEach { run ->
        when {
            run.size == 1 -> placeAlone(run.single())
            placeList(run) || placeRange(run) -> Unit
            else -> run.forEach(::placeAlone)
        }
    }

    return dots.mapIndexed { index, dot ->
        val label = labels[index]
        NightTimelineAlarmPlacement(
            id = dot.alarms.joinToString("|") { it.id },
            alarms = dot.alarms,
            iconCenterX = dot.marker.x,
            iconCenterY = dot.marker.y,
            nearNonAlarmMarker = dot.nudged,
            labelCenterX = label?.box?.center?.x ?: dot.marker.x,
            labelCenterY = label?.box?.center?.y ?: dot.marker.y,
            labelX = label?.box?.left ?: dot.marker.x,
            labelY = label?.box?.top ?: dot.marker.y,
            labelWidth = label?.box?.width ?: 0f,
            labelHeight = label?.box?.height ?: 0f,
            time = label?.text ?: dot.time,
            leader = label?.leader,
            showsLabel = label != null,
            labelAlarms = label?.alarms ?: dot.alarms,
        )
    }
}

/** Alarm dots this close (dp) share one column of times. */
private const val ALARM_RUN_GAP = 30f

private val ALONE_DIRECTIONS = listOf(0f, 180f, 20f, -20f, 160f, -160f, 40f, -40f, 140f, -140f, 60f, -60f, 120f, -120f, 90f, -90f)

/** How far (dp) past its dot an alarm's time may go: a line across the whole dial says less than no time at all. */
private const val ALARM_SEARCH_REACH = 110f

/** The longest line (dp) from a dot to its time in a list; further than this, the run shows one range instead. */
private const val LIST_MAX_LINE = 34f

/** How far (dp) a column of times may slide along the ring to find room. */
private val LIST_SHIFTS = listOf(0f) + (1..18).flatMap { step -> listOf(step * 4f, -step * 4f) }

internal enum class RingSide { Outside, Inside, Either }

/** Everything already on the dial that a new text or drawing must keep clear of (dp). */
internal class DialSpace(
    private val width: Float,
    private val height: Float,
    private val centerX: Float,
    private val centerY: Float,
    private val radius: Float,
) {
    private val hard = mutableListOf<Rect>()
    private val soft = mutableListOf<Rect>()
    private val circles = mutableListOf<Pair<Offset, Float>>()
    private val segments = mutableListOf<Pair<Offset, Offset>>()
    private val skyEdges = mutableListOf<Pair<Offset, Offset>>()

    /** A line where the sky's colour changes: texts and drawings keep off it, lines may cross it. */
    fun addSkyEdge(from: Offset, to: Offset) {
        skyEdges += from to to
    }

    /** A placed text, word or sun. */
    fun addHard(rect: Rect, padding: Float = 2f) {
        hard += rect.inflate(padding)
    }

    /** A placed alarm time: the alarm search may fall back onto one, never onto a hard obstacle. */
    fun addSoft(rect: Rect) {
        soft += rect
    }

    fun addCircle(center: Offset, radius: Float) {
        circles += center to radius
    }

    fun addSegment(from: Offset, to: Offset) {
        segments += from to to
    }

    /** Inside the panel and on the asked side of the ring, not touching it. */
    fun fits(box: Rect, side: RingSide, margin: Float = 3f): Boolean {
        if (box.left < margin || box.top < margin || box.right > width - margin || box.bottom > height - margin) return false
        val nearest = hypot(centerX.coerceIn(box.left, box.right) - centerX, centerY.coerceIn(box.top, box.bottom) - centerY)
        val farthest = maxOf(
            hypot(box.left - centerX, box.top - centerY),
            hypot(box.right - centerX, box.top - centerY),
            hypot(box.left - centerX, box.bottom - centerY),
            hypot(box.right - centerX, box.bottom - centerY),
        )
        val outside = nearest >= radius + RING_CLEAR_OUTSIDE
        val inside = farthest <= radius - RING_CLEAR_INSIDE
        return when (side) {
            RingSide.Outside -> outside
            RingSide.Inside -> inside
            RingSide.Either -> outside || inside
        }
    }

    fun clearOfHard(box: Rect): Boolean =
        hard.none { it.overlaps(box) } && circles.none { (center, r) -> circleHitsRect(center, r, box) }

    fun clearOfSoft(box: Rect): Boolean =
        soft.none { it.overlaps(box) } &&
            segments.none { (from, to) -> segmentHitsRect(from, to, box) } &&
            skyEdges.none { (from, to) -> segmentHitsRect(from, to, box) }

    fun clear(box: Rect): Boolean = clearOfHard(box) && clearOfSoft(box)

    /** A line that crosses no text (but [ignoring], its own), no dot (but the one it starts from) and no other line. */
    fun segmentClear(from: Offset, to: Offset, ignoring: Rect? = null): Boolean =
        hard.none { it != ignoring && segmentHitsRect(from, to, it) } &&
            soft.none { it != ignoring && segmentHitsRect(from, to, it) } &&
            circles.none { (center, r) -> hypot(from.x - center.x, from.y - center.y) > r + 1f && distanceToSegment(center, from, to) < r } &&
            segments.none { (p, q) -> segmentsCross(from, to, p, q) }

    /**
     * The nearest free box of [size] around [start]: [start] itself, else spots at growing distances, first in
     * [directionDegrees] and then turning away from it. Null when nothing within [maxShift] is free.
     */
    fun place(
        start: Offset,
        size: DialSize,
        side: RingSide,
        directionDegrees: Float,
        maxShift: Float = 96f,
        accept: (Rect) -> Boolean = { true },
    ): Rect? {
        var distance = 0f
        while (distance <= maxShift) {
            for (offset in if (distance == 0f) STRAIGHT else FAN) {
                val radians = (directionDegrees + offset) * PI / 180.0
                val x = start.x + distance * cos(radians).toFloat()
                val y = start.y + distance * sin(radians).toFloat()
                val box = Rect(x - size.width / 2f, y - size.height / 2f, x + size.width / 2f, y + size.height / 2f)
                if (fits(box, side) && clear(box) && accept(box)) return box
            }
            distance += 2f
        }
        return null
    }

    /** [place] for a round drawing of [radius] (the suns, the crescent), kept outside the ring. */
    fun placeCircle(start: Offset, radius: Float, directionDegrees: Float, maxShift: Float = 96f): Offset? {
        var distance = 0f
        while (distance <= maxShift) {
            for (offset in if (distance == 0f) STRAIGHT else FAN) {
                val radians = (directionDegrees + offset) * PI / 180.0
                val center = Offset(start.x + distance * cos(radians).toFloat(), start.y + distance * sin(radians).toFloat())
                if (circleFree(center, radius)) return center
            }
            distance += 2f
        }
        return null
    }

    private fun circleFree(center: Offset, r: Float, margin: Float = 2f): Boolean {
        if (center.x - r < margin || center.y - r < margin || center.x + r > width - margin || center.y + r > height - margin) return false
        if (hypot(center.x - centerX, center.y - centerY) < this.radius + RING_CLEAR_OUTSIDE + r) return false
        return hard.none { circleHitsRect(center, r, it) } &&
            soft.none { circleHitsRect(center, r, it) } &&
            circles.none { (other, otherRadius) -> hypot(other.x - center.x, other.y - center.y) < r + otherRadius } &&
            segments.none { (from, to) -> distanceToSegment(center, from, to) < r } &&
            skyEdges.none { (from, to) -> distanceToSegment(center, from, to) < r }
    }

    private companion object {
        val STRAIGHT = listOf(0f)
        val FAN = listOf(0f, 30f, -30f, 60f, -60f, 90f, -90f, 120f, -120f, 150f, -150f, 180f)
    }
}

/** The day sky outside the ring: above the horizon and the sunset ray, round the panel's corners. */
private fun daySkyPolygon(
    panelWidth: Float,
    panelHeight: Float,
    centerY: Float,
    sunrisePoint: Offset,
    sunsetPoint: Offset,
    sunsetBorderEnd: Offset,
): List<Offset> = buildList {
    add(Offset(0f, 0f))
    add(Offset(panelWidth, 0f))
    if (sunsetBorderEnd.y >= panelHeight - 0.5f) add(Offset(panelWidth, panelHeight))
    add(sunsetBorderEnd)
    add(sunsetPoint)
    add(sunrisePoint)
    add(Offset(0f, centerY))
}

/** Whether [spot] lies on the day sky: the ring's day half inside it, [daySkyOutline] outside it. */
private fun onDaySkyAt(
    spot: Offset,
    center: Offset,
    radius: Float,
    sunriseAngle: Float,
    sunsetAngle: Float,
    daySkyOutline: List<Offset>,
): Boolean {
    val dx = spot.x - center.x
    val dy = spot.y - center.y
    if (hypot(dx, dy) <= radius) {
        var angle = (atan2(dy, dx) * 180f / PI.toFloat())
        while (angle < sunriseAngle) angle += 360f
        return angle <= sunsetAngle
    }
    return insidePolygon(spot, daySkyOutline)
}

private fun insidePolygon(spot: Offset, polygon: List<Offset>): Boolean {
    var inside = false
    var previous = polygon.last()
    for (vertex in polygon) {
        if ((vertex.y > spot.y) != (previous.y > spot.y) &&
            spot.x < (previous.x - vertex.x) * (spot.y - vertex.y) / (previous.y - vertex.y) + vertex.x
        ) {
            inside = !inside
        }
        previous = vertex
    }
    return inside
}

private fun circleHitsRect(center: Offset, radius: Float, rect: Rect): Boolean {
    val nearestX = center.x.coerceIn(rect.left, rect.right)
    val nearestY = center.y.coerceIn(rect.top, rect.bottom)
    return hypot(nearestX - center.x, nearestY - center.y) < radius
}

private fun distanceToSegment(point: Offset, from: Offset, to: Offset): Float {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val lengthSquared = dx * dx + dy * dy
    val t = if (lengthSquared == 0f) 0f else (((point.x - from.x) * dx + (point.y - from.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    return hypot(from.x + t * dx - point.x, from.y + t * dy - point.y)
}

private fun segmentHitsRect(from: Offset, to: Offset, rect: Rect): Boolean {
    if (rect.contains(from) || rect.contains(to)) return true
    val corners = listOf(
        Offset(rect.left, rect.top),
        Offset(rect.right, rect.top),
        Offset(rect.right, rect.bottom),
        Offset(rect.left, rect.bottom),
    )
    return (0 until 4).any { index -> segmentsCross(from, to, corners[index], corners[(index + 1) % 4]) }
}

private fun segmentsCross(a: Offset, b: Offset, c: Offset, d: Offset): Boolean {
    fun cross(o: Offset, p: Offset, q: Offset) = (p.x - o.x) * (q.y - o.y) - (p.y - o.y) * (q.x - o.x)
    val d1 = cross(c, d, a)
    val d2 = cross(c, d, b)
    val d3 = cross(a, b, c)
    val d4 = cross(a, b, d)
    return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) && ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
}

private fun normalizedMinuteOfDay(minuteOfDay: Int): Int =
    ((minuteOfDay % (24 * 60)) + (24 * 60)) % (24 * 60)

internal fun minutesBetweenInDay(startMinuteOfDay: Int, endMinuteOfDay: Int): Int {
    val totalMinutes = 24 * 60
    return (normalizedMinuteOfDay(endMinuteOfDay) - normalizedMinuteOfDay(startMinuteOfDay) + totalMinutes) % totalMinutes
}
