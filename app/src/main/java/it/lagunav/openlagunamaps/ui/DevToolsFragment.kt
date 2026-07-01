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
import androidx.lifecycle.lifecycleScope
import it.lagunav.openlagunamaps.databinding.FragmentDevtoolsBinding
import it.lagunav.openlagunamaps.engine.SimulatedPositionProvider
import it.lagunav.openlagunamaps.engine.SimulatorHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Dev Tools = MapFragment (debugMode=true) embedded + overlay debug.
 *
 * Differenze rispetto a Mappa:
 * - Posizione sempre simulata (joystick sempre visibile, nessun GPS reale)
 * - Layer extra visibili: no-go (rosso), bypass (arancio/viola), gate (verde)
 * - Pannello debug con spinner modalità e bottoni specifici per tool
 * - Long-tap sulla mappa + [Imposta Fine] per testare percorsi da qualunque punto
 */
class DevToolsFragment : Fragment() {

    private var _binding: FragmentDevtoolsBinding? = null
    private val binding get() = _binding!!

    private var simProvider: SimulatedPositionProvider? = null
    private var childMap: MapFragment? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDevtoolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Embedded MapFragment con debugMode=true
        if (savedInstanceState == null) {
            val map = MapFragment().apply { debugMode = true }
            childMap = map
            childFragmentManager.beginTransaction()
                .replace(binding.mapContainerDev.id, map)
                .commit()
        } else {
            childMap = childFragmentManager.findFragmentById(binding.mapContainerDev.id) as? MapFragment
        }

        setupSpinner()
        setupButtons()

