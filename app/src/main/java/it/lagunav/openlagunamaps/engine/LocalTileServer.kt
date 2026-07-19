package it.lagunav.openlagunamaps.engine

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/**
 * Serve localmente (127.0.0.1) le tile vettoriali/raster, i glifi e lo sprite della mappa
 * offline precotta, leggendoli dai database SQLite copiati in filesDir da LocalAssetInstaller.
 *
 * Sostituisce la dipendenza dal meccanismo di cache/offline-region interno di MapLibre: qui i
 * dati sono in un formato che controlliamo e verifichiamo noi (mbtiles standard), serviti allo
 * stesso modo sia online che offline — un solo percorso di codice, sempre testabile.
 *
 * Le tile vettoriali fuori dall'area bundlata (laguna+35km) vengono recuperate dalla rete al
 * volo quando disponibile e salvate in una cache scrivibile separata (tiles_vector_cache.mbtiles),
 * così restano disponibili anche offline la volta successiva — senza toccare il pacchetto
 * bundlato di sola lettura. Zoom 14 è il massimo nativo del tileset OpenFreeMap: oltre non c'è
 * altro dettaglio da scaricare, MapLibre ingrandisce da solo i tile di zoom 14 (overzoom),
 * esattamente come già fa online.
 */
object LocalTileServer {
    private const val TAG = "LocalTileServer"
    private const val REMOTE_TILEJSON_URL = "https://tiles.openfreemap.org/planet"
    private const val REMOTE_RETRY_BACKOFF_MS = 30_000L
    private const val REMOTE_TIMEOUT_MS = 4_000

    // Tetto alla cache scrivibile delle tile scaricate al volo fuori dall'area bundlata: senza
    // limite crescerebbe indefinitamente navigando molto online. Quando superato, si cancellano
    // le tile più LONTANE da Venezia (non le più vecchie): quello che conta è tenere quello che
    // serve davvero per navigare in laguna, non la cronologia di quando è stato scaricato.
    private const val MAX_CACHE_BYTES = 200L * 1_000_000L
    private const val VENICE_LAT = 45.4371
    private const val VENICE_LON = 12.3345

    private var server: Server? = null
    var port: Int = -1
        private set

    @Volatile private var remoteVectorTemplate: String? = null
    @Volatile private var nextRemoteAttemptAllowedAt: Long = 0L

    fun startIfNeeded(context: Context) {
        if (server != null) return
        val freePort = ServerSocket(0).use { it.localPort }
        val instance = Server(context.applicationContext, freePort)
        try {
            instance.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = instance
            port = freePort
            Log.d(TAG, "Avviato su 127.0.0.1:$freePort")
        } catch (e: Exception) {
            Log.e(TAG, "Impossibile avviare il server locale sulla porta $freePort", e)
        }
    }

    /** Template remoto tipo ".../planet/20260621_080001_pt/{z}/{x}/{y}.pbf", risolto una volta
     *  via TileJSON e tenuto in memoria. Se offline o il fetch fallisce, ritenta solo dopo un
     *  breve backoff — altrimenti ogni tile mancante nel pan offline bloccherebbe la UI per il
     *  timeout di rete completo. */
    private fun resolveRemoteVectorTemplate(): String? {
        remoteVectorTemplate?.let { return it }
        val now = System.currentTimeMillis()
        if (now < nextRemoteAttemptAllowedAt) return null
        return try {
            val conn = (URL(REMOTE_TILEJSON_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = REMOTE_TIMEOUT_MS
                readTimeout = REMOTE_TIMEOUT_MS
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val template = JSONObject(body).getJSONArray("tiles").getString(0)
            remoteVectorTemplate = template
            template
        } catch (e: Exception) {
            Log.d(TAG, "TileJSON remoto non disponibile (probabilmente offline): ${e.message}")
            nextRemoteAttemptAllowedAt = now + REMOTE_RETRY_BACKOFF_MS
            null
        }
    }

    /** Distanza approssimata (km) tra il centro del tile z/x/y e Venezia, per decidere quali
     *  tile buttare via per prime quando la cache supera il tetto massimo. Approssimazione
     *  equirettangolare: più che sufficiente per un ordinamento, non serve precisione geodetica. */
    private fun tileDistanceFromVeniceKm(z: Int, x: Int, y: Int): Double {
        val n = (1 shl z).toDouble()
        val lon = (x + 0.5) / n * 360.0 - 180.0
        val latRad = kotlin.math.atan(kotlin.math.sinh(Math.PI * (1 - 2 * (y + 0.5) / n)))
        val lat = Math.toDegrees(latRad)
        val dLat = Math.toRadians(lat - VENICE_LAT)
        val dLon = Math.toRadians(lon - VENICE_LON) * kotlin.math.cos(Math.toRadians((lat + VENICE_LAT) / 2))
        return kotlin.math.sqrt(dLat * dLat + dLon * dLon) * 6371.0
    }

    private fun fetchRemoteTile(template: String, z: Int, x: Int, y: Int): ByteArray? {
        val now = System.currentTimeMillis()
        if (now < nextRemoteAttemptAllowedAt) return null
        val url = template.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = REMOTE_TIMEOUT_MS
                readTimeout = REMOTE_TIMEOUT_MS
            }
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            bytes.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            nextRemoteAttemptAllowedAt = now + REMOTE_RETRY_BACKOFF_MS
            null
        }
    }

