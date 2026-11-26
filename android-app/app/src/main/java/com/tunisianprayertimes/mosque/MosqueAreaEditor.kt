package com.tunisianprayertimes.mosque

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import com.tunisianprayertimes.mosque.presence.firstBoundaryCrossing
import com.tunisianprayertimes.ui.AppModalBottomSheet
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

@Composable
internal fun AreaEditor(initial: AreaDraft, saved: List<SavedSilenceArea>, onClose: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose) // Keep the recoverable draft; Cancel explicitly discards it.
    var draft by remember { mutableStateOf(initial) }
    var undo by remember { mutableStateOf(emptyList<String>()) }
    var manual by rememberSaveable(initial.key) { mutableStateOf(false) }
    var rectangle by remember { mutableStateOf(false) }
    var chooser by remember { mutableStateOf(false) }
    var testArrival by rememberSaveable(initial.key) { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var locationFailed by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var mapReady by remember { mutableStateOf<Boolean?>(null) }
    var finishAttempted by remember { mutableStateOf(false) }
    var userLocation by remember { mutableStateOf<GeoPoint?>(null) }
    val points = remember(draft.coordinates) { parseCoordinateLines(draft.coordinates)?.openOutline() }
    val editablePolygon = remember(points) {
        if (points != null && points.size in 3..PrivateSilenceAreaStore.MAX_VERTICES) {
            runCatching { SilencePolygon(points) }.getOrNull()
        } else null
    }
    val fixed = draft.fixedPolygons
    val polygons = fixed.ifEmpty { listOfNotNull(editablePolygon) }
    val mode = draft.mode
    val crossing = remember(points, mode, finishAttempted) {
        points?.let { firstBoundaryCrossing(it, closed = mode != BoundaryEditMode.DRAW || finishAttempted) }
    }
    var focus by remember { mutableStateOf(BoundaryFocus(draft.cataloguePin, points.orEmpty() + fixed.flatMap { it.exterior })) }

    fun update(value: AreaDraft) {
        draft = value
        error = null
        runCatching { PrivateSilenceAreaStore.saveDraft(context, value) }
            .onFailure { error = R.string.mosque_save_failed }
    }
    fun replaceCoordinates(text: String, nextMode: BoundaryEditMode = mode) {
        if (text == draft.coordinates && nextMode == mode) return
        if (text != draft.coordinates) undo = (undo + draft.coordinates).takeLast(30)
        finishAttempted = false
        update(draft.copy(coordinates = text.take(32_000), mode = nextMode))
    }
    fun undoCoordinates() {
        finishAttempted = false
        update(draft.copy(coordinates = undo.last()))
        undo = undo.dropLast(1)
    }
    fun finishDrawing() {
        if (editablePolygon == null) {
            finishAttempted = true
        } else {
            finishAttempted = false
            update(draft.copy(mode = BoundaryEditMode.ADJUST))
        }
    }
    fun locate() {
        if (locating) return
        locationFailed = false
        locating = true
        scope.launch {
            val point = currentMapLocation(context)
            if (point != null) {
                userLocation = point
                focus = BoundaryFocus(point = point)
            } else {
                userLocation = null
                locationFailed = true
            }
            locating = false
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) locate() else {
            userLocation = null
            locationFailed = true
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(bottom = 4.dp)) {
        val nameField = @Composable {
            OutlinedTextField(draft.name, onValueChange = { update(draft.copy(name = it.take(100))) },
                label = { Text(stringResource(R.string.mosque_area_name)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text(stringResource(R.string.mosque_back)) }
            Text(stringResource(R.string.mosque_editor_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = {
                if (manual) {
                    focus = BoundaryFocus(draft.cataloguePin, points.orEmpty() + fixed.flatMap { it.exterior })
                    mapReady = null
                }
                manual = !manual
            }) { Text(stringResource(when {
                manual -> R.string.mosque_editor_map
                fixed.isNotEmpty() -> R.string.mosque_editor_preview
                else -> R.string.mosque_editor_coordinates
            })) }
        }
        if (manual) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (fixed.isEmpty()) R.string.mosque_editor_help else R.string.mosque_complex_read_only))
                nameField()
                if (fixed.isEmpty()) {
                    OutlinedTextField(draft.coordinates, onValueChange = { replaceCoordinates(it) },
                        label = { Text(stringResource(R.string.mosque_coordinates)) },
                        modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 8,
                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
                    Row {
                        TextButton(onClick = { rectangle = true }) { Text(stringResource(R.string.mosque_rectangle)) }
                        TextButton(enabled = undo.isNotEmpty(), onClick = ::undoCoordinates) {
                            Text(stringResource(R.string.mosque_undo))
                        }
                        if (mode == BoundaryEditMode.DRAW) {
                            TextButton(enabled = points.orEmpty().size >= 3, onClick = ::finishDrawing) {
                                Text(stringResource(R.string.mosque_map_finish))
                            }
                        }
                    }
                }
                if (polygons.isNotEmpty() && mode != BoundaryEditMode.DRAW) PolygonPreview(polygons)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (initial.id == null && points.isNullOrEmpty() && fixed.isEmpty()) {
                    OutlinedButton(onClick = { chooser = true }) { Text(stringResource(R.string.mosque_choose_catalogue)) }
                }
                TextButton(enabled = !locating, onClick = {
                    if (hasMapLocation(context)) locate() else locationPermission.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    )
                }) { Text(stringResource(R.string.mosque_map_locate)) }
                if (locating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                MosqueBoundaryMap(points.orEmpty(), fixed, crossing, draft.cataloguePin, userLocation, mode, focus,
                    Modifier.fillMaxSize(), { mapReady = it }) {
                    replaceCoordinates(coordinateText(it))
                }
                if (mapReady == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
                if (mapReady == false) Surface(Modifier.align(Alignment.Center).padding(16.dp), shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.mosque_map_unavailable), Modifier.padding(12.dp))
                }
                if (mapReady == true) Surface(
                    Modifier.align(Alignment.BottomStart).padding(4.dp)
                        .clickable { uriHandler.openUri("https://www.openstreetmap.org/copyright") },
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(stringResource(R.string.mosque_map_attribution), Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                if (locationFailed) {
                    Text(stringResource(R.string.mosque_map_location_failed), style = MaterialTheme.typography.bodySmall)
                }
                val help = when (mode) {
                    BoundaryEditMode.DRAW -> R.string.mosque_map_draw_help
                    BoundaryEditMode.ADJUST -> R.string.mosque_map_adjust_help
                    BoundaryEditMode.VIEW -> when {
                        fixed.isNotEmpty() -> R.string.mosque_complex_read_only
                        editablePolygon != null -> R.string.mosque_map_confirm_help
                        else -> R.string.mosque_map_start
                    }
                }
                Text(stringResource(help), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
                if (fixed.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    if (mode == BoundaryEditMode.VIEW) {
                        TextButton(enabled = mapReady == true, onClick = {
                            val next = if (editablePolygon == null) BoundaryEditMode.DRAW else BoundaryEditMode.ADJUST
                            update(draft.copy(mode = next))
                        }) { Text(stringResource(if (editablePolygon != null) R.string.mosque_map_adjust else R.string.mosque_map_draw_begin)) }
                    } else {
                        if (mode == BoundaryEditMode.DRAW) {
                            TextButton(enabled = points.orEmpty().size >= 3, onClick = ::finishDrawing) {
                                Text(stringResource(R.string.mosque_map_finish))
                            }
                        }
                        TextButton(enabled = undo.isNotEmpty(), onClick = ::undoCoordinates) {
                            Text(stringResource(R.string.mosque_undo))
                        }
                        TextButton(enabled = !points.isNullOrEmpty(), onClick = {
                            replaceCoordinates("", BoundaryEditMode.DRAW)
                        }) { Text(stringResource(R.string.mosque_map_clear)) }
                    }
                }
                nameField()
            }
        }
        val validationError = error ?: when {
            crossing != null -> R.string.mosque_map_crossing
            finishAttempted || (mode != BoundaryEditMode.DRAW && draft.coordinates.isNotBlank() &&
                (manual || points == null || points.size >= 3) && editablePolygon == null) -> R.string.mosque_geometry_error
            else -> null
        }
        validationError?.let {
            Text(stringResource(it), Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mosque_area_enable), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = draft.enabled, onCheckedChange = { update(draft.copy(enabled = it)) },
                modifier = Modifier.semantics { contentDescription = context.getString(R.string.mosque_area_enable) })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(modifier = Modifier.weight(1f),
                enabled = polygons.isNotEmpty() && mode != BoundaryEditMode.DRAW && draft.name.isNotBlank(), onClick = {
                runCatching { PrivateSilenceAreaStore.save(context, draft, polygons) }
                    .onSuccess { onClose() }.onFailure { error = R.string.mosque_save_failed }
            }) { Text(stringResource(R.string.mosque_area_save)) }
            OutlinedButton(enabled = polygons.isNotEmpty() && mode != BoundaryEditMode.DRAW,
                onClick = { testArrival = true }) { Text(stringResource(R.string.mosque_test_arrival)) }
            TextButton(onClick = { PrivateSilenceAreaStore.clearDraft(context, draft.key); onClose() }) {
                Text(stringResource(R.string.mosque_cancel))
            }
        }
    }
    if (testArrival) MosqueArrivalTestDialog(polygons,
        stricter = saved.firstOrNull { it.geometry.id == draft.id }?.geometry?.requireStrongerEvidence == true,
        onDismiss = { testArrival = false })
    if (chooser) CatalogueChooser(userLocation, onClose = { chooser = false }) { mosque, approved ->
        chooser = false
        val id = "catalogue:${mosque.id}"
        val existing = saved.firstOrNull { it.geometry.id == id }
        val selected = existing?.geometry?.polygons ?: approved
        val pin = mosque.coordinate?.let { GeoPoint(it.latitude, it.longitude) }
        focus = BoundaryFocus(pin, selected.flatMap { it.exterior })
        finishAttempted = false
        undo = emptyList()
        update(draft.copy(id = id, name = (existing?.name ?: mosque.displayName()).take(100),
            enabled = existing?.geometry?.enabled ?: draft.enabled, cataloguePin = pin).withPolygons(selected))
    }
    if (rectangle) RectangleDialog(onClose = { rectangle = false }) {
        replaceCoordinates(coordinateText(it), BoundaryEditMode.ADJUST)
    }
}

private data class CatalogueMosque(val mosque: Mosque, val polygons: List<SilencePolygon>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogueChooser(nearby: GeoPoint?, onClose: () -> Unit, onChoose: (Mosque, List<SilencePolygon>) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val catalog by produceState<Result<List<CatalogueMosque>>?>(null, context) {
        value = withContext(Dispatchers.IO) {
            runCatching { MosqueRepository.load(context).mosques
                .filter { it.displayName().isNotBlank() }
                .map { CatalogueMosque(it, it.approvedPolygons()) }
            }
        }
    }
    val options = remember(catalog, nearby) {
        val mosques = catalog?.getOrNull().orEmpty()
        if (nearby == null) mosques.sortedWith(compareByDescending<CatalogueMosque> { it.polygons.isNotEmpty() }
            .thenBy { it.mosque.displayName() })
        else mosques.sortedBy { choice ->
            choice.mosque.coordinate?.let { coordinate ->
                val lat = coordinate.latitude - nearby.latitude
                val lon = coordinate.longitude - nearby.longitude
                lat * lat + lon * lon
            } ?: Double.POSITIVE_INFINITY
        }
    }
    val matches = remember(options, query) {
        val words = normalizeMosqueText(query).split(' ').filter { it.isNotEmpty() }
        options.asSequence().filter { choice ->
            val mosque = choice.mosque
            val names = normalizeMosqueText(listOfNotNull(mosque.nameAr, mosque.nameFr, mosque.name).joinToString(" "))
            words.all { names.contains(it) }
        }.take(120).toList()
    }
    AppModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.76f).padding(horizontal = 16.dp, vertical = 6.dp)) {
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.mosque_search_catalogue)) })
            when {
                catalog == null -> CircularProgressIndicator(Modifier.padding(16.dp))
                catalog?.isFailure == true -> Text(stringResource(R.string.mosque_catalogue_unavailable), Modifier.padding(12.dp))
                matches.isEmpty() -> Text(stringResource(R.string.mosque_catalogue_no_results), Modifier.padding(12.dp))
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(matches, key = { it.mosque.id }) { choice ->
                        ListItem(
                            headlineContent = { Text(choice.mosque.displayName()) },
                            supportingContent = { Text(stringResource(if (choice.polygons.isNotEmpty())
                                R.string.mosque_catalogue_approved else R.string.mosque_catalogue_draw_needed)) },
                            modifier = Modifier.clickable { onChoose(choice.mosque, choice.polygons) },
                        )
                    }
                }
            }
        }
    }
}

