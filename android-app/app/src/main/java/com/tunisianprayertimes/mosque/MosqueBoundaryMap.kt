package com.tunisianprayertimes.mosque

import android.content.Context
import android.graphics.PointF
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tunisianprayertimes.R
import com.tunisianprayertimes.mosque.presence.GeoPoint
import com.tunisianprayertimes.mosque.presence.SilencePolygon
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Property.ICON_ANCHOR_BOTTOM
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.hypot

/** Each instance requests a camera move, including repeat taps at the same location. */
internal class BoundaryFocus(
    val point: GeoPoint? = null,
    val outline: List<GeoPoint> = emptyList(),
)

@Composable
internal fun MosqueBoundaryMap(
    points: List<GeoPoint>,
    fixedPolygons: List<SilencePolygon>,
    crossing: Pair<Int, Int>?,
    pin: GeoPoint?,
    userLocation: GeoPoint?,
    mode: BoundaryEditMode,
    focus: BoundaryFocus,
    modifier: Modifier = Modifier,
    onStatus: (Boolean) -> Unit,
    onChange: (List<GeoPoint>) -> Unit,
    reference: List<SilencePolygon> = emptyList(),
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = remember(context, lifecycle) { BoundaryMapView(context) }
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStop()
            view.onDestroy()
        }
    }
    AndroidView(factory = { view }, modifier = modifier) {
        it.bind(points, fixedPolygons, crossing, pin, userLocation, mode, focus, onStatus, onChange, reference)
    }
}

private class BoundaryMapView(context: Context) : MapView(context, mapOptions(context)) {
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var map: MapLibreMap? = null
    private var source: GeoJsonSource? = null
    private var points = emptyList<GeoPoint>()
    private var fixedPolygons = emptyList<SilencePolygon>()
    private var reference = emptyList<SilencePolygon>()
    private var crossing: Pair<Int, Int>? = null
    private var pin: GeoPoint? = null
    private var userLocation: GeoPoint? = null
    private var mode = BoundaryEditMode.VIEW
    private var focus: BoundaryFocus? = null
    private var pendingFocus: BoundaryFocus? = null
    private var cornerDrag: CornerDrag? = null
    private var consumingDrag = false
    private var dragPreview: List<GeoPoint>? = null
    private var succeeded: Boolean? = null
    private var onStatus: (Boolean) -> Unit = {}
    private var onChange: (List<GeoPoint>) -> Unit = {}

    init {
        onCreate(null)
        addOnDidFailLoadingMapListener {
            succeeded = false
            onStatus(false)
        }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyFocus() }
        getMapAsync { ready ->
            map = ready
            ready.uiSettings.isRotateGesturesEnabled = false
            ready.uiSettings.isTiltGesturesEnabled = false
            ready.uiSettings.isDoubleTapGesturesEnabled = mode == BoundaryEditMode.VIEW
            ready.uiSettings.isQuickZoomGesturesEnabled = mode == BoundaryEditMode.VIEW
            ready.setMaxZoomPreference(22.0)
            ready.addOnMapClickListener(::addCorner)
            setOnTouchListener { _, event -> dragCorner(event) }
            ready.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                val boundary = GeoJsonSource("mosque-boundary")
                style.addSource(boundary)
                style.addLayer(FillLayer("mosque-fill", boundary.id).withProperties(
                    fillColor("#15865B"), fillOpacity(0.24f),
                ))
                style.addLayer(LineLayer("mosque-edge", boundary.id)
                    .withFilter(Expression.eq(Expression.get("kind"), Expression.literal("edge")))
                    .withProperties(lineColor("#086644"), lineWidth(3f)))
                style.addLayer(LineLayer("catalogue-reference", boundary.id)
                    .withFilter(Expression.eq(Expression.get("kind"), Expression.literal("reference")))
                    .withProperties(lineColor("#97582e"), lineWidth(2f), lineDasharray(arrayOf(3f, 2f))))
                style.addLayer(LineLayer("mosque-crossing", boundary.id)
                    .withFilter(Expression.eq(Expression.get("kind"), Expression.literal("crossing")))
                    .withProperties(lineColor("#B3261E"), lineWidth(4f)))
                style.addLayer(CircleLayer("mosque-pin", boundary.id)
                    .withFilter(Expression.eq(Expression.get("kind"), Expression.literal("pin")))
                    .withProperties(circleColor("#086644"), circleRadius(7f),
                        circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
                style.addLayer(CircleLayer("mosque-corners", boundary.id)
                    .withFilter(Expression.eq(Expression.get("kind"), Expression.literal("corner")))
                    .withProperties(circleColor("#FFFFFF"), circleRadius(5.5f),
                        circleStrokeWidth(2f), circleStrokeColor("#086644")))
                style.addImage("user-location-pin", requireNotNull(context.getDrawable(R.drawable.mosque_user_location_pin)))
                style.addLayer(SymbolLayer("mosque-user-location", boundary.id)
                    .withFilter(Expression.eq(Expression.get("kind"), Expression.literal("user-location")))
                    .withProperties(iconImage("user-location-pin"), iconAnchor(ICON_ANCHOR_BOTTOM),
                        iconAllowOverlap(true), iconIgnorePlacement(true)))
                source = boundary
                succeeded = true
                onStatus(true)
                render()
                applyFocus()
            }
        }
    }

