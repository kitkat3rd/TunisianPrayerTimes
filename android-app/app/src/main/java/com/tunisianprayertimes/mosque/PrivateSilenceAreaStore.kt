package com.tunisianprayertimes.mosque

import android.content.Context
import android.util.AtomicFile
import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.SilenceArea
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SavedSilenceArea(val name: String, val geometry: SilenceArea)
data class SilenceAreaRead(val areas: List<SavedSilenceArea>, val available: Boolean, val rejected: Int = 0)

/** Private areas and unfinished drafts never enter cloud backup or device transfer. No raw GPS fixes. */
object PrivateSilenceAreaStore {
    private const val MAX_AREAS = 80
    private const val MAX_FILE_BYTES = 2_000_000
    internal const val MAX_VERTICES = 256
    internal const val MAX_RINGS_OR_PARTS = 16
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "mosque-silence-areas.json"))
    private fun draft(context: Context) = AtomicFile(File(context.noBackupFilesDir, "mosque-silence-draft.json"))

    fun read(context: Context): SilenceAreaRead = synchronized(SilenceCoordinator.serial) {
        val source = file(context)
        if (!source.baseFile.exists()) return@synchronized SilenceAreaRead(emptyList(), true)
        runCatching {
            require(source.baseFile.length() <= MAX_FILE_BYTES)
            val root = JSONObject(source.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            require(root.getInt("version") == 1)
            val array = root.getJSONArray("areas")
            require(array.length() <= MAX_AREAS)
            var rejected = 0
            val ids = hashSetOf<String>()
            val areas = (0 until array.length()).mapNotNull { i ->
                runCatching {
                    val item = array.getJSONObject(i)
                    val id = item.getString("id")
                    require(id.length in 1..100 && ids.add(id))
                    val shapes = parsePolygons(item.getJSONArray("polygons"))
                    val name = item.getString("name").trim()
                    require(name.isNotEmpty() && name.length <= 100)
                    SavedSilenceArea(name, SilenceArea(id, item.getLong("revision"), shapes, item.optBoolean("enabled", false),
                        item.optBoolean("requireStrongerEvidence", false)))
                }.getOrElse { rejected++; null }
            }
            SilenceAreaRead(areas, true, rejected)
        }.getOrElse { SilenceAreaRead(emptyList(), false) }
    }

    internal fun save(context: Context, draft: AreaDraft, polygons: List<SilencePolygon>) {
        require(draft.name.trim().length in 1..100)
        require(draft.mode != BoundaryEditMode.DRAW)
        require(polygons.size in 1..MAX_RINGS_OR_PARTS && polygons.all { polygon ->
            polygon.exterior.size in 3..MAX_VERTICES && polygon.holes.size < MAX_RINGS_OR_PARTS &&
                polygon.holes.all { it.size in 3..MAX_VERTICES }
        })
        val id = draft.id ?: UUID.randomUUID().toString()
        require(id.length in 1..100)
        synchronized(SilenceCoordinator.serial) {
            val currentDraft = readDraft(context)
            check(currentDraft == null || currentDraft.key == draft.key) { "DRAFT_ALREADY_EXISTS" }
            val current = read(context)
            check(current.available) { "STORE_UNAVAILABLE" }
            val previous = current.areas.firstOrNull { it.geometry.id == id }
            val previousPolygons = previous?.geometry?.polygons
            if (previousPolygons != null && (previousPolygons.size > 1 || previousPolygons.any { it.holes.isNotEmpty() })) {
                require(previousPolygons == polygons)
            }
            require(previous != null || current.areas.size < MAX_AREAS)
            val area = SavedSilenceArea(draft.name.trim(), SilenceArea(id,
                (previous?.geometry?.revision ?: 0) + 1, polygons, draft.enabled, previous?.geometry?.requireStrongerEvidence ?: false))
            write(context, current.areas.filterNot { it.geometry.id == area.geometry.id } + area)
            clearDraft(context, draft.key)
        }
        MosqueSilenceMonitor.environmentChanged(context)
    }

    fun setStricter(context: Context, id: String, stricter: Boolean) {
        mutate(context) { all -> all.map { if (it.geometry.id == id) it.copy(geometry = it.geometry.copy(
            requireStrongerEvidence = stricter, revision = it.geometry.revision + 1)) else it } }
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        mutate(context) { all -> all.map { if (it.geometry.id == id) it.copy(geometry = it.geometry.copy(enabled = enabled)) else it } }
    }
    fun delete(context: Context, id: String) = mutate(context) { all -> all.filterNot { it.geometry.id == id } }
    private fun mutate(context: Context, transform: (List<SavedSilenceArea>) -> List<SavedSilenceArea>) {
        synchronized(SilenceCoordinator.serial) {
            val current = read(context); check(current.available)
            write(context, transform(current.areas))
        }
        MosqueSilenceMonitor.environmentChanged(context)
    }
    private fun write(context: Context, areas: List<SavedSilenceArea>) {
        val json = JSONObject().put("version", 1).put("areas", JSONArray().apply {
            areas.forEach { saved -> put(JSONObject().put("id", saved.geometry.id).put("name", saved.name)
                .put("revision", saved.geometry.revision).put("enabled", saved.geometry.enabled)
                .put("requireStrongerEvidence", saved.geometry.requireStrongerEvidence)
                .put("polygons", polygonsJson(saved.geometry.polygons))) }
        })
        atomicWrite(file(context), json.toString())
    }

    internal fun saveDraft(context: Context, value: AreaDraft) {
        require(value.coordinates.length <= 32_000)
        synchronized(SilenceCoordinator.serial) {
            val current = readDraft(context)
            check(current == null || current.key == value.key) { "DRAFT_ALREADY_EXISTS" }
            val json = JSONObject().put("name", value.name.take(100)).put("coordinates", value.coordinates)
                .put("id", value.id ?: JSONObject.NULL).put("enabled", value.enabled)
                .put("key", value.key).put("mode", value.mode.name)
            if (value.fixedPolygons.isNotEmpty()) json.put("polygons", polygonsJson(value.fixedPolygons))
            value.cataloguePin?.let { json.put("pin", JSONArray().put(it.longitude).put(it.latitude)) }
            atomicWrite(draft(context), json.toString())
        }
    }
    internal fun readDraft(context: Context): AreaDraft? = synchronized(SilenceCoordinator.serial) {
        val source = draft(context)
        if (!source.baseFile.exists()) return@synchronized null
        runCatching {
            require(source.baseFile.length() <= MAX_FILE_BYTES)
            val json = JSONObject(source.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            val id = json.optString("id").takeUnless { it == "null" || it.isBlank() }
            val enabled = if (json.has("enabled")) json.optBoolean("enabled") else {
                read(context).areas.firstOrNull { it.geometry.id == id }?.geometry?.enabled ?: true
            }
            AreaDraft(
                id, json.getString("name"), json.getString("coordinates"), enabled,
                key = json.optString("key", "legacy-draft"),
                mode = BoundaryEditMode.entries.firstOrNull { it.name == json.optString("mode") } ?: BoundaryEditMode.VIEW,
                fixedPolygons = json.optJSONArray("polygons")?.let(::parsePolygons).orEmpty(),
                cataloguePin = json.optJSONArray("pin")?.let { GeoPoint(it.getDouble(1), it.getDouble(0)) },
            )
        }.getOrNull()
    }
    internal fun clearDraft(context: Context, key: String): Boolean = synchronized(SilenceCoordinator.serial) {
        val current = readDraft(context) ?: return@synchronized true
        if (current.key != key) return@synchronized false
        val source = draft(context)
        source.delete()
        !source.baseFile.exists()
    }

    /** Deliberate export only; names and exact private boundaries are visible in the preview first. */
    fun exportGeoJson(context: Context): String {
        val read = read(context); check(read.available)
        return JSONObject().put("type", "FeatureCollection").put("features", JSONArray().apply {
            read.areas.forEach { put(geoJsonFeature(it)) }
        }).toString(2)
    }
    internal fun geoJsonFeature(saved: SavedSilenceArea): JSONObject {
        val coordinates = JSONArray()
        saved.geometry.polygons.forEach { polygon ->
            val rings = JSONArray()
            (listOf(polygon.exterior) + polygon.holes).forEach { points ->
                val closed = if (points.first() == points.last()) points else points + points.first()
                rings.put(ringJson(closed))
            }
            coordinates.put(rings)
        }
        return JSONObject().put("type", "Feature").put("id", saved.geometry.id)
            .put("properties", JSONObject().put("name", saved.name).put("enabled", saved.geometry.enabled))
            .put("geometry", JSONObject().put("type", "MultiPolygon").put("coordinates", coordinates))
    }
    private fun ringJson(points: List<GeoPoint>) = JSONArray().apply {
        points.forEach { put(JSONArray().put(it.longitude).put(it.latitude)) }
    }
    private fun polygonsJson(polygons: List<SilencePolygon>) = JSONArray().apply {
        polygons.forEach { polygon ->
            put(JSONArray().apply { (listOf(polygon.exterior) + polygon.holes).forEach { put(ringJson(it)) } })
        }
    }
    private fun parsePolygons(polygons: JSONArray): List<SilencePolygon> {
        require(polygons.length() in 1..MAX_RINGS_OR_PARTS)
        return List(polygons.length()) { part ->
            val rings = polygons.getJSONArray(part)
            require(rings.length() in 1..MAX_RINGS_OR_PARTS)
            val parsed = List(rings.length()) { parseRing(rings.getJSONArray(it)) }
            SilencePolygon(parsed.first(), parsed.drop(1))
        }
    }
    private fun parseRing(array: JSONArray): List<GeoPoint> {
        require(array.length() in 3..MAX_VERTICES)
        return (0 until array.length()).map { i -> array.getJSONArray(i).let { GeoPoint(it.getDouble(1), it.getDouble(0)) } }
    }
    private fun atomicWrite(file: AtomicFile, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Throwable) { file.failWrite(stream); throw error }
    }
}