private fun Mosque.displayName() = nameAr ?: name ?: nameFr.orEmpty()

private val searchMarks = Regex("[\\u0640\\u064B-\\u065F\\u0670]")
private val alefForms = Regex("[أإآٱ]")
private val coordinateSeparators = Regex("[,;\\s]+")

private fun normalizeMosqueText(value: String) =
    value.lowercase(Locale.ROOT).replace(searchMarks, "").replace(alefForms, "ا").trim()

private fun parseCoordinateLines(value: String): List<GeoPoint>? = runCatching {
    val points = value.lineSequence().filter { it.isNotBlank() }.map { line ->
        val parts = line.trim().split(coordinateSeparators)
        require(parts.size == 2)
        GeoPoint(parts[0].toDouble(), parts[1].toDouble()).also { require(it.isValid()) }
    }.take(PrivateSilenceAreaStore.MAX_VERTICES + 2).toList()
    require(points.size <= PrivateSilenceAreaStore.MAX_VERTICES + 1)
    points
}.getOrNull()

@Composable
private fun RectangleDialog(onClose: () -> Unit, onConfirm: (List<GeoPoint>) -> Unit) {
    var text by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.mosque_rectangle)) },
        text = {
            Column {
                Text(stringResource(R.string.mosque_rectangle_help))
                OutlinedTextField(text, { text = it; invalid = false }, minLines = 2, isError = invalid,
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
            }
        }, confirmButton = {
            TextButton(onClick = {
                val corners = parseCoordinateLines(text)
                if (corners?.size != 2) {
                    invalid = true
                    return@TextButton
                }
                val west = corners.minOf { it.longitude }
                val east = corners.maxOf { it.longitude }
                val south = corners.minOf { it.latitude }
                val north = corners.maxOf { it.latitude }
                val points = listOf(GeoPoint(south, west), GeoPoint(south, east), GeoPoint(north, east), GeoPoint(north, west))
                if (runCatching { SilencePolygon(points) }.isSuccess) {
                    onConfirm(points)
                    onClose()
                } else invalid = true
            }) { Text(stringResource(R.string.mosque_continue)) }
        }, dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.mosque_cancel)) } },
    )
}

