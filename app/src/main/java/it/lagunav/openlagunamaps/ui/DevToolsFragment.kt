package it.lagunav.openlagunamaps.ui

import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import it.lagunav.openlagunamaps.databinding.FragmentDevtoolsBinding
import it.lagunav.openlagunamaps.engine.BathymetryEngine
import it.lagunav.openlagunamaps.engine.RoutingEngine
import it.lagunav.openlagunamaps.engine.SimulatedPositionProvider
import it.lagunav.openlagunamaps.engine.SimulatorHub
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.annotations.Polyline
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.roundToInt

class DevToolsFragment : Fragment() {

    private var _binding: FragmentDevtoolsBinding? = null
    private val binding get() = _binding!!

    private lateinit var bathyEngine: BathymetryEngine
    private lateinit var routingEngine: RoutingEngine
    private var mapLibre: MapLibreMap? = null

    private var startMarker: Marker? = null
    private var endMarker: Marker? = null
    private var routeLine: Polyline? = null
    private val candidateLines = mutableListOf<Polyline>()
    private var tipsTestMarker: Marker? = null

    private val TIP_COLORS = listOf("#E6194B", "#F58231", "#FFE119", "#3CB44B", "#4363D8", "#911EB4")

    // Simulatore
    private var simProvider: SimulatedPositionProvider? = null
    private val SOURCE_SIM_BOAT  = "sim-boat-source"
    private val LAYER_SIM_BOAT   = "sim-boat-layer"
    private val SOURCE_SIM_ROUTE = "sim-route-source"
    private val SOURCE_SIM_DONE  = "sim-route-done-source"
    private val LAYER_SIM_ROUTE  = "sim-route-layer"
    private val LAYER_SIM_DONE   = "sim-route-done-layer"
    private val SOURCE_SIM_DEST  = "sim-dest-source"
    private val LAYER_SIM_DEST   = "sim-dest-layer"

    // Stato navigazione locale del simulatore
    private var simRoute: List<LatLng>? = null
    private var simDest: LatLng? = null
    private var simWpIdx = 0
    private val WAYPOINT_ADV = 25.0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDevtoolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bathyEngine = BathymetryEngine(requireContext())
        routingEngine = RoutingEngine(requireContext())

        setupSpinner()