    private class Server(private val context: Context, port: Int) : NanoHTTPD("127.0.0.1", port) {

        private val cacheDbLock = Any()
        private var cacheDb: SQLiteDatabase? = null

        // Connessioni di sola lettura riusate: aprire un SQLiteDatabase da 150MB ad ogni singola
        // richiesta di tile era abbastanza lento da far scadere la richiesta prima di completarsi
        // (il loop camera, aggiornando la posizione decine di volte al secondo, fa ricalcolare a
        // MapLibre le tile "necessarie" e annulla quelle ancora in corso) — risultato: le tile
        // più pesanti (zoom alto, vicino) non arrivavano mai a scaricarsi, schermo bianco proprio
        // dove si zooma di più. SQLiteDatabase supporta letture concorrenti da più thread sulla
        // stessa connessione, quindi una sola apertura per file basta e avanza.
        private val readOnlyDbsLock = Any()
        private val readOnlyDbs = HashMap<String, SQLiteDatabase?>()

        private fun dbFile(name: String) = File(context.filesDir, name)

        private fun openReadOnly(name: String): SQLiteDatabase? = synchronized(readOnlyDbsLock) {
            if (readOnlyDbs.containsKey(name)) return readOnlyDbs[name]
            val f = dbFile(name)
            val db = if (f.exists()) {
                try {
                    SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                } catch (e: Exception) {
                    Log.e(TAG, "Impossibile aprire $name", e)
                    null
                }
            } else null
            readOnlyDbs[name] = db
            db
        }

