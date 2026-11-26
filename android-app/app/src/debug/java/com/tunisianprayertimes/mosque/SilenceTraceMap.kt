package com.tunisianprayertimes.mosque

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.project
import com.tunisianprayertimes.mosque.trace.TraceEntry
import com.tunisianprayertimes.mosque.trace.breaksRouteContinuity
import com.tunisianprayertimes.mosque.trace.connectsRouteFrom
import com.tunisianprayertimes.mosque.trace.hasCoordinates
import com.tunisianprayertimes.mosque.trace.isRouteSample
import com.tunisianprayertimes.mosque.trace.nearbySilenceAreas
import com.tunisianprayertimes.ui.PrayerSilencePalette as Colors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private data class MapFix(val index: Int, val meters: Offset, val route: Boolean, val connected: Boolean)
private data class MapArea(val name: String, val rings: List<List<Offset>>) {
    val labelPoint = rings.first().minBy { it.y }
}

private fun GeoPoint.metersFrom(origin: GeoPoint): Offset {
    val wrapped = copy(longitude = origin.longitude + ((longitude - origin.longitude + 540) % 360 - 180))
    val (east, north) = project(wrapped, origin)
    return Offset(east.toFloat(), -north.toFloat())
}

private fun gridStep(minimum: Float): Float {
    val power = 10.0.pow(floor(log10(minimum.toDouble()))).toFloat()
    return listOf(1f, 2f, 5f, 10f).first { it * power >= minimum } * power
}

