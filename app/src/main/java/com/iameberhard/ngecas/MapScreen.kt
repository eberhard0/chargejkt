package com.iameberhard.ngecas

import android.content.Context
import android.graphics.PointF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** The map with every charger as a dot: amber = DC fast, blue = AC. Tap a dot to open it. */
@Composable
fun StationMapView(
    map: StationMap,
    stations: List<Station>,
    here: Pair<Double, Double>?,
    selectedId: String?,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        lifecycle.addObserver(map.lifecycleObserver)
        onDispose {
            lifecycle.removeObserver(map.lifecycleObserver)
            map.destroy()
        }
    }
    LaunchedEffect(stations) { map.showStations(stations) }
    LaunchedEffect(here) { map.showPosition(here) }
    LaunchedEffect(selectedId) { map.showSelected(stations.firstOrNull { it.id == selectedId }) }
    AndroidView(factory = { map.view }, modifier = modifier)
}

class StationMap(context: Context, private val onTap: (String) -> Unit) {

    val view: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var stations: List<Station> = emptyList()
    private var position: Pair<Double, Double>? = null
    private var selected: Station? = null
    private var pendingCamera: (() -> Unit)? = null
    private var framedOnce = false

    private var started = false
    private var resumed = false
    private var destroyed = false

    val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> if (!started && !destroyed) { started = true; view.onStart() }
            Lifecycle.Event.ON_RESUME -> if (!resumed && !destroyed) { resumed = true; view.onResume() }
            Lifecycle.Event.ON_PAUSE -> if (resumed) { resumed = false; view.onPause() }
            Lifecycle.Event.ON_STOP -> if (started) { started = false; view.onStop() }
            else -> Unit
        }
    }

    init {
        MapLibre.getInstance(context)
        view = MapView(context)
        view.onCreate(null)
        view.getMapAsync { ready ->
            map = ready
            ready.uiSettings.apply {
                setLogoEnabled(false)
                setAttributionEnabled(false) // credited in Settings instead
                setCompassEnabled(false)
                setRotateGesturesEnabled(false)
                setTiltGesturesEnabled(false)
            }
            val json = context.assets.open("map_dark.json").bufferedReader().use { it.readText() }
            ready.setStyle(Style.Builder().fromJson(json)) { loaded ->
                loaded.addSource(GeoJsonSource(STATIONS))
                loaded.addLayer(
                    CircleLayer(AC, STATIONS).withFilter(eq(get("kind"), literal("ac"))).withProperties(
                        circleRadius(7f), circleColor(AcColor.toArgb()),
                        circleStrokeWidth(2f), circleStrokeColor(android.graphics.Color.BLACK),
                    ),
                )
                loaded.addLayer(
                    CircleLayer(DC, STATIONS).withFilter(eq(get("kind"), literal("dc"))).withProperties(
                        circleRadius(8f), circleColor(FastColor.toArgb()),
                        circleStrokeWidth(2f), circleStrokeColor(android.graphics.Color.BLACK),
                    ),
                )
                loaded.addSource(GeoJsonSource(SELECTED))
                loaded.addLayer(
                    CircleLayer(SELECTED, SELECTED).withProperties(
                        circleRadius(14f), circleColor(android.graphics.Color.TRANSPARENT), circleOpacity(0f),
                        circleStrokeWidth(3f), circleStrokeColor(android.graphics.Color.WHITE),
                    ),
                )
                loaded.addSource(GeoJsonSource(ME))
                loaded.addLayer(
                    CircleLayer(ME, ME).withProperties(
                        circleRadius(8f), circleColor(android.graphics.Color.WHITE),
                        circleStrokeWidth(3.5f), circleStrokeColor(Accent.toArgb()),
                    ),
                )
                style = loaded
                draw()
                pendingCamera?.invoke()
                pendingCamera = null
            }
            ready.addOnMapClickListener { latLng ->
                val point: PointF = ready.projection.toScreenLocation(latLng)
                val hit = ready.queryRenderedFeatures(point, DC, AC).firstOrNull()
                    ?: ready.queryRenderedFeatures(android.graphics.RectF(point.x - 24, point.y - 24, point.x + 24, point.y + 24), DC, AC).firstOrNull()
                val id = hit?.getStringProperty("id")
                if (id != null) onTap(id)
                id != null
            }
        }
    }

    fun showStations(list: List<Station>) {
        stations = list
        draw()
        if (!framedOnce && list.isNotEmpty()) {
            framedOnce = true
            if (position == null) frameJakarta()
        }
    }

    fun showPosition(here: Pair<Double, Double>?) {
        val first = position == null && here != null
        position = here
        draw()
        if (first) centreOn(here)
    }

    fun showSelected(s: Station?) {
        selected = s
        draw()
        if (s != null) whenReady { m ->
            val zoom = maxOf(m.cameraPosition.zoom, 13.5)
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(s.lat, s.lon), zoom), 500)
        }
    }

    fun centreOn(here: Pair<Double, Double>?) {
        if (here == null) return
        whenReady { m ->
            val zoom = maxOf(m.cameraPosition.zoom, 13.0)
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(here.first, here.second), zoom), 600)
        }
    }

    fun frameJakarta() {
        whenReady { m ->
            val bounds = LatLngBounds.Builder().include(LatLng(-6.42, 106.64)).include(LatLng(-6.08, 107.02)).build()
            m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 40))
        }
    }

    fun destroy() {
        if (resumed) { resumed = false; view.onPause() }
        if (started) { started = false; view.onStop() }
        if (!destroyed) {
            destroyed = true
            style = null
            map = null
            view.onDestroy()
        }
    }

    private fun whenReady(action: (MapLibreMap) -> Unit) {
        val ready = map
        if (ready != null && style != null) action(ready) else pendingCamera = { map?.let(action) }
    }

    private fun draw() {
        val loaded = style ?: return
        loaded.getSourceAs<GeoJsonSource>(STATIONS)?.setGeoJson(
            FeatureCollection.fromFeatures(
                stations.map { s ->
                    Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)).apply {
                        addStringProperty("id", s.id)
                        addStringProperty("kind", if (s.fast) "dc" else "ac")
                    }
                },
            ),
        )
        val sel = selected
        loaded.getSourceAs<GeoJsonSource>(SELECTED)?.setGeoJson(
            if (sel == null) NOTHING else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(sel.lon, sel.lat))),
        )
        val here = position
        loaded.getSourceAs<GeoJsonSource>(ME)?.setGeoJson(
            if (here == null) NOTHING else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(here.second, here.first))),
        )
    }

    private companion object {
        const val STATIONS = "stations"
        const val AC = "stations-ac"
        const val DC = "stations-dc"
        const val SELECTED = "stations-selected"
        const val ME = "me"
        val NOTHING: FeatureCollection = FeatureCollection.fromFeatures(emptyList<Feature>())
    }
}