@Composable
private fun PolygonPreview(polygons: List<SilencePolygon>) {
    val rings = polygons.flatMap { listOf(it.exterior) + it.holes }
    val points = rings.flatten()
    val description = stringResource(R.string.mosque_preview_description, points.size.toString())
    Canvas(Modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = description }) {
        val minX = points.minOf { it.longitude }
        val maxX = points.maxOf { it.longitude }
        val minY = points.minOf { it.latitude }
        val maxY = points.maxOf { it.latitude }
        val scale = minOf((size.width - 32) / (maxX - minX).toFloat(), (size.height - 32) / (maxY - minY).toFloat())
        val path = Path().apply { fillType = PathFillType.EvenOdd }
        rings.forEach { ring ->
            ring.forEachIndexed { index, point ->
                val x = 16 + ((point.longitude - minX) * scale).toFloat()
                val y = size.height - 16 - ((point.latitude - minY) * scale).toFloat()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
        }
        drawPath(path, Color(0x33427A55))
        drawPath(path, Color(0xFF427A55), style = Stroke(3f))
    }
}

internal fun hasMapLocation(context: Context) = listOf(
    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
).any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

@SuppressLint("MissingPermission")
private suspend fun currentMapLocation(context: Context): GeoPoint? {
    if (!hasMapLocation(context)) return null
    val token = CancellationTokenSource()
    return withTimeoutOrNull(10_000L) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { token.cancel() }
            try {
                LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                        .setMaxUpdateAgeMillis(0).setDurationMillis(10_000L).build(), token.token)
                    .addOnCompleteListener { task ->
                        val point = if (task.isSuccessful) task.result?.let { GeoPoint(it.latitude, it.longitude) } else null
                        if (continuation.isActive) continuation.resume(point?.takeIf { it.isValid() })
                    }
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }
}
