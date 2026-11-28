package com.tunisianprayertimes.mosque

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.BuildConfig
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import kotlin.math.cos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private fun pair(point: GeoPoint) = JSONArray().put(point.longitude).put(point.latitude)

private fun geo(pair: JSONArray) = GeoPoint(pair.getDouble(1), pair.getDouble(0))

internal fun reportGeometry(polygons: List<SilencePolygon>): JSONObject {
    val parts = JSONArray()
    for (polygon in polygons) {
        val rings = JSONArray()
        for (ring in listOf(polygon.exterior) + polygon.holes) {
            val open = ring.openOutline()
            rings.put(JSONArray().apply { (open + open.first()).forEach { put(pair(it)) } })
        }
        parts.put(rings)
    }
    return if (parts.length() == 1)
        JSONObject().put("type", "Polygon").put("coordinates", parts.getJSONArray(0))
    else JSONObject().put("type", "MultiPolygon").put("coordinates", parts)
}

private fun shapes(g: JSONObject?): List<SilencePolygon> {
    if (g == null || g.optString("type") == "Point") return emptyList()
    val coords = g.getJSONArray("coordinates")
    val parts = if (g.getString("type") == "Polygon") JSONArray().put(coords) else coords
    return List(parts.length()) { i ->
        val p = parts.getJSONArray(i)
        val rings =
            List(p.length()) { j ->
                val r = p.getJSONArray(j)
                List(r.length()) { geo(r.getJSONArray(it)) }.openOutline()
            }
        SilencePolygon(rings.first(), rings.drop(1))
    }
}