        // Il simulatore parte automaticamente: il joystick è sempre visibile in Dev Tools
        startSimulator()
    }

    // =================================================================
    // SPINNER MODALITÀ
    // =================================================================

    private fun setupSpinner() {
        val modes = arrayOf(
            "Calcolo Percorso",
            "Test Punte (Tips)",
            "Zone Mare/Laguna"
        )
        binding.spinnerDevMode.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, modes)

        binding.spinnerDevMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                binding.groupRouteButtons.visibility = if (position == 0) View.VISIBLE else View.GONE
                binding.btnTestTips.visibility       = if (position == 1) View.VISIBLE else View.GONE
                when (position) {
                    2 -> binding.tvDevStatus.text = "Blu=mare / Giallo=laguna (layer zone attivi in debug)"
                    else -> Unit
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // =================================================================
    // BOTTONI SPECIFICI PER MODALITÀ
    // =================================================================

    private fun setupButtons() {
        // Modalità Percorso: imposta la destinazione al centro camera attuale
        binding.btnSetEnd.setOnClickListener {
            val center = childMap?.cameraCenter() ?: run {
                binding.tvDevStatus.text = "Mappa non ancora pronta"
                return@setOnClickListener
            }
            childMap?.setDestinationAndRoute(center)
            binding.tvDevStatus.text = "Destinazione: %.5f, %.5f".format(center.latitude, center.longitude)
        }

        // Cancella percorso attivo
        binding.btnCancelRouteDev.setOnClickListener {
            childMap?.cancelRoute()
            binding.tvDevStatus.text = "Percorso cancellato"
        }

        // Test Tips: calcola percorsi verso le 6 bocche di porto — BACKGROUND THREAD
        binding.btnTestTips.setOnClickListener {
            val engine = childMap?.routingEngine ?: return@setOnClickListener
            val pos    = childMap?.lastGpsLocation?.let { LatLng(it.latitude, it.longitude) } ?: run {
                binding.tvDevStatus.text = "Posizione non disponibile (muovi la barca)"
                return@setOnClickListener
            }
            binding.tvDevStatus.text = "Calcolo tips..."
            viewLifecycleOwner.lifecycleScope.launch {
                val results = withContext(Dispatchers.Default) { engine.pathsToTips(pos) }
                val sb = StringBuilder("Tips dalla posizione corrente:\n")
                results.forEachIndexed { i, r ->
                    if (r.path != null) {
                        val d = engine.calculateTotalDistance(r.path)
                        sb.append("Tip ${i+1}: %.2f km | %d min [PERCORSO REALE]\n".format(
                            d / 1000.0, engine.calculateEstimatedTimeMinutes(r.path)
                        ))
                    } else if (r.estimatedSeconds > 0) {
                        sb.append("Tip ${i+1}: ~${(r.estimatedSeconds / 60).toInt()} min [stima]\n")
                    } else {
                        sb.append("Tip ${i+1}: ${r.error}\n")
                    }
                }
                binding.tvDevStatus.text = sb.toString()
            }
        }
    }

    // =================================================================
    // SIMULATORE — SEMPRE ATTIVO IN DEV TOOLS
    // =================================================================

    private fun startSimulator() {
        val map = childMap
        val sim = SimulatedPositionProvider().apply {
            // Punto di partenza: ultima posizione nota oppure centro di Venezia
            map?.lastGpsLocation?.let { setPosition(it.latitude, it.longitude) }
                ?: setPosition(45.4337, 12.3350)
        }
        simProvider = sim
        SimulatorHub.provider = sim

        sim.start { location ->
            SimulatorHub.notify(location)
            onSimFix(location)
        }

        binding.joystick.onMove = { normX, normY, magnitude ->
            val joyBearing = Math.toDegrees(atan2(normX.toDouble(), -normY.toDouble())).toFloat()
            val camBearing = map?.mapLibreMap()?.cameraPosition?.bearing?.toFloat() ?: 0f
            val absBearing = (joyBearing + camBearing + 360f) % 360f
            sim.setMovement(absBearing, magnitude * 25f)
        }
    }

    private fun stopSimulator() {
        simProvider?.stop()
        SimulatorHub.provider = null
        simProvider = null
        binding.joystick.onMove = null
    }

    private fun onSimFix(location: Location) {
        val engine  = childMap?.routingEngine ?: return
        val pos     = LatLng(location.latitude, location.longitude)
        val speedKn = location.speed * 3600f / 1852f
        val limitKn = engine.getMaxSpeedKnotsAt(pos)
        val limitStr = if (limitKn != null) "Lim %.0f kn".format(limitKn) else "—"
        val isAtSea  = engine.isAtSea(pos)
        val dist     = engine.distanceToNearestCanalMeters(pos)
        val distStr  = if (dist < Double.MAX_VALUE / 2) "%.0f m canal".format(dist) else "—"
        val overLim  = limitKn != null && speedKn > limitKn

        val line = "%.5f, %.5f  [${if (isAtSea) "MARE" else "LAGUNA"}]\n%.1f kn  $limitStr  ${"%d°".format(location.bearing.roundToInt())}  $distStr".format(
            location.latitude, location.longitude, speedKn
        )
        // Aggiorna status solo nella modalità "Percorso" (non sovrascrivere info Tips/Zone)
        if (binding.spinnerDevMode.selectedItemPosition == 0) {
            binding.tvDevStatus.text = line
            binding.tvDevStatus.setTextColor(
                if (overLim) android.graphics.Color.parseColor("#FF4444")
                else android.graphics.Color.parseColor("#FF00FF")
            )
        }

        Log.d("SimDebug", "pos=${location.latitude},${location.longitude} " +
                "spd=${speedKn}kn brg=${location.bearing.roundToInt()}° " +
                "atSea=$isAtSea distCanal=${dist.toInt()}m overLimit=$overLim")
    }

    // =================================================================
    // LIFECYCLE
    // =================================================================

    override fun onPause() {
        super.onPause()
        // Teniamo il simulatore attivo via SimulatorHub anche se DevTools è in background
        // (MapFragment in Mappa lo ascolta via SimulatorHub.addListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopSimulator()
        _binding = null
    }
}
