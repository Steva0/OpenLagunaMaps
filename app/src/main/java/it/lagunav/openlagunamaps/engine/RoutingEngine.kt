package it.lagunav.openlagunamaps.engine

import android.content.Context
import kotlinx.serialization.json.*
import org.maplibre.android.geometry.LatLng
import java.util.*
import kotlin.math.*

data class Node(val id: String, val lat: Double, val lon: Double)
data class Edge(val u: String, val v: String, val lengthM: Double, val depthM: Double, val speedKmh: Double)
data class NoGoArea(val id: String, val polygon: List<LatLng>, val isRock: Boolean)
data class Segment(val p1: LatLng, val p2: LatLng)
data class FixedDepthArea(val depth: Float, val polygon: List<LatLng>)

/**
 * Motore di routing navale.
 *
 * Stato di avanzamento (vedi roadmap nel CLAUDE.md del progetto):
 * - LAGUNA -> LAGUNA: implementato (snap al canale più vicino + A* sul grafo canali).
 * - MARE -> MARE: TODO, non ancora reimplementato.
 * - LAGUNA <-> MARE (misto): TODO, non ancora reimplementato.
 */
class RoutingEngine(private val context: Context) {

    private val nodes = mutableMapOf<String, Node>()
    private val edges = mutableListOf<Edge>()
    private val adj = mutableMapOf<String, MutableList<Edge>>()
    private val noGoAreas = mutableListOf<NoGoArea>()

    private val seaSegments = mutableListOf<Segment>()
    private val lagunaSegments = mutableListOf<Segment>()
    private var projectBoundary: List<LatLng>? = null
    private val fixedDepthAreas = mutableListOf<FixedDepthArea>()

    private val json = Json { ignoreUnknownKeys = true }

    var userAverageSpeedKmH: Double = 30.0
    var lastRoutingError: String = ""

    // Velocità di default per gli archi senza "maxspeed" in tag OSM.
    private val DEFAULT_SPEED_KNOTS = 12.0
    private val DEFAULT_SPEED_KMH = DEFAULT_SPEED_KNOTS * 1.852

    // Limite superiore di velocità osservato nel grafo, usato come euristica ammissibile per A*.
    private var maxSpeedKmh = DEFAULT_SPEED_KMH

    init {
        loadGraph()
        loadMarkersAndBoundaries()
    }

    // =================================================================
    // CARICAMENTO DATI
    // =================================================================

    private fun parseSpeedKmh(el: JsonElement?): Double {
        if (el == null || el is JsonNull) return DEFAULT_SPEED_KMH
        val content = el.jsonPrimitive.contentOrNull ?: return DEFAULT_SPEED_KMH
        // Il campo "s" nel dato sorgente è incoerente: a volte un numero puro ("11"),
        // a volte testo con unità ("5 knots"). Estraiamo sempre la prima cifra trovata.
        val knots = Regex("[0-9]+(\\.[0-9]+)?").find(content)?.value?.toDoubleOrNull() ?: return DEFAULT_SPEED_KMH
        return knots * 1.852
    }