@Composable
internal fun MapProposalForm(
    initialKind: String,
    seedId: String?,
    initialGeometry: String?,
    review: (String, JSONObject) -> Unit,
) {
    val c = LocalContext.current
    val catalogue by
        produceState<List<Mosque>>(emptyList(), c) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { MosqueRepository.load(c).mosques }.getOrDefault(emptyList())
                }
        }
    var id by rememberSaveable { mutableStateOf(seedId) }
    val linked = remember(catalogue, id) { catalogue.firstOrNull { it.id == id } }
    val reference = remember(linked) { linked?.approvedPolygons().orEmpty() }
    var geometry by rememberSaveable { mutableStateOf(initialGeometry) }
    var text by rememberSaveable { mutableStateOf("") }
    var pinText by rememberSaveable { mutableStateOf("") }
    var full by rememberSaveable { mutableStateOf(initialGeometry != null) }
    var publicName by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable {
        mutableStateOf(if (initialKind == "missing") "missing" else "other")
    }
    var chooser by rememberSaveable { mutableStateOf(false) }
    var asCorrection by rememberSaveable { mutableStateOf(initialKind == "boundary") }
    var acceptedNew by rememberSaveable { mutableStateOf(false) }
    val fixed = remember(geometry) { shapes(geometry?.let(::JSONObject)) }
    val points =
        remember(text) {
            text
                .lineSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val xy = line.split(',')
                    if (xy.size != 2) null else GeoPoint(xy[0].toDouble(), xy[1].toDouble())
                }
                .toList()
        }
    val pin = remember(pinText) { pinText.takeIf { it.isNotEmpty() }?.let { geo(JSONArray(it)) } }
    val focus =
        remember(linked, initialGeometry) {
            BoundaryFocus(
                linked?.coordinate?.let { GeoPoint(it.latitude, it.longitude) },
                (if (linked != null) reference else fixed).flatMap { it.exterior },
            )
        }
    val nearby =
        remember(catalogue, pin) {
            if (pin == null || asCorrection) emptyList()
            else
                catalogue
                    .mapNotNull { mosque ->
                        val pos = mosque.coordinate ?: return@mapNotNull null
                        val dx = (pos.longitude - pin.longitude) * cos(Math.toRadians(pin.latitude))
                        val dy = pos.latitude - pin.latitude
                        val distance = dx * dx + dy * dy
                        if (distance < 0.0000032) mosque to distance else null
                    }
                    .sortedBy { it.second }
                    .take(4)
                    .map { it.first }
        }
    if (asCorrection) {
        OutlinedButton(onClick = { chooser = true }) {
            Text(linked?.displayName() ?: stringResource(R.string.mr_pick_mosque))
        }
        if (reference.isNotEmpty()) Text(stringResource(R.string.mr_compare))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            !full,
            { full = false },
            label = {
                Text(stringResource(if (asCorrection) R.string.mr_part else R.string.mr_pin))
            },
        )
        FilterChip(full, { full = true }, label = { Text(stringResource(R.string.mr_outline)) })
    }
    MosqueBoundaryMap(
        points = if (full) points else emptyList(),
        fixedPolygons = if (full) fixed else emptyList(),
        crossing = null,
        pin = if (full) null else pin,
        userLocation = null,
        mode = if (full && fixed.isNotEmpty()) BoundaryEditMode.VIEW else BoundaryEditMode.DRAW,
        focus = focus,
        modifier = Modifier.fillMaxWidth().height(250.dp),
        onStatus = {},
        onChange = { latest ->
            if (full) text = coordinateText(latest)
            else
                latest.lastOrNull()?.let {
                    pinText = pair(it).toString()
                    acceptedNew = false
                }
        },
        reference = reference,
    )
    if (full && (points.isNotEmpty() || fixed.isNotEmpty()))
        TextButton(
            onClick = {
                geometry = null
                text = ""
            }
        ) {
            Text(stringResource(R.string.mr_clear))
        }
    if (!asCorrection && nearby.isNotEmpty()) {
        Text(stringResource(R.string.mr_nearby))
        for (mosque in nearby) TextButton(
            onClick = {
                id = mosque.id
                asCorrection = true
                reason = "other"
            }
        ) {
            Text(mosque.displayName())
        }
        FilterChip(
            acceptedNew,
            { acceptedNew = !acceptedNew },
            label = { Text(stringResource(R.string.mr_keep_missing)) },
        )
    }
    OutlinedTextField(
        publicName,
        { publicName = it.take(100) },
        label = { Text(stringResource(R.string.mr_public_name)) },
        singleLine = true,
    )
    if (asCorrection) {
        Text(stringResource(R.string.mr_reason))
        PickReportOption(reasons, stringArrayResource(R.array.mr_map_reasons).toList(), reason) {
            reason = it
        }
    }
    val proposed =
        remember(full, fixed, points, pin) {
            val poly =
                if (fixed.isNotEmpty()) fixed
                else runCatching { listOf(SilencePolygon(points)) }.getOrDefault(emptyList())
            if (
                full &&
                    poly.isNotEmpty() &&
                    poly.sumOf { p -> (listOf(p.exterior) + p.holes).sumOf { it.size + 1 } } <=
                        1500 &&
                    poly.all { p ->
                        (listOf(p.exterior) + p.holes).flatten().all(::inTunisiaEnvelope)
                    }
            )
                reportGeometry(poly)
            else if (!full && pin != null && inTunisiaEnvelope(pin))
                JSONObject().put("type", "Point").put("coordinates", pair(pin))
            else null
        }
    if (proposed == null && (points.isNotEmpty() || pin != null))
        Text(stringResource(R.string.mr_invalid_shape))
    Button(
        enabled = proposed != null && (nearby.isEmpty() || asCorrection || acceptedNew),
        onClick = {
            review(
                if (asCorrection) "boundary" else "missing",
                JSONObject()
                    .put("geometry", proposed)
                    .put("reason", reason)
                    .put("shape", if (full) "full" else if (asCorrection) "part" else "pin")
                    .apply {
                        if (publicName.isNotBlank()) put("public_name", publicName.trim())
                        if (linked != null) {
                            put("catalogue_id", linked.id)
                            put("base", BuildConfig.MOSQUE_CATALOG_VERSION)
                        }
                    },
            )
        },
    ) {
        Text(stringResource(R.string.mr_review))
    }
    if (chooser)
        CatalogueChooser(pin, { chooser = false }) { mosque, _ ->
            id = mosque.id
            chooser = false
        }
}

@Composable
internal fun CatalogueUpdateAction(area: SavedSilenceArea) {
    val id =
        area.geometry.id.takeIf { it.startsWith("catalogue:") }?.removePrefix("catalogue:")
            ?: return
    if (area.catalogueBase == BuildConfig.MOSQUE_CATALOG_VERSION) return
    val c = LocalContext.current
    val record by
        produceState<Mosque?>(null, id) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching { MosqueRepository.load(c).mosques.firstOrNull { it.id == id } }
                        .getOrNull()
                }
        }
    val candidate = record ?: return
    val outline = remember(candidate) { candidate.approvedPolygons() }
    if (outline.isEmpty() || outline == area.geometry.polygons) return
    var show by rememberSaveable { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    TextButton(onClick = { show = true }) { Text(stringResource(R.string.mr_apply_update)) }
    if (show)
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text(stringResource(R.string.mr_apply_update)) },
            text = {
                Column {
                    Text(stringResource(R.string.mr_compare))
                    val focus =
                        remember(area, outline) {
                            BoundaryFocus(
                                outline =
                                    outline.flatMap { it.exterior } +
                                        area.geometry.polygons.flatMap { it.exterior }
                            )
                        }
                    MosqueBoundaryMap(
                        emptyList(),
                        outline,
                        null,
                        null,
                        null,
                        BoundaryEditMode.VIEW,
                        focus,
                        Modifier.fillMaxWidth().height(210.dp),
                        {},
                        {},
                        area.geometry.polygons,
                    )
                    if (failed)
                        Text(
                            stringResource(R.string.mr_error),
                            color = MaterialTheme.colorScheme.error,
                        )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runCatching {
                                PrivateSilenceAreaStore.applyCatalogueUpdate(c, area, candidate)
                            }
                            .onSuccess { show = false }
                            .onFailure { failed = true }
                    }
                ) {
                    Text(stringResource(R.string.mr_use_update))
                }
            },
            dismissButton = {
                TextButton(onClick = { show = false }) {
                    Text(stringResource(R.string.mosque_cancel))
                }
            },
        )
}