/** North-up local metric map. Only independently recorded route samples can form a line. */
@Composable
internal fun SilenceTraceMap(
    sessionId: Long,
    entries: List<TraceEntry>,
    selected: Int,
    nearbyRoute: Int,
    onSelect: (Int) -> Unit,
) {
    val origin = remember(entries) {
        entries.firstOrNull { it.hasCoordinates() }?.let { GeoPoint(it.latitude!!, it.longitude!!) }
    }
    if (origin == null) {
        Text("لم تُسجَّل إحداثيات لهذه الجولة بعد.", color = Colors.SecondaryText)
        return
    }
    val fixes = remember(entries, origin) {
        var previous: TraceEntry? = null
        entries.mapIndexedNotNull { index, entry ->
            if (entry.breaksRouteContinuity) previous = null
            val connects = previous?.let(entry::connectsRouteFrom) == true
            if (entry.isRouteSample) previous = entry
            if (!entry.hasCoordinates()) return@mapIndexedNotNull null
            MapFix(index, GeoPoint(entry.latitude!!, entry.longitude!!).metersFrom(origin), entry.isRouteSample, connects)
        }
    }
    val context = LocalContext.current
    val savedAreas by produceState(initialValue = emptyList<SavedSilenceArea>(), sessionId, context) {
        value = withContext(Dispatchers.IO) { PrivateSilenceAreaStore.read(context).areas }
    }
    val areas by produceState(initialValue = emptyList<SavedSilenceArea>(), savedAreas, entries) {
        value = withContext(Dispatchers.Default) { nearbySilenceAreas(entries, savedAreas) }
    }
    val polygons = remember(areas, origin) {
        areas.flatMap { area ->
            area.geometry.polygons.map { polygon ->
                MapArea(area.name, (listOf(polygon.exterior) + polygon.holes).map { ring -> ring.map { it.metersFrom(origin) } })
            }
        }
    }
    val bounds = remember(fixes, polygons) {
        val positions = fixes.map { it.meters } + polygons.flatMap { it.rings.first() }
        Rect(positions.minOf { it.x }, positions.minOf { it.y },
            positions.maxOf { it.x }, positions.maxOf { it.y })
    }
    val focusIndex = if (nearbyRoute >= 0) nearbyRoute else selected
    val focused = fixes.binarySearchBy(focusIndex) { it.index }.takeIf { it >= 0 }?.let { fixes[it] }
    var magnify by rememberSaveable(sessionId) { mutableStateOf(false) }
    val onTap by rememberUpdatedState(onSelect)

    BoxWithConstraints(Modifier.fillMaxWidth().height(228.dp).clip(RoundedCornerShape(14.dp)).background(Colors.TintedStrip)) {
        val density = LocalDensity.current
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val measurer = rememberTextMeasurer()
        val labelStyle = MaterialTheme.typography.labelSmall.copy(color = Colors.PrimaryText)
        val labelWidth = with(density) { 112.dp.roundToPx() }.coerceAtMost(constraints.maxWidth)
        val labels = remember(polygons, labelWidth, labelStyle, measurer) {
            polygons.map { polygon -> measurer.measure(polygon.name, labelStyle, maxLines = 1,
                overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = labelWidth)) }
        }
        val margin = with(density) { 26.dp.toPx() }
        val centerMeters = if (magnify && focused != null) focused.meters else bounds.center
        val scale = if (magnify && focused != null) minOf(width, height) / 100f
        else minOf((width - 2 * margin) / bounds.width.coerceAtLeast(30f),
            (height - 2 * margin) / bounds.height.coerceAtLeast(30f)).coerceAtLeast(0.000001f)
        val originPx = Offset(width / 2, height / 2) - centerMeters * scale
        fun screen(meters: Offset) = originPx + meters * scale
        val stepMeters = gridStep(with(density) { 32.dp.toPx() } / scale)
        val stepPx = stepMeters * scale
        val radius = entries[focusIndex].accuracyMeters
        val description = "خريطة المسار، الشمال إلى الأعلى. " + when {
            nearbyRoute >= 0 -> "الدائرة الفارغة تبيّن آخر نقطة مسجّلة قبل الحدث."
            focused != null -> "الموضع المحدّد ظاهر على الخريطة."
            else -> "الحدث المحدّد بلا إحداثيات."
        }
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }
            .pointerInput(fixes, centerMeters, scale, selected) {
                detectTapGestures { tap ->
                    val nearest = fixes.asSequence().filter { it.route || it.index <= selected }
                        .minWithOrNull(compareBy<MapFix> { (screen(it.meters) - tap).getDistanceSquared() }
                            .thenBy { it.route }.thenByDescending { it.index })
                    if (nearest != null && (screen(nearest.meters) - tap).getDistance() <= 22.dp.toPx()) {
                        onTap(nearest.index)
                    }
                }
            }) {
            // Align grid to the middle of the current view to keep coordinates and spacing stable.
            var x = (size.width / 2) % stepPx
            while (x < size.width) {
                drawLine(Colors.SoftBorder, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                x += stepPx
            }
            var y = (size.height / 2) % stepPx
            while (y < size.height) {
                drawLine(Colors.SoftBorder, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                y += stepPx
            }
            val drawnLabels = mutableListOf<Rect>()
            polygons.forEachIndexed { index, area ->
                val outline = Path().apply { fillType = PathFillType.EvenOdd }
                area.rings.forEach { ring ->
                    ring.forEachIndexed { i, point ->
                        val pos = screen(point)
                        if (i == 0) outline.moveTo(pos.x, pos.y) else outline.lineTo(pos.x, pos.y)
                    }
                    outline.close()
                }
                drawPath(outline, Colors.GoldAccent.copy(alpha = 0.22f))
                drawPath(outline, Colors.GoldAccent, style = Stroke(1.4.dp.toPx()))
                val anchor = screen(area.labelPoint)
                if (anchor.x in 0f..size.width && anchor.y in 0f..size.height) {
                    val label = labels[index]
                    val left = (anchor.x - label.size.width / 2f).coerceIn(0f, (size.width - label.size.width).coerceAtLeast(0f))
                    val top = (anchor.y - label.size.height).coerceAtLeast(0f)
                    val box = Rect(left, top, left + label.size.width, top + label.size.height)
                    if (drawnLabels.none(box::overlaps)) {
                        drawRect(Colors.Surface.copy(alpha = 0.90f), box.topLeft, box.size)
                        drawText(label, topLeft = box.topLeft)
                        drawnLabels.add(box)
                    }
                }
            }
            var previous: Offset? = null
            fixes.forEach { fix ->
                if (fix.route) {
                    val p = screen(fix.meters)
                    val color = if (fix.index <= selected) Colors.InteractiveTeal else Colors.Tick
                    if (fix.connected) previous?.let { drawLine(color, it, p, 2.2.dp.toPx()) }
                    drawCircle(color, 1.9.dp.toPx(), p)
                    previous = p
                }
            }
            fixes.forEach { fix ->
                if (!fix.route && fix.index <= selected) {
                    drawCircle(Colors.GoldAccent, 3.4.dp.toPx(), screen(fix.meters))
                }
            }
            if (focused != null) {
                val p = screen(focused.meters)
                if (radius != null && radius.isFinite() && radius > 0) {
                    val r = (radius * scale).coerceAtMost((size.width + size.height).toDouble()).toFloat()
                    drawCircle(Colors.Halo, r, p)
                    drawCircle(Colors.InteractiveTeal.copy(alpha = 0.4f), r, p, style = Stroke(1.dp.toPx()))
                }
                drawCircle(Colors.Surface, 7.dp.toPx(), p)
                if (nearbyRoute >= 0) {
                    drawCircle(Colors.InteractiveTeal, 6.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                } else {
                    drawCircle(if (focused.route) Colors.InteractiveTeal else Colors.GoldAccent, 5.dp.toPx(), p)
                }
            }
        }
        val gridLabel = if (stepMeters >= 1000) "${(stepMeters / 1000).roundToInt()} كم" else "${stepMeters.roundToInt()} م"
        Text("↑ شمال", Modifier.align(Alignment.TopStart).background(Colors.TintedStrip).padding(6.dp),
            color = Colors.PrimaryText, style = MaterialTheme.typography.labelMedium)
        Text("ضلع المربّع $gridLabel", Modifier.align(Alignment.BottomEnd).background(Colors.TintedStrip).padding(5.dp),
            color = Colors.PrimaryText, style = MaterialTheme.typography.labelSmall)
    }
    Text("الأخضر الداكن هو المسار حتى الوقت المحدّد. النقاط الذهبية قراءات أو أحداث لها موقع. ينقطع الخط عند تغيّر المصدر أو بعد دقيقتين بلا قراءة.",
        color = Colors.SecondaryText, style = MaterialTheme.typography.bodySmall)
    if (polygons.isNotEmpty()) {
        Text("المضلّعات الذهبية تبيّن مناطق الإسكات القريبة المفعّلة حاليًا: " + areas.joinToString("، ") { it.name },
            color = Colors.SecondaryText,
            style = MaterialTheme.typography.bodySmall)
    }
    if (focused != null || magnify) {
        TextButton(onClick = { magnify = !magnify }) { Text(if (magnify) "عرض المسار الكامل" else "تكبير حول الموضع المحدّد") }
    }
}