    fun bind(
        points: List<GeoPoint>,
        fixedPolygons: List<SilencePolygon>,
        crossing: Pair<Int, Int>?,
        pin: GeoPoint?,
        userLocation: GeoPoint?,
        mode: BoundaryEditMode,
        focus: BoundaryFocus,
        onStatus: (Boolean) -> Unit,
        onChange: (List<GeoPoint>) -> Unit,
        reference: List<SilencePolygon>,
    ) {
        this.onStatus = onStatus
        this.onChange = onChange
        succeeded?.let(onStatus)
        if (this.reference != reference || this.points != points || this.fixedPolygons != fixedPolygons || this.crossing != crossing ||
            this.pin != pin || this.userLocation != userLocation || this.mode != mode
        ) {
            val ready = map
            if (this.mode != mode && ready != null) {
                ready.uiSettings.isDoubleTapGesturesEnabled = mode == BoundaryEditMode.VIEW
                ready.uiSettings.isQuickZoomGesturesEnabled = mode == BoundaryEditMode.VIEW
            }
            if (mode == BoundaryEditMode.DRAW && this.mode == BoundaryEditMode.VIEW &&
                ready != null && ready.cameraPosition.zoom < 17.5
            ) {
                ready.animateCamera(CameraUpdateFactory.zoomTo(17.5))
            }
            this.reference = reference
            this.points = points
            this.fixedPolygons = fixedPolygons
            this.crossing = crossing
            this.pin = pin
            this.userLocation = userLocation
            this.mode = mode
            render()
        }
        if (this.focus != focus) {
            this.focus = focus
            pendingFocus = focus
            applyFocus()
        }
    }