        /** Cache scrivibile per le tile vettoriali scaricate al volo fuori dall'area bundlata.
         *  Un'unica connessione riusata (SQLiteDatabase gestisce da sé la concorrenza tra thread
         *  delle richieste NanoHTTPD). */
        private fun openCacheDb(): SQLiteDatabase = synchronized(cacheDbLock) {
            cacheDb?.let { return it }
            val db = SQLiteDatabase.openOrCreateDatabase(dbFile("tiles_vector_cache.mbtiles"), null)
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, dist_km REAL, PRIMARY KEY (zoom_level, tile_column, tile_row))"
            )
            cacheDb = db
            db
        }

        /** Inserisce la tile in cache (z/x/y non capovolti, per il calcolo distanza — tmsRow è
         *  solo la convenzione di storage) e, se il file supera il tetto massimo, cancella le
         *  tile più lontane da Venezia finché non si rientra nel limite. */
        private fun insertIntoCacheAndEvict(cache: SQLiteDatabase, z: Int, x: Int, y: Int, tmsRow: Int, data: ByteArray) {
            synchronized(cacheDbLock) {
                val distKm = tileDistanceFromVeniceKm(z, x, y)
                cache.execSQL(
                    "INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data, dist_km) VALUES (?, ?, ?, ?, ?)",
                    arrayOf(z, x, tmsRow, data, distKm)
                )
                var totalBytes = cache.rawQuery("SELECT COALESCE(SUM(LENGTH(tile_data)), 0) FROM tiles", null).use {
                    it.moveToFirst(); it.getLong(0)
                }
                if (totalBytes <= MAX_CACHE_BYTES) return
                cache.rawQuery("SELECT zoom_level, tile_column, tile_row, LENGTH(tile_data) FROM tiles ORDER BY dist_km DESC", null).use { cursor ->
                    while (totalBytes > MAX_CACHE_BYTES && cursor.moveToNext()) {
                        val zz = cursor.getInt(0)
                        val xx = cursor.getInt(1)
                        val rr = cursor.getInt(2)
                        val size = cursor.getLong(3)
                        cache.execSQL(
                            "DELETE FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                            arrayOf(zz, xx, rr)
                        )
                        totalBytes -= size
                    }
                }
                Log.d(TAG, "Cache tile evictata, dimensione ora ~${totalBytes / 1_000_000}MB")
            }
        }

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            return try {
                when {
                    uri.startsWith("/tiles/") -> serveVectorTile(uri.removePrefix("/tiles/"))
                    uri.startsWith("/raster/") -> serveTile(uri.removePrefix("/raster/"), "tiles_raster.mbtiles", "image/png")
                    uri.startsWith("/fonts/") -> serveGlyph(uri.removePrefix("/fonts/"))
                    uri.startsWith("/sprite/") -> serveSprite(uri.removePrefix("/sprite/"))
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore servendo $uri", e)
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "error")
            }
        }

        private fun parseZxy(path: String): Triple<Int, Int, Int>? {
            val parts = path.substringBeforeLast('.').split("/")
            if (parts.size != 3) return null
            val z = parts[0].toIntOrNull() ?: return null
            val x = parts[1].toIntOrNull() ?: return null
            val y = parts[2].toIntOrNull() ?: return null
            return Triple(z, x, y)
        }

        private fun queryTile(db: SQLiteDatabase, z: Int, x: Int, tmsRow: Int): ByteArray? {
            db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                arrayOf(z.toString(), x.toString(), tmsRow.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) return cursor.getBlob(0)
            }
            return null
        }

        // uri tipo "9/280/190.png" (raster di sfondo, completamente bundlato, nessuna cache online)
        private fun serveTile(path: String, dbName: String, mime: String): Response {
            val (z, x, y) = parseZxy(path) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "bad path")
            val tmsRow = (1 shl z) - 1 - y
            val db = openReadOnly(dbName) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no db")
            queryTile(db, z, x, tmsRow)?.let { data ->
                return newFixedLengthResponse(Response.Status.OK, mime, data.inputStream(), data.size.toLong())
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no tile")
        }

        // uri tipo "9/280/190.pbf" — bundlato -> cache locale -> rete (se disponibile, e la
        // salva in cache per la prossima volta) -> 404.
        private fun serveVectorTile(path: String): Response {
            val (z, x, y) = parseZxy(path) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "bad path")
            val tmsRow = (1 shl z) - 1 - y
            val mime = "application/x-protobuf"

            openReadOnly("tiles_vector.mbtiles")?.let { db ->
                queryTile(db, z, x, tmsRow)?.let { data ->
                    return newFixedLengthResponse(Response.Status.OK, mime, data.inputStream(), data.size.toLong())
                }
            }

            val cache = openCacheDb()
            queryTile(cache, z, x, tmsRow)?.let { data ->
                return newFixedLengthResponse(Response.Status.OK, mime, data.inputStream(), data.size.toLong())
            }

            val template = resolveRemoteVectorTemplate()
            if (template != null) {
                val data = fetchRemoteTile(template, z, x, y)
                if (data != null) {
                    insertIntoCacheAndEvict(cache, z, x, y, tmsRow, data)
                    return newFixedLengthResponse(Response.Status.OK, mime, data.inputStream(), data.size.toLong())
                }
            }

            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no tile")
        }

        // uri tipo "Noto Sans Regular/0-255.pbf"
        private fun serveGlyph(path: String): Response {
            val lastSlash = path.lastIndexOf('/')
            if (lastSlash < 0) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "bad path")
            val fontstack = java.net.URLDecoder.decode(path.substring(0, lastSlash), "UTF-8")
            val range = path.substring(lastSlash + 1).removeSuffix(".pbf")
            val rangeStart = range.substringBefore('-').toIntOrNull()
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "bad range")

            val db = openReadOnly("glyphs.db") ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no db")
            db.rawQuery(
                "SELECT data FROM glyphs WHERE fontstack=? AND range_start=?",
                arrayOf(fontstack, rangeStart.toString())
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val data = cursor.getBlob(0)
                    return newFixedLengthResponse(Response.Status.OK, "application/x-protobuf", data.inputStream(), data.size.toLong())
                }
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no glyph")
        }

        // uri tipo "ofm.json", "ofm.png", "ofm@2x.json", "ofm@2x.png"
        private fun serveSprite(name: String): Response {
            val fileName = "sprite_$name"
            val mime = if (name.endsWith(".png")) "image/png" else "application/json"
            return try {
                val bytes = context.assets.open(fileName).use { it.readBytes() }
                newFixedLengthResponse(Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong())
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no sprite")
            }
        }
    }
}