    private fun loadGraph() {
        try {
            val jsonString = context.assets.open("graph.json").bufferedReader().use { it.readText() }
            val root = json.parseToJsonElement(jsonString).jsonObject

            root["nodes"]?.jsonObject?.forEach { (id, element) ->
                val obj = element.jsonObject
                nodes[id] = Node(
                    id = id,
                    lat = obj["lat"]?.jsonPrimitive?.double ?: 0.0,
                    lon = obj["lon"]?.jsonPrimitive?.double ?: 0.0
                )
            }

            root["edges"]?.jsonArray?.forEach { element ->
                val obj = element.jsonObject
                val u = obj["u"]?.jsonPrimitive?.content ?: return@forEach
                val v = obj["v"]?.jsonPrimitive?.content ?: return@forEach
                val length = obj["l"]?.jsonPrimitive?.double ?: 0.0
                val depth = obj["d"]?.jsonPrimitive?.double ?: 0.0
                val speedKmh = parseSpeedKmh(obj["s"])

                val edge = Edge(u, v, length, depth, speedKmh)
                edges.add(edge)
                adj.getOrPut(u) { mutableListOf() }.add(edge)
                adj.getOrPut(v) { mutableListOf() }.add(edge)
                if (speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh
            }

            root["no_go_areas"]?.jsonArray?.forEach { element ->
                val obj = element.jsonObject
                val coords = obj["nodes"]?.jsonArray?.map {
                    val c = it.jsonArray
                    LatLng(c[0].jsonPrimitive.double, c[1].jsonPrimitive.double)
                } ?: emptyList()
                val isRock = obj["rock"]?.jsonPrimitive?.boolean ?: false
                if (coords.size > 2) {
                    val polygon = if (coords.first() != coords.last()) coords + coords.first() else coords
                    noGoAreas.add(NoGoArea(obj["id"]?.jsonPrimitive?.content ?: "", polygon, isRock))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadMarkersAndBoundaries() {
        try {
            val jsonString = context.assets.open("laguna_vettoriale.json").bufferedReader().use { it.readText() }
            val root = json.parseToJsonElement(jsonString).jsonObject
            root["features"]?.jsonArray?.forEach { feature ->
                val props = feature.jsonObject["properties"]?.jsonObject ?: return@forEach
                val geom = feature.jsonObject["geometry"]?.jsonObject ?: return@forEach
                val type = geom["type"]?.jsonPrimitive?.content ?: ""
                val coordsArray = geom["coordinates"]?.jsonArray ?: return@forEach

                val lls = when (type) {
                    "Polygon" -> coordsArray[0].jsonArray.map { LatLng(it.jsonArray[1].jsonPrimitive.double, it.jsonArray[0].jsonPrimitive.double) }
                    "LineString" -> coordsArray.map { LatLng(it.jsonArray[1].jsonPrimitive.double, it.jsonArray[0].jsonPrimitive.double) }
                    "Point" -> listOf(LatLng(coordsArray[1].jsonPrimitive.double, coordsArray[0].jsonPrimitive.double))
                    else -> emptyList()
                }
                if (lls.isEmpty()) return@forEach

                val isNoGo = props["special:nav:area"]?.jsonPrimitive?.content == "no_go"
                val isRock = props["special:nav:obstacle"]?.jsonPrimitive?.content == "rock" ||
                        props["special:nav:obstade"]?.jsonPrimitive?.content == "rock"

                if ((isNoGo || isRock) && noGoAreas.none { it.polygon.firstOrNull() == lls.firstOrNull() }) {
                    noGoAreas.add(NoGoArea("", lls, isRock))
                }

                if (props["special:nav:area"]?.jsonPrimitive?.content == "depth_fixed") {
                    fixedDepthAreas.add(FixedDepthArea(props["depth"]?.jsonPrimitive?.floatOrNull ?: 12f, lls))
                }

                if (props["special:nav:boundary"]?.jsonPrimitive?.content == "project") {
                    projectBoundary = lls
                }

                val isSeaMarker = props.containsKey("special:mare")
                val isLagunaMarker = props.containsKey("special:laguna")
                if (isSeaMarker) for (i in 0 until lls.size - 1) seaSegments.add(Segment(lls[i], lls[i + 1]))
                if (isLagunaMarker) for (i in 0 until lls.size - 1) lagunaSegments.add(Segment(lls[i], lls[i + 1]))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // =================================================================
    // ROUTING CORE INTERFACE
    // =================================================================

    fun findRoute(start: LatLng, end: LatLng, minDepth: Double = 0.5): List<LatLng>? {
        lastRoutingError = ""
        val sSea = isAtSea(start)
        val eSea = isAtSea(end)

        return when {
            !sSea && !eSea -> solveLagunaToLaguna(start, end, minDepth)
            sSea && eSea -> solveSeaToSea(start, end)
            else -> solveMixed(start, end, minDepth)
        }
    }

    // =================================================================
    // CASO 1: LAGUNA -> LAGUNA (implementato)
    //
    // Strategia: si proietta il punto di partenza sul canale (arco del grafo)
    // più vicino, idem per l'arrivo, poi si naviga col solo grafo dei canali
    // (A*, costo = tempo) dal punto di innesto di partenza a quello di arrivo.
    // =================================================================

    private data class EdgeSnap(val edge: Edge, val point: LatLng)

    private fun solveLagunaToLaguna(start: LatLng, end: LatLng, minDepth: Double): List<LatLng>? {
        val snapStart = snapToNearestEdge(start) ?: run { lastRoutingError = "Nessun canale trovato vicino al punto di partenza"; return null }
        val snapEnd = snapToNearestEdge(end) ?: run { lastRoutingError = "Nessun canale trovato vicino al punto di arrivo"; return null }

        // Caso degenere: partenza e arrivo si agganciano allo stesso canale, nessun A* necessario.
        if (snapStart.edge === snapEnd.edge) {
            if (snapStart.edge.depthM in 0.1..<minDepth) {
                lastRoutingError = "Canale non navigabile (profondità minima richiesta: ${minDepth}m)"
                return null
            }
            return listOf(start, snapStart.point, snapEnd.point, end)
        }

        val startId = "__snap_start__"
        val endId = "__snap_end__"
        val virtualPositions = mapOf(startId to snapStart.point, endId to snapEnd.point)
        val extra = mutableMapOf<String, MutableList<Edge>>()

        fun addVirtualEdge(from: String, edgeSnap: EdgeSnap) {
            val uNode = nodes[edgeSnap.edge.u] ?: return
            val vNode = nodes[edgeSnap.edge.v] ?: return
            val lenToU = haversine(edgeSnap.point.latitude, edgeSnap.point.longitude, uNode.lat, uNode.lon)
            val lenToV = haversine(edgeSnap.point.latitude, edgeSnap.point.longitude, vNode.lat, vNode.lon)
            val eToU = Edge(from, edgeSnap.edge.u, lenToU, edgeSnap.edge.depthM, edgeSnap.edge.speedKmh)
            val eToV = Edge(from, edgeSnap.edge.v, lenToV, edgeSnap.edge.depthM, edgeSnap.edge.speedKmh)
            extra.getOrPut(from) { mutableListOf() }.addAll(listOf(eToU, eToV))
            extra.getOrPut(edgeSnap.edge.u) { mutableListOf() }.add(eToU)
            extra.getOrPut(edgeSnap.edge.v) { mutableListOf() }.add(eToV)
        }
        addVirtualEdge(startId, snapStart)
        addVirtualEdge(endId, snapEnd)

        fun neighbors(id: String): List<Edge> = (adj[id].orEmpty()) + (extra[id].orEmpty())
        fun position(id: String): LatLng? = virtualPositions[id] ?: nodes[id]?.let { LatLng(it.lat, it.lon) }

        val pathIds = runCanalAStar(startId, endId, minDepth, ::neighbors, ::position)
            ?: run { lastRoutingError = "Nessun canale praticabile (profondità minima: ${minDepth}m)"; return null }

        val path = mutableListOf<LatLng>()
        path.add(start)
        pathIds.forEach { id -> position(id)?.let { path.add(it) } }
        path.add(end)
        return path
    }

    /** Trova, a forza bruta, l'arco del grafo più vicino al punto e il punto di proiezione su di esso. */
    private fun snapToNearestEdge(p: LatLng): EdgeSnap? {
        var best: EdgeSnap? = null
        var bestDist = Double.MAX_VALUE
        edges.forEach { e ->
            val uNode = nodes[e.u] ?: return@forEach
            val vNode = nodes[e.v] ?: return@forEach
            val proj = closestPointOnSegment(p, LatLng(uNode.lat, uNode.lon), LatLng(vNode.lat, vNode.lon))
            val dist = haversine(p.latitude, p.longitude, proj.latitude, proj.longitude)
            if (dist < bestDist) {
                bestDist = dist
                best = EdgeSnap(e, proj)
            }
        }
        return best
    }

    private fun edgeTimeSeconds(e: Edge): Double {
        val speed = if (e.speedKmh > 0.0) e.speedKmh else DEFAULT_SPEED_KMH
        return (e.lengthM / 1000.0) / speed * 3600.0
    }

    private fun runCanalAStar(
        startId: String,
        endId: String,
        minDepth: Double,
        neighbors: (String) -> List<Edge>,
        position: (String) -> LatLng?
    ): List<String>? {
        if (startId == endId) return listOf(startId)

        fun heuristicSeconds(fromId: String, toId: String): Double {
            val a = position(fromId) ?: return 0.0
            val b = position(toId) ?: return 0.0
            return haversine(a.latitude, a.longitude, b.latitude, b.longitude) / 1000.0 / maxSpeedKmh * 3600.0
        }

        val times = mutableMapOf<String, Double>()
        val prev = mutableMapOf<String, String?>()
        val pq = PriorityQueue<Triple<String, Double, Double>>(compareBy { it.second + it.third })

        times[startId] = 0.0
        pq.add(Triple(startId, 0.0, heuristicSeconds(startId, endId)))

        while (pq.isNotEmpty()) {
            val (u, g, _) = pq.poll()!!
            if (u == endId) break
            if (g > (times[u] ?: Double.MAX_VALUE)) continue

            neighbors(u).forEach { e ->
                if (e.depthM in 0.1..<minDepth) return@forEach
                val v = if (e.u == u) e.v else e.u
                val newTime = g + edgeTimeSeconds(e)
                if (newTime < (times[v] ?: Double.MAX_VALUE)) {
                    times[v] = newTime
                    prev[v] = u
                    pq.add(Triple(v, newTime, heuristicSeconds(v, endId)))
                }
            }
        }

        val path = mutableListOf<String>()
        var curr: String? = endId
        while (curr != null) {
            path.add(0, curr)
            curr = prev[curr]
        }
        return if (path.size >= 2 && path.first() == startId) path else null
    }

    // =================================================================
    // CASO 2: MARE -> MARE (TODO, da reimplementare)
    // =================================================================

    private fun solveSeaToSea(start: LatLng, end: LatLng): List<LatLng>? {
        lastRoutingError = "Routing mare-mare non ancora implementato (TODO)"
        return null
    }

    // =================================================================
    // CASO 3: LAGUNA <-> MARE, MISTO (TODO, da reimplementare)
    // =================================================================

    private fun solveMixed(start: LatLng, end: LatLng, minDepth: Double): List<LatLng>? {
        lastRoutingError = "Routing misto laguna/mare non ancora implementato (TODO)"
        return null
    }

    // =================================================================
    // QUERY DI SUPPORTO (usate da UI / HUD, indipendenti dal routing)
    // =================================================================

    fun getFixedDepthAt(p: LatLng): Float? = fixedDepthAreas.find { containsPoint(it.polygon, p) }?.depth
    fun isPointInNoGo(p: LatLng): Boolean = noGoAreas.any { containsPoint(it.polygon, p) }
    fun isInsideProject(p: LatLng): Boolean = projectBoundary?.let { containsPoint(it, p) } ?: true
    fun getNoGoAreas(): List<NoGoArea> = noGoAreas

    fun calculateEstimatedTimeMinutes(p: List<LatLng>): Int {
        if (p.size < 2) return 0
        return (calculateTotalTimeSeconds(p) / 60.0).toInt()
    }

    // Approssimazione: la distanza totale del percorso disegnato viene divisa per la
    // velocità di default. Le velocità reali per-arco sono già usate internamente da
    // A* per scegliere il percorso più veloce, ma si perdono una volta che il risultato
    // è appiattito in una semplice lista di punti.
    fun calculateTotalTimeSeconds(path: List<LatLng>): Double {
        val totalDist = calculateTotalDistance(path)
        return (totalDist / 1000.0) / DEFAULT_SPEED_KMH * 3600.0
    }

    fun calculateTotalDistance(path: List<LatLng>): Double {
        var totalDist = 0.0
        for (i in 0 until path.size - 1) {
            totalDist += haversine(path[i].latitude, path[i].longitude, path[i + 1].latitude, path[i + 1].longitude)
        }
        return totalDist
    }

    fun isAtSea(p: LatLng): Boolean {
        if (fixedDepthAreas.any { containsPoint(it.polygon, p) }) return false
        var maxSeaLon = -180.0
        var maxLagunaLon = -180.0
        seaSegments.forEach { s -> getHInt(p.latitude, s.p1, s.p2)?.let { if (it <= p.longitude) maxSeaLon = max(maxSeaLon, it) } }
        lagunaSegments.forEach { s -> getHInt(p.latitude, s.p1, s.p2)?.let { if (it <= p.longitude) maxLagunaLon = max(maxLagunaLon, it) } }
        return if (maxSeaLon == -180.0 && maxLagunaLon == -180.0) false else maxSeaLon > maxLagunaLon
    }

    private fun getHInt(lat: Double, p1: LatLng, p2: LatLng): Double? {
        if ((p1.latitude <= lat && p2.latitude > lat) || (p2.latitude <= lat && p1.latitude > lat)) {
            return p1.longitude + (lat - p1.latitude) / (p2.latitude - p1.latitude) * (p2.longitude - p1.longitude)
        }
        return null
    }

    private fun closestPointOnSegment(p: LatLng, a: LatLng, b: LatLng): LatLng {
        val l2 = (b.latitude - a.latitude).pow(2) + (b.longitude - a.longitude).pow(2)
        if (l2 == 0.0) return a
        var t = ((p.latitude - a.latitude) * (b.latitude - a.latitude) + (p.longitude - a.longitude) * (b.longitude - a.longitude)) / l2
        t = max(0.0, min(1.0, t))
        return LatLng(a.latitude + t * (b.latitude - a.latitude), a.longitude + t * (b.longitude - a.longitude))
    }

    private fun containsPoint(poly: List<LatLng>, p: LatLng): Boolean {
        var res = false
        var j = poly.size - 1
        for (i in poly.indices) {
            if (((poly[i].latitude > p.latitude) != (poly[j].latitude > p.latitude)) &&
                (p.longitude < (poly[j].longitude - poly[i].longitude) * (p.latitude - poly[i].latitude) / (poly[j].latitude - poly[i].latitude) + poly[i].longitude)) {
                res = !res
            }
            j = i
        }
        return res
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