        binding.mapViewDev.onCreate(savedInstanceState)
        binding.mapViewDev.getMapAsync { map ->
            mapLibre = map
            // Nascondiamo logo e attribuzione
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isLogoEnabled = false

            val styleUrl = "https://tiles.openfreemap.org/styles/liberty"
            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                setupLagunaLayers(style)

                map.addOnCameraMoveListener {
                    map.cameraPosition.target?.let {
                        updateDevHud(it)
                        if (binding.cbShowZones.isChecked && binding.cbShowZones.visibility == View.VISIBLE) {
                            updateDebugZones(it, style)
                        } else {
                            removeDebugZones(style)
                        }
                    }
                }
            }

            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(45.433, 12.333))
                .zoom(13.0)
                .build()
        }

        binding.cbShowZones.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) mapLibre?.getStyle { removeDebugZones(it) }
        }

        binding.btnSetStart.setOnClickListener {
            mapLibre?.let { map ->
                val center = map.cameraPosition.target
                startMarker?.let { map.removeMarker(it) }
                startMarker = map.addMarker(MarkerOptions().position(center).title("Partenza"))
                calculateRouteIfPossible()
            }
        }

        binding.btnSetEnd.setOnClickListener {
            mapLibre?.let { map ->
                val center = map.cameraPosition.target
                endMarker?.let { map.removeMarker(it) }
                endMarker = map.addMarker(MarkerOptions().position(center).title("Arrivo"))
                calculateRouteIfPossible()
            }
        }

        binding.btnTestTips.setOnClickListener {
            mapLibre?.let { map ->
                val center = map.cameraPosition.target ?: return@let
                candidateLines.forEach { map.removePolyline(it) }
                candidateLines.clear()
                tipsTestMarker?.let { map.removeMarker(it) }
                tipsTestMarker = map.addMarker(MarkerOptions().position(center).title("Test Tips"))

                val results = routingEngine.pathsToTips(center)
                val status = StringBuilder(
                    String.format(Locale.getDefault(), "TEST TIPS da %.6f,%.6f\n", center.latitude, center.longitude)
                )
                results.forEachIndexed { i, r ->
                    val color = TIP_COLORS[i % TIP_COLORS.size]
                    val path = r.path
                    if (path != null) {
                        candidateLines.add(
                            map.addPolyline(PolylineOptions().addAll(path).color(android.graphics.Color.parseColor(color)).width(4f))
                        )
                        val d = routingEngine.calculateTotalDistance(path)
                        val t = routingEngine.calculateEstimatedTimeMinutes(path)
                        status.append(String.format(
                            Locale.getDefault(), "Tip %d (%.4f,%.4f): %.2f km | %d min\n",
                            i + 1, r.tip.latitude, r.tip.longitude, d / 1000.0, t
                        ))
                    } else {
                        status.append(String.format(
                            Locale.getDefault(), "Tip %d (%.4f,%.4f): %s\n",
                            i + 1, r.tip.latitude, r.tip.longitude, r.error
                        ))
                    }
                    Log.d("RoutingDebug", "tip $i ${r.tip} -> ${path?.size ?: "NULL"} punti (${r.error})")
                }
                binding.tvDevStatus.text = status.toString()
            }
        }

        binding.fabClearAll.setOnClickListener {
            mapLibre?.let { map ->
                startMarker?.let { map.removeMarker(it) }
                endMarker?.let { map.removeMarker(it) }
                tipsTestMarker?.let { map.removeMarker(it) }
                routeLine?.let { map.removePolyline(it) }
                candidateLines.forEach { map.removePolyline(it) }
                startMarker = null
                endMarker = null
                tipsTestMarker = null
                routeLine = null
                candidateLines.clear()
                binding.tvDevRouting.text = "Inizio: No | Fine: No"
                binding.tvDevStatus.text = "MODALITÀ DEBUG"
            }
        }
    }

    private fun setupSpinner() {
        val modes = arrayOf("Calcolo Percorso", "Test Punte (Tips)", "Zone Mare/Laguna", "Simulatore Barca")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, modes)
        binding.spinnerDevMode.adapter = adapter

        binding.spinnerDevMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                binding.groupRoute.visibility      = if (position == 0) View.VISIBLE else View.GONE
                binding.btnTestTips.visibility     = if (position == 1) View.VISIBLE else View.GONE
                binding.cbShowZones.visibility     = if (position == 2) View.VISIBLE else View.GONE
                binding.groupSimulator.visibility  = if (position == 3) View.VISIBLE else View.GONE

                mapLibre?.getStyle { style -> removeDebugZones(style) }
                candidateLines.forEach { mapLibre?.removePolyline(it) }
                candidateLines.clear()
                tipsTestMarker?.let { mapLibre?.removeMarker(it) }
                tipsTestMarker = null

                if (position == 3) startSimulator() else stopSimulator()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateDevHud(target: LatLng) {
        val isAtSea = routingEngine.isAtSea(target)
        val isNoGo = routingEngine.isPointInNoGo(target)
        val fixedDepth = routingEngine.getFixedDepthAt(target)

        val depthText: String
        when {
            isNoGo -> depthText = if (isAtSea) "⚠️ ATTENZIONE: Possibile Basso Fondale" else "0.0 m (Terraferma)"
            fixedDepth != null -> depthText = String.format(Locale.getDefault(), "Profondità: %.1f m", fixedDepth)
            isAtSea -> depthText = "Profondità: > 12 m"
            else -> {
                val d = bathyEngine.getDepthAt(target.latitude, target.longitude, routingEngine.getNoGoAreas())
                depthText = String.format(Locale.getDefault(), "Profondità: %.1f m", d)
            }
        }

        binding.tvDevDepth.text = depthText
        val start = startMarker?.let { if (routingEngine.isAtSea(it.position)) "Sì (MARE)" else "Sì (LAGUNA)" } ?: "No"
        val end = endMarker?.let { if (routingEngine.isAtSea(it.position)) "Sì (MARE)" else "Sì (LAGUNA)" } ?: "No"
        binding.tvDevRouting.text = "Punti -> Start: $start | End: $end"
    }

    private fun updateDebugZones(center: LatLng, style: Style) {
        val step = 0.002
        val range = 8
        val features = com.google.gson.JsonArray()

        for (i in -range..range) {
            for (j in -range..range) {
                val lat = center.latitude + (i * step)
                val lon = center.longitude + (j * step)
                val isSea = routingEngine.isAtSea(LatLng(lat, lon))
                val f = com.google.gson.JsonObject()
                f.addProperty("type", "Feature")
                val p = com.google.gson.JsonObject()
                p.addProperty("color", if (isSea) "#0000FF" else "#FFFF00")
                f.add("properties", p)
                val g = com.google.gson.JsonObject()
                g.addProperty("type", "Point")
                val c = com.google.gson.JsonArray()
                c.add(lon); c.add(lat)
                g.add("coordinates", c)
                f.add("geometry", g)
                features.add(f)
            }
        }

        val fc = com.google.gson.JsonObject()
        fc.addProperty("type", "FeatureCollection")
        fc.add("features", features)

        removeDebugZones(style)
        style.addSource(GeoJsonSource("debug-zones-source", fc.toString()))
        style.addLayer(CircleLayer("debug-zones-layer", "debug-zones-source").withProperties(
            circleColor(get("color")), circleRadius(5f), circleOpacity(0.5f)
        ))
    }

    private fun removeDebugZones(style: Style) {
        style.removeLayer("debug-zones-layer")
        style.removeSource("debug-zones-source")
    }

    private fun calculateRouteIfPossible() {
        val start = startMarker?.position
        val end = endMarker?.position
        if (start != null && end != null) {
            val route = routingEngine.findRoute(start, end)
            val coordsLine = String.format(
                Locale.getDefault(), "A: %.6f,%.6f  B: %.6f,%.6f", start.latitude, start.longitude, end.latitude, end.longitude
            )
            mapLibre?.let { map ->
                routeLine?.let { map.removePolyline(it) }
                if (route != null) {
                    routeLine = map.addPolyline(PolylineOptions().addAll(route).color(android.graphics.Color.parseColor("#00008B")).width(6f))
                    val d = routingEngine.calculateTotalDistance(route)
                    val t = routingEngine.calculateEstimatedTimeMinutes(route)
                    binding.tvDevStatus.text = String.format(
                        Locale.getDefault(), "ROUTING OK: %.2f km | %d min | %d punti\n%s",
                        d / 1000.0, t, route.size, coordsLine
                    )
                    Log.d("RoutingDebug", "findRoute start=$start end=$end -> ${route.size} punti")
                    route.forEachIndexed { i, p -> Log.d("RoutingDebug", "  [$i] ${p.latitude},${p.longitude}") }
                } else {
                    binding.tvDevStatus.text = "ERRORE: ${routingEngine.lastRoutingError}\n$coordsLine"
                    Log.d("RoutingDebug", "findRoute start=$start end=$end -> NULL (${routingEngine.lastRoutingError})")
                }
            }
        }
    }

    private fun setupLagunaLayers(style: Style) {
        try {
            val inputStream = requireContext().assets.open("laguna_vettoriale.json")
            val buffer = ByteArray(inputStream.available())
            inputStream.read(buffer); inputStream.close()
            val geoJsonData = String(buffer, Charset.forName("UTF-8"))
            val source = GeoJsonSource("laguna-source-dev", geoJsonData)
            style.addSource(source)

            style.addLayer(LineLayer("obstacles-outline-dev", "laguna-source-dev").withFilter(any(eq(get("special:nav:area"), "no_go"), eq(get("special:nav:obstacle"), "rock"), eq(get("special:nav:obstade"), "rock"))).withProperties(lineColor("#FF0000"), lineWidth(3f)))
            style.addLayer(LineLayer("gates-layer-dev", "laguna-source-dev").withFilter(eq(get("special:nav:gate"), "sea")).withProperties(lineColor("#00FF00"), lineWidth(4f)))
            style.addLayer(LineLayer("bypass-sea-layer-dev", "laguna-source-dev").withFilter(eq(get("special:nav:bypass"), "sea")).withProperties(lineColor("#FFA500"), lineWidth(3f)))
            style.addLayer(LineLayer("bypass-rock-layer-dev", "laguna-source-dev").withFilter(eq(get("special:nav:bypass"), "rock")).withProperties(lineColor("#800080"), lineWidth(3f)))
            style.addLayer(LineLayer("rivers-layer-dev", "laguna-source-dev").withFilter(eq(get("waterway"), "river")).withProperties(lineColor("#00AAFF"), lineWidth(2.5f), lineOpacity(0.8f)))
            style.addLayer(LineLayer("canals-layer-dev", "laguna-source-dev").withFilter(eq(get("waterway"), "canal")).withProperties(lineColor("#FF00FF"), lineWidth(2f), lineOpacity(0.7f)))
            style.addLayer(LineLayer("mare-marker-dev", "laguna-source-dev").withFilter(eq(get("special:mare"), "yes")).withProperties(lineColor("#0000FF"), lineWidth(2f), lineDasharray(arrayOf(2f, 2f))))
            style.addLayer(LineLayer("laguna-marker-dev", "laguna-source-dev").withFilter(eq(get("special:laguna"), "yes")).withProperties(lineColor("#FFFF00"), lineWidth(2f), lineDasharray(arrayOf(2f, 2f))))
            style.addLayer(LineLayer("project-boundary-dev", "laguna-source-dev").withFilter(eq(get("special:nav:boundary"), "project")).withProperties(lineColor("#FFFF00"), lineWidth(4f), lineOpacity(0.6f)))
            style.addLayer(CircleLayer("briccole-layer-dev", "laguna-source-dev").withFilter(eq(geometryType(), literal("Point"))).withProperties(circleColor("#FFFF00"), circleRadius(3f)))
        } catch (e: Exception) { e.printStackTrace() }
    }

    // =================================================================
    // SIMULATORE BARCA
    // =================================================================

    private fun startSimulator() {
        val sim = SimulatedPositionProvider()
        // Punto di partenza = centro mappa corrente
        mapLibre?.cameraPosition?.target?.let {
            sim.setPosition(it.latitude, it.longitude)
        }
        simProvider = sim

        // Layer per la barca simulata
        mapLibre?.getStyle { style ->
            if (style.getSource(SOURCE_SIM_BOAT) == null) {
                style.addSource(GeoJsonSource(SOURCE_SIM_BOAT, buildSimPointGeoJson(sim.currentLat, sim.currentLon)))
                style.addLayer(
                    CircleLayer(LAYER_SIM_BOAT, SOURCE_SIM_BOAT).withProperties(
                        circleColor("#00FF88"),
                        circleRadius(10f),
                        circleStrokeColor("#FFFFFF"),
                        circleStrokeWidth(2.5f)
                    )
                )
            }
        }

        binding.joystick.onMove = { normX, normY, magnitude ->
            val bearing = Math.toDegrees(atan2(normX.toDouble(), -normY.toDouble())).toFloat()
            sim.setMovement(bearing, magnitude * 30f) // max 30 nodi
        }

        SimulatorHub.provider = sim
        // Il fix viene distribuito a DevTools (UI locale) e a MapFragment via SimulatorHub
        SimulatorHub.provider = sim
        sim.start { location ->
            SimulatorHub.notify(location)
            onSimFix(location)
        }

        // Long-tap sulla mappa per impostare la destinazione del simulatore
        mapLibre?.addOnMapLongClickListener { point ->
            setSimDestination(point)
            true
        }

        // Layers per rotta simulata
        mapLibre?.getStyle { style ->
            if (style.getSource(SOURCE_SIM_DONE) == null) {
                style.addSource(GeoJsonSource(SOURCE_SIM_DONE, emptyFc()))
                style.addLayer(LineLayer(LAYER_SIM_DONE, SOURCE_SIM_DONE).withProperties(
                    lineColor("#888888"), lineWidth(4f), lineOpacity(0.5f)
                ))
                style.addSource(GeoJsonSource(SOURCE_SIM_ROUTE, emptyFc()))
                style.addLayer(LineLayer(LAYER_SIM_ROUTE, SOURCE_SIM_ROUTE).withProperties(
                    lineColor("#00008B"), lineWidth(5f), lineOpacity(0.9f)
                ))
                style.addSource(GeoJsonSource(SOURCE_SIM_DEST, emptyFc()))
                style.addLayer(CircleLayer(LAYER_SIM_DEST, SOURCE_SIM_DEST).withProperties(
                    circleColor("#CC0000"), circleRadius(12f),
                    circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)
                ))
            }
        }
    }

    private fun setSimDestination(dest: LatLng) {
        val sim = simProvider ?: return
        val start = LatLng(sim.currentLat, sim.currentLon)
        val route = routingEngine.findRoute(start, dest)
        if (route == null) {
            binding.tvDevStatus.text = "Percorso non trovato: ${routingEngine.lastRoutingError}"
            return
        }
        simRoute = route; simDest = dest; simWpIdx = 0
        mapLibre?.getStyle { style ->
            drawSimRouteSplit(style, route, 0)
            setSimDestLayer(style, dest)
        }
        binding.tvDevStatus.text = "Percorso: ${route.size} punti, tap per aggiornare"
    }

    private fun drawSimRouteSplit(style: Style, route: List<LatLng>, idx: Int) {
        fun lineGeoJson(pts: List<LatLng>): String {
            if (pts.size < 2) return emptyFc()
            val coords = com.google.gson.JsonArray().also { a -> pts.forEach { p -> a.add(com.google.gson.JsonArray().apply { add(p.longitude); add(p.latitude) }) } }
            val feat = com.google.gson.JsonObject().apply {
                addProperty("type","Feature"); add("properties", com.google.gson.JsonObject())
                add("geometry", com.google.gson.JsonObject().apply { addProperty("type","LineString"); add("coordinates", coords) })
            }
            return com.google.gson.JsonObject().apply { addProperty("type","FeatureCollection"); add("features", com.google.gson.JsonArray().apply { add(feat) }) }.toString()
        }
        (style.getSource(SOURCE_SIM_DONE)  as? GeoJsonSource)?.setGeoJson(lineGeoJson(if (idx > 0) route.subList(0, idx+1) else emptyList()))
        (style.getSource(SOURCE_SIM_ROUTE) as? GeoJsonSource)?.setGeoJson(lineGeoJson(if (idx < route.size) route.subList(idx, route.size) else emptyList()))
    }

    private fun setSimDestLayer(style: Style, dest: LatLng) {
        val feat = com.google.gson.JsonObject().apply {
            addProperty("type","Feature"); add("properties", com.google.gson.JsonObject())
            add("geometry", com.google.gson.JsonObject().apply {
                addProperty("type","Point")
                add("coordinates", com.google.gson.JsonArray().apply { add(dest.longitude); add(dest.latitude) })
            })
        }
        (style.getSource(SOURCE_SIM_DEST) as? GeoJsonSource)?.setGeoJson(
            com.google.gson.JsonObject().apply { addProperty("type","FeatureCollection"); add("features", com.google.gson.JsonArray().apply { add(feat) }) }.toString()
        )
    }

    private fun emptyFc() = """{"type":"FeatureCollection","features":[]}"""

    private fun stopSimulator() {
        simProvider?.stop()
        SimulatorHub.provider = null
        simRoute = null; simDest = null; simWpIdx = 0
        simProvider = null
        binding.joystick.onMove = null
        mapLibre?.getStyle { style ->
            style.removeLayer(LAYER_SIM_BOAT)
            style.removeSource(SOURCE_SIM_BOAT)
        }
        binding.tvDevStatus.text = "MODALITÀ DEBUG"
    }

    private fun onSimFix(location: Location) {
        val pos       = LatLng(location.latitude, location.longitude)
        val speedKn   = location.speed * 3600f / 1852f
        val limitKn   = routingEngine.getMaxSpeedKnotsAt(pos)
        val limitStr  = if (limitKn != null) "%.0f kn".format(limitKn) else "--"
        val overLimit = limitKn != null && speedKn > limitKn
        val isAtSea   = routingEngine.isAtSea(pos)
        val distCanal = routingEngine.distanceToNearestCanalMeters(pos)
        val distStr   = if (distCanal < Double.MAX_VALUE / 2) "%.0f m".format(distCanal) else "--"

        // Aggiornamento navigazione locale simulatore
        val route = simRoute
        var navLine = ""
        if (route != null && simDest != null) {
            while (simWpIdx < route.size - 1 && haversineLocal(pos, route[simWpIdx]) < WAYPOINT_ADV) simWpIdx++
            val remaining = if (simWpIdx < route.size) route.drop(simWpIdx) else emptyList()
            val etaMin  = if (remaining.size >= 2) routingEngine.calculateEstimatedTimeMinutes(remaining) else 0
            val distKm  = if (remaining.size >= 2) routingEngine.calculateTotalDistance(remaining) / 1000.0 else 0.0
            val distWp  = if (simWpIdx < route.size) haversineLocal(pos, route[simWpIdx]) else 0.0
            val offCanal = distCanal > 30.0 && !isAtSea
            navLine = "\n--- NAVIGAZIONE ---\nProssimo wp: %.0f m | ETA: %d min | %.2f km\n%s".format(
                distWp, etaMin, distKm,
                if (offCanal) "!!! FUORI CANALE (${distStr}) !!!" else "In canale"
            )
            mapLibre?.getStyle { style ->
                if (simWpIdx > 0) drawSimRouteSplit(style, route, simWpIdx)
            }
        }

        binding.tvSimStatus.text = "%.6f, %.6f  [${if (isAtSea) "MARE" else "LAGUNA"}]\n%.1f kn / Lim $limitStr  %s  Dist.canal: $distStr%s".format(
            location.latitude, location.longitude,
            speedKn, "%d°".format(location.bearing.roundToInt()),
            navLine
        )
        binding.tvSimStatus.setTextColor(
            if (overLimit) android.graphics.Color.parseColor("#FF4444")
            else android.graphics.Color.WHITE
        )

        mapLibre?.getStyle { style ->
            (style.getSource(SOURCE_SIM_BOAT) as? GeoJsonSource)
                ?.setGeoJson(buildSimPointGeoJson(location.latitude, location.longitude))
        }
    }

    private fun haversineLocal(a: LatLng, b: LatLng): Double {
        val r = 6371000.0
        val dlat = Math.toRadians(b.latitude - a.latitude)
        val dlon = Math.toRadians(b.longitude - a.longitude)
        val x = Math.sin(dlat/2)*Math.sin(dlat/2) + Math.cos(Math.toRadians(a.latitude))*Math.cos(Math.toRadians(b.latitude))*Math.sin(dlon/2)*Math.sin(dlon/2)
        return 2*r*Math.atan2(Math.sqrt(x), Math.sqrt(1-x))
    }

    private fun buildSimPointGeoJson(lat: Double, lon: Double): String {
        val coords = JsonArray().apply { add(lon); add(lat) }
        val geom   = JsonObject().apply { addProperty("type", "Point"); add("coordinates", coords) }
        val feat   = JsonObject().apply {
            addProperty("type", "Feature")
            add("properties", JsonObject())
            add("geometry", geom)
        }
        return JsonObject().apply {
            addProperty("type", "FeatureCollection")
            add("features", JsonArray().apply { add(feat) })
        }.toString()
    }

    override fun onStart() { super.onStart(); binding.mapViewDev.onStart() }
    override fun onResume() { super.onResume(); binding.mapViewDev.onResume() }
    override fun onPause() { super.onPause(); binding.mapViewDev.onPause(); simProvider?.stop() }
    override fun onStop() { super.onStop(); binding.mapViewDev.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); binding.mapViewDev.onSaveInstanceState(outState) }
    override fun onLowMemory() { super.onLowMemory(); binding.mapViewDev.onLowMemory() }
    override fun onDestroyView() {
        super.onDestroyView()
        binding.mapViewDev.onDestroy()
        _binding = null
    }
}