private fun inTunisiaEnvelope(p: GeoPoint) = p.longitude in 7.0..12.0 && p.latitude in 30.0..38.0

@Composable
internal fun ReportPreview(data: JSONObject) {
    val f = data.getJSONObject("fields")
    if (data.getString("kind") == "feedback") {
        val question = f.optString("question")
        val labels =
            stringArrayResource(
                if (question == "window_v1") R.array.mr_survey_answers else R.array.mr_answers
            )
        val codes = if (question == "window_v1") surveyAnswers else outcomes
        Text(
            labels.getOrNull(codes.indexOf(f.optString("answer")))
                ?: stringResource(R.string.mr_field)
        )
        if (f.has("detail")) {
            val detail = f.getString("detail")
            val display =
                when {
                    detail in places ->
                        stringArrayResource(R.array.mr_places)[places.indexOf(detail)]
                    detail in durations ->
                        stringArrayResource(R.array.mr_durations)[durations.indexOf(detail)]
                    detail == "yes" -> "نعم"
                    else -> "لا"
                }
            Text(display)
        }
        prayerLabels[f.optString("prayer")]?.let { Text(it) }
        f.optJSONObject("technical")?.let { technical ->
            Text(stringResource(R.string.mr_technical))
            if (technical.has("started")) Text("بدأ رصد الموقع")
            if (technical.has("activation_ok")) Text("قَبِل أندرويد طلب الكتم")
            if (technical.has("activation_failed")) Text("تعذّر تفعيل الكتم")
            if (technical.has("release_overdue")) Text("تأخّرت استعادة الصوت")
            if (technical.has("field_entry")) Text("سُجّل الدخول")
            if (technical.has("field_exit")) Text("سُجّل الخروج")
            if (technical.has("field_pass")) Text("سُجّل المرور بجانب المسجد")
            var expanded by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(R.string.mr_values))
            }
            if (expanded)
                for (k in technical.keys().asSequence().sorted()) Text(
                    k + " · " + technical.getInt(k),
                    style = MaterialTheme.typography.bodySmall,
                )
        }
    } else {
        val c = LocalContext.current
        val g = f.getJSONObject("geometry")
        val poly = remember(g.toString()) { shapes(g) }
        val pin =
            remember(g.toString()) {
                if (g.getString("type") == "Point") geo(g.getJSONArray("coordinates")) else null
            }
        val publicId = f.optString("catalogue_id")
        val reference by
            produceState<List<SilencePolygon>>(emptyList(), publicId) {
                if (publicId.isNotEmpty())
                    value =
                        withContext(Dispatchers.IO) {
                            MosqueRepository.load(c)
                                .mosques
                                .firstOrNull { it.id == publicId }
                                ?.approvedPolygons()
                                .orEmpty()
                        }
            }
        val focus = remember(g.toString()) { BoundaryFocus(pin, poly.flatMap { it.exterior }) }
        MosqueBoundaryMap(
            emptyList(),
            poly,
            null,
            pin,
            null,
            BoundaryEditMode.VIEW,
            focus,
            Modifier.fillMaxWidth().height(230.dp),
            {},
            {},
            reference,
        )
        if (pin != null) Text(pair(pin).toString(), style = MaterialTheme.typography.bodySmall)
        if (f.has("public_name")) Text(f.getString("public_name"))
        if (publicId.isNotEmpty())
            Text(publicId + " · " + f.getString("base"), style = MaterialTheme.typography.bodySmall)
        val reason = f.optString("reason")
        Text(
            if (reason == "missing") stringResource(R.string.mr_missing)
            else stringArrayResource(R.array.mr_map_reasons)[reasons.indexOf(reason)]
        )
        Text(stringResource(R.string.mr_public))
    }
}