    private fun applyFocus() {
        val ready = map ?: return
        val target = pendingFocus ?: return
        if (width <= 0 || height <= 0) return
        pendingFocus = null
        val outline = target.outline.distinct()
        if (outline.size >= 2) {
            val bounds = LatLngBounds.Builder().includes(outline.map { it.latLng() }).build()
            ready.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, (48 * density).toInt()))
        } else {
            val point = outline.firstOrNull() ?: target.point ?: return
            ready.moveCamera(CameraUpdateFactory.newLatLngZoom(point.latLng(), 18.0))
        }
    }

    private fun render() {
        val boundary = source ?: return
        val outline = (dragPreview ?: points).map { it.mapPoint() }
        val closed = mode != BoundaryEditMode.DRAW && outline.size >= 3
        val edge = if (closed) outline + outline.first() else outline
        val features = buildList {
            reference.forEach { polygon ->
                for (ring in listOf(polygon.exterior) + polygon.holes) {
                    val open = ring.openOutline().map { it.mapPoint() }
                    add(lineFeature(open + open.first(), "reference"))
                }
            }
            fixedPolygons.forEach { polygon ->
                val rings = (listOf(polygon.exterior) + polygon.holes).map { ring ->
                    val open = ring.openOutline().map { it.mapPoint() }
                    open + open.first()
                }
                add(Feature.fromGeometry(Polygon.fromLngLats(rings)))
                rings.forEach { add(lineFeature(it, "edge")) }
            }
            if (closed && crossing == null) add(Feature.fromGeometry(Polygon.fromLngLats(listOf(edge))))
            if (edge.size >= 2) add(lineFeature(edge, "edge"))
            if (outline.size >= 3 && dragPreview == null) {
                crossing?.let { (a, b) ->
                    for (index in listOf(a, b)) {
                        if (index !in outline.indices) continue
                        add(lineFeature(listOf(outline[index], outline[(index + 1) % outline.size]), "crossing"))
                    }
                }
            }
            if (mode != BoundaryEditMode.VIEW) {
                outline.forEach { add(Feature.fromGeometry(it).apply { addStringProperty("kind", "corner") }) }
            }
            userLocation?.let { point ->
                add(Feature.fromGeometry(point.mapPoint()).apply { addStringProperty("kind", "user-location") })
            }
            if (outline.isEmpty() && fixedPolygons.isEmpty()) {
                pin?.let {
                    add(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)).apply {
                        addStringProperty("kind", "pin")
                    })
                }
            }
        }
        boundary.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun addCorner(latLng: LatLng): Boolean {
        if (source == null || mode == BoundaryEditMode.VIEW || points.size >= PrivateSilenceAreaStore.MAX_VERTICES) return false
        val point = GeoPoint(latLng.latitude, latLng.longitude)
        if (!point.isValid()) return false
        if (mode == BoundaryEditMode.DRAW || points.size < 3) {
            publishPoints(points + point)
            return true
        }
        val projection = map?.projection ?: return false
        val tap = projection.toScreenLocation(latLng)
        val screen = points.map { projection.toScreenLocation(it.latLng()) }
        val edges = screen.indices.map { index -> projectOntoEdge(tap, screen[index], screen[(index + 1) % screen.size]) }
        val edge = edges.indices.minBy { edges[it].distance }
        val match = edges[edge]
        if (match.distance > 22 * density) return false
        val updated = insertBoundaryVertex(points, edge, match.fraction.toDouble()) ?: return false
        publishPoints(updated)
        return true
    }

    private fun publishPoints(updated: List<GeoPoint>) {
        points = updated
        crossing = null
        onChange(updated)
        render()
    }

    private fun dragCorner(event: MotionEvent): Boolean {
        val projection = map?.projection ?: return false
        val touch = PointF(event.x, event.y)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            consumingDrag = false
            cornerDrag = null
            if (source == null || mode != BoundaryEditMode.ADJUST) return false
            val corners = points.map { projection.toScreenLocation(it.latLng()) }
            val index = corners.indices.minByOrNull { distance(corners[it], touch) } ?: return false
            val corner = corners[index]
            if (distance(corner, touch) > 18 * density) return false
            cornerDrag = CornerDrag(index, touch, corner)
            consumingDrag = true
            return true
        }
        if (!consumingDrag) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val drag = cornerDrag
                if (drag != null && (dragPreview != null || distance(touch, drag.start) > touchSlop)) {
                    val position = projection.fromScreenLocation(drag.at(touch))
                    val point = GeoPoint(position.latitude, position.longitude)
                    if (point.isValid() && drag.index in points.indices) {
                        dragPreview = points.toMutableList().apply { set(drag.index, point) }
                        render()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cornerDrag = null
                dragPreview = null
                render()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val completed = dragPreview
                dragPreview = null
                cornerDrag = null
                consumingDrag = false
                if (event.actionMasked == MotionEvent.ACTION_UP && completed != null) {
                    publishPoints(completed)
                } else {
                    render()
                }
            }
        }
        return true
    }
}

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
private val tunis = LatLng(36.8002, 10.1767)
private val mapHttpClient by lazy {
    OkHttpClient.Builder().addNetworkInterceptor { chain ->
        val request = chain.request().newBuilder().header("User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36")
            .build()
        chain.proceed(request)
    }.build()
}

private fun mapOptions(context: Context): MapLibreMapOptions {
    MapLibre.getInstance(context.applicationContext)
    HttpRequestUtil.setOkHttpClient(mapHttpClient)
    return MapLibreMapOptions.createFromAttributes(context)
        .textureMode(true).logoEnabled(false).attributionEnabled(false)
        .camera(CameraPosition.Builder().target(tunis).zoom(13.0).build())
}

private fun GeoPoint.latLng() = LatLng(latitude, longitude)
private fun GeoPoint.mapPoint() = Point.fromLngLat(longitude, latitude)

private fun lineFeature(points: List<Point>, kind: String) =
    Feature.fromGeometry(LineString.fromLngLats(points)).apply { addStringProperty("kind", kind) }

private fun distance(a: PointF, b: PointF) = hypot(a.x - b.x, a.y - b.y)

private data class CornerDrag(val index: Int, val start: PointF, val corner: PointF) {
    fun at(touch: PointF) = PointF(corner.x + touch.x - start.x, corner.y + touch.y - start.y)
}

private data class EdgeProjection(val fraction: Float, val distance: Float)

private fun projectOntoEdge(point: PointF, a: PointF, b: PointF): EdgeProjection {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) return EdgeProjection(0f, distance(point, a))
    val fraction = (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    return EdgeProjection(fraction, distance(point, PointF(a.x + fraction * dx, a.y + fraction * dy)))
}
