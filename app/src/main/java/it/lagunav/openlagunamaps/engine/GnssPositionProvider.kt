package it.lagunav.openlagunamaps.engine

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager

/**
 * Provider GPS reale. Strategia a tre livelli per minimizzare il TTFF:
 * 1. Last known location (istantanea, potenzialmente stale) — feedback immediato.
 * 2. NETWORK_PROVIDER (1-2 secondi, imprecisa ~50-100m) — posizione rapida mentre il GPS si scalda.
 * 3. GPS_PROVIDER (10-60 secondi, precisa <10m) — sostitisce la rete una volta agganciato.
 * I fix di rete vengono ignorati dopo il primo fix GPS (accuracy < 20m).
 */
class GnssPositionProvider(private val context: Context) : PositionProvider {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var gpsListener: LocationListener? = null
    private var netListener: LocationListener? = null
    private var gotGpsFix = false

    override fun start(onFix: (Location) -> Unit) {
        gotGpsFix = false

        try {
            // 1. Ultima posizione nota: feedback visivo istantaneo
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onFix(it) }
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?.takeIf { it.accuracy < 500f }?.let { onFix(it) }

            // 2. Rete: fix rapido finché il GPS non è pronto
            val netL = LocationListener { loc ->
                if (!gotGpsFix) onFix(loc)
            }
            netListener = netL
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, netL)
            }

            // 3. GPS: preciso, una volta agganciato prende il sopravvento
            val gpsL = LocationListener { loc ->
                gotGpsFix = true
                onFix(loc)
            }
            gpsListener = gpsL
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsL)
            }
        } catch (_: SecurityException) {}
    }

    override fun stop() {
        gpsListener?.let { lm.removeUpdates(it) }
        netListener?.let { lm.removeUpdates(it) }
        gpsListener = null; netListener = null
        gotGpsFix = false
    }
}
