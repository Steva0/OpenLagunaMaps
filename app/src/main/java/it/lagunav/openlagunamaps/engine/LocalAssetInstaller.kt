package it.lagunav.openlagunamaps.engine

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Copia i database SQLite della mappa offline precotta (tile vettoriali, tile raster di
 * sfondo, glifi — vedi genera_tiles_offline.py) dagli asset a filesDir al primo avvio, cosi'
 * LocalTileServer puo' aprirli con un path reale (SQLite non puo' leggere direttamente da un
 * asset compresso dentro l'APK).
 *
 * Sostituisce il vecchio OfflinePackInstaller, che copiava il database interno di MapLibre
 * (mbgl-offline.db) confidando che la libreria nativa lo riconoscesse: non funzionava in modo
 * affidabile. Qui copiamo formati SQLite standard che apriamo e serviamo noi, quindi la
 * verifica di integrita' e' una query SQL reale, non solo un confronto di dimensioni.
 */
object LocalAssetInstaller {
    private const val TAG = "LocalAssetInstaller"

    val DB_ASSETS = listOf(
        "tiles_vector.mbtiles" to "SELECT count(*) FROM tiles LIMIT 1",
        "tiles_raster.mbtiles" to "SELECT count(*) FROM tiles LIMIT 1",
        "glyphs.db" to "SELECT count(*) FROM glyphs LIMIT 1",
    )

    fun installIfNeeded(context: Context, onDone: () -> Unit) {
        Thread {
            for ((assetName, checkQuery) in DB_ASSETS) {
                installOne(context, assetName, checkQuery)
            }
            Handler(Looper.getMainLooper()).post { onDone() }
        }.start()
    }

    private fun installOne(context: Context, assetName: String, checkQuery: String) {
        val dest = File(context.filesDir, assetName)
        if (dest.exists() && isValidSqlite(dest, checkQuery)) {
            Log.d(TAG, "$assetName già presente e valido (${dest.length()} byte), copia saltata")
            return
        }
        val startedAt = System.currentTimeMillis()
        try {
            val assetSize = context.assets.openFd(assetName).use { it.length }
            context.assets.open(assetName).use { input ->
                dest.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 20) }
            }
            val elapsedMs = System.currentTimeMillis() - startedAt
            if (dest.length() != assetSize) {
                Log.e(TAG, "$assetName: copia incompleta, attesi $assetSize byte, copiati ${dest.length()}")
                dest.delete()
                return
            }
            if (!isValidSqlite(dest, checkQuery)) {
                Log.e(TAG, "$assetName: copiato ma non leggibile come SQLite valido")
                dest.delete()
                return
            }
            Log.d(TAG, "$assetName copiato e verificato (${dest.length()} byte) in ${elapsedMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Errore copiando $assetName", e)
            dest.delete()
        }
    }

    private fun isValidSqlite(file: File, checkQuery: String): Boolean {
        return try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(checkQuery, null).use { it.moveToFirst() }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Verifica SQLite fallita per ${file.name}", e)
            false
        }
    }
}
