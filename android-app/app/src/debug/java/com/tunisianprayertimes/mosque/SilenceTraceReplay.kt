package com.tunisianprayertimes.mosque

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.mosque.trace.TraceEntry
import com.tunisianprayertimes.mosque.trace.TraceSession
import com.tunisianprayertimes.mosque.trace.displayTitle
import com.tunisianprayertimes.mosque.trace.hasCoordinates
import com.tunisianprayertimes.mosque.trace.isRouteSample
import com.tunisianprayertimes.mosque.trace.nearbyRouteIndices
import com.tunisianprayertimes.ui.PrayerSilencePalette as Colors
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Embeddable in a scrollable settings page. Entries arrive in stable (time, id) order. */
@Composable
internal fun SilenceTraceReplay(session: TraceSession, entries: List<TraceEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) {
        Text("في انتظار أول حدث.", modifier, color = Colors.SecondaryText)
        return
    }
    key(session.id) {
        var eventsOnly by rememberSaveable { mutableStateOf(false) }
        val visible = remember(entries, eventsOnly) {
            entries.indices.filter { !eventsOnly || (!entries[it].isRouteSample && entries[it].kind != "monitor_fix") }
        }
        val nearby = remember(entries) { nearbyRouteIndices(entries) }
        // No selection means the newest visible record, including new samples during a live walk.
        var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
        var playing by remember { mutableStateOf(false) }
        val selection = remember(visible, entries, selectedId) {
            val index = selectedId?.let { id -> entries.indexOfFirst { it.id == id } } ?: -1
            if (index < 0 || visible.isEmpty()) visible.lastIndex else {
                val found = visible.binarySearch(index)
                if (found >= 0) found else (-found - 2).coerceAtLeast(0)
            }
        }
        val currentIndex = visible.getOrNull(selection)
        fun select(row: Int) {
            selectedId = if (row == visible.lastIndex) null else visible.getOrNull(row)?.let { entries[it].id }
        }
        val formatter = remember(session.timeZoneId) {
            DateTimeFormatter.ofPattern("dd MMM، HH:mm:ss", Locale.forLanguageTag("ar-TN"))
                .withZone(ZoneId.of(session.timeZoneId))
        }
        LaunchedEffect(playing, selection, visible.size) {
            if (!playing) return@LaunchedEffect
            if (selection >= visible.lastIndex) {
                playing = false
                return@LaunchedEffect
            }
            delay(900)
            select(selection + 1)
        }
        Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("خريطة المسار", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                color = Colors.PrimaryText, fontWeight = FontWeight.Bold)
            val mapIndex = currentIndex ?: entries.lastIndex
            SilenceTraceMap(session.id, entries, mapIndex, nearby[mapIndex]) { index ->
                eventsOnly = false
                playing = false
                selectedId = entries[index].id
            }
            Text("الخط الزمني", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                color = Colors.PrimaryText, fontWeight = FontWeight.Bold)
            FilterChip(
                selected = eventsOnly, onClick = {
                    eventsOnly = !eventsOnly
                    playing = false
                }, label = { Text("الأحداث فقط") },
            )
            if (currentIndex == null) {
                Text("لا توجد أحداث مسجّلة في هذا العرض.", color = Colors.SecondaryText)
            } else {
                val current = entries[currentIndex]
                val stamp = formatter.format(Instant.ofEpochMilli(current.at))
                Surface(shape = RoundedCornerShape(12.dp), color = Colors.TintedStrip) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("$stamp  ·  ${selection + 1} / ${visible.size}", style = MaterialTheme.typography.labelMedium,
                            color = Colors.SecondaryText)
                        Text(current.displayTitle, fontWeight = FontWeight.Bold, color = Colors.PrimaryText)
                        if (current.detail.isNotBlank() && current.detail != current.displayTitle) {
                            Text(current.detail, style = MaterialTheme.typography.bodyMedium, color = Colors.PrimaryText)
                        }
                        Text(positionDescription(current, entries.getOrNull(nearby[currentIndex])),
                            style = MaterialTheme.typography.bodySmall, color = Colors.SecondaryText)
                    }
                }
                if (visible.size > 1) {
                    Slider(value = selection.toFloat(), onValueChange = {
                        playing = false
                        select(it.roundToInt())
                    }, valueRange = 0f..visible.lastIndex.toFloat(),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "انتقال عبر الخط الزمني" })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = selection > 0, onClick = { playing = false; select(selection - 1) }) { Text("السابق") }
                        TextButton(onClick = {
                            if (!playing && selection == visible.lastIndex) select(0)
                            playing = !playing
                        }) { Text(if (playing) "إيقاف العرض" else "تشغيل التسلسل") }
                        TextButton(enabled = selection < visible.lastIndex, onClick = { playing = false; select(selection + 1) }) {
                            Text("التالي")
                        }
                    }
                    val list = rememberLazyListState()
                    LaunchedEffect(selection, visible) {
                        val shown = list.layoutInfo.visibleItemsInfo
                        if (shown.none { it.index == selection }) list.scrollToItem(selection)
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp), state = list) {
                        items(visible.size, key = { entries[visible[it]].id }) { row ->
                            val entry = entries[visible[row]]
                            Surface(shape = RoundedCornerShape(8.dp),
                                color = if (row == selection) Colors.FieldPressed else Colors.Surface) {
                                Column(Modifier.fillMaxWidth()
                                    .selectable(selected = row == selection, role = Role.RadioButton, onClick = {
                                        playing = false
                                        select(row)
                                    }).padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    Text(formatter.format(Instant.ofEpochMilli(entry.at)),
                                        style = MaterialTheme.typography.labelSmall, color = Colors.SecondaryText)
                                    Text(entry.displayTitle, style = MaterialTheme.typography.bodyMedium, color = Colors.PrimaryText,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun positionDescription(entry: TraceEntry, nearby: TraceEntry?): String {
    val point = if (entry.hasCoordinates()) entry else nearby ?: return "بلا إحداثيات مسجّلة لهذا الحدث."
    val coordinates = String.format(Locale.US, "%.6f, %.6f", point.latitude, point.longitude)
    val accuracy = point.accuracyMeters?.takeIf { it.isFinite() && it >= 0 }?.let {
        String.format(Locale.US, "%.1f", it) + " م"
    } ?: "غير معروفة"
    val location = "الإحداثيات ⁦$coordinates⁩  ·  دقّة الموقع $accuracy"
    if (nearby == null) return location
    val seconds = String.format(Locale.US, "%.1f", (entry.at - nearby.at) / 1000.0)
    return "بلا إحداثيات مسجّلة لهذا الحدث. الدائرة الفارغة تبيّن آخر نقطة من المسار، قبل الحدث بـ $seconds ثانية.\n$location"
}
