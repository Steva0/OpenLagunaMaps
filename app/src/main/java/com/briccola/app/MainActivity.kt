package com.briccola.app

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import android.widget.Toast
import com.google.android.material.navigation.NavigationView
import com.briccola.app.databinding.ActivityMainBinding
import com.briccola.app.ui.MapFragment
import com.briccola.app.ui.WeatherFragment
import com.briccola.app.ui.SettingsFragment
import android.content.Intent
import android.net.Uri
import com.briccola.app.ui.AboutFragment
import com.briccola.app.ui.DonateFragment
import com.briccola.app.ui.DevToolsFragment
import com.briccola.app.engine.LocalAssetInstaller
import com.briccola.app.engine.LocalTileServer

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentFragment: Fragment? = null
    private var isMapReady = false
    private var lastBackPressMs = 0L

    /** Mostra il popup di consenso alla privacy alla primissima apertura (una sola volta,
     *  persistito in SharedPreferences). Se l'utente accetta, prosegue con [onContinue]; se
     *  rifiuta, chiude l'app — non esiste una modalità "ridotta" senza i permessi di base che
     *  l'app usa (posizione, mappa), quindi non avrebbe senso proseguire comunque. */
    private fun showPrivacyConsentIfNeeded(onContinue: () -> Unit) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PRIVACY_ACCEPTED, false)) {
            onContinue()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_privacy_consent, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // Sfondo trasparente per permettere alla CardView di gestire gli angoli arrotondati
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        dialogView.findViewById<android.view.View>(R.id.btn_accept).setOnClickListener {
            prefs.edit().putBoolean(KEY_PRIVACY_ACCEPTED, true).apply()
            dialog.dismiss()
            onContinue()
        }

        dialogView.findViewById<android.view.View>(R.id.btn_read_more).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)))
        }

        dialogView.findViewById<android.view.View>(R.id.btn_exit).setOnClickListener {
            finish()
        }

        dialog.show()
    }

    companion object {
        private const val KEY_PRIVACY_ACCEPTED = "privacy_policy_accepted"
        const val PRIVACY_POLICY_URL = "https://steva0.github.io/Briccola/privacy-policy.html"
    }

    /**
     * Apre il drawer laterale. Chiamata dai fragment (es. MapFragment) 
     * che integrano il pulsante menu nella propria UI.
     */
    fun openDrawer() {
        binding.drawerLayout.openDrawer(GravityCompat.START)
    }

    /**
     * Segnala che la mappa (o l'inizializzazione principale) è completata,
     * permettendo alla splash screen di sparire.
     */
    fun setReady() {
        isMapReady = true
    }

    /**
     * Cambia la voce di menu SENZA ricreare dei fragment già visti (evita di reinflate la
     * MapView/lo stile MapLibre a ogni cambio, che è lento): il primo accesso li crea con
     * add(), i successivi li recuperano per tag e li mostrano di nuovo con show()/hide().
     * Il fragment che esce di scena resta vivo ma nascosto in background.
     */
    private fun showFragment(itemId: Int, title: CharSequence?, factory: () -> Fragment) {
        val tag = "menu_fragment_$itemId"
        val fm = supportFragmentManager
        val transaction = fm.beginTransaction()
        currentFragment?.let { if (it.tag != tag) transaction.hide(it) }

        val existing = fm.findFragmentByTag(tag)
        if (existing != null) {
            transaction.show(existing)
            currentFragment = existing
        } else {
            val newFragment = factory()
            transaction.add(R.id.fragment_container, newFragment, tag)
            currentFragment = newFragment
        }
        transaction.commit()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()

        // Mantieni la splash screen finché la mappa non è pronta o finché non scatta un timeout (2s)
        splashScreen.setKeepOnScreenCondition { !isMapReady }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ isMapReady = true }, 2000L)

        // Personalizzazione dell'uscita della splash screen per un effetto "premium"
        splashScreen.setOnExitAnimationListener { splashScreenView ->
            val iconView = splashScreenView.iconView

            // Animazione di "scatto verso l'alto" con dissolvenza
            val translationY = android.animation.ObjectAnimator.ofFloat(
                iconView,
                android.view.View.TRANSLATION_Y,
                0f,
                -iconView.height.toFloat() * 1.5f
            )
            
            val alpha = android.animation.ObjectAnimator.ofFloat(
                iconView,
                android.view.View.ALPHA,
                1f,
                0f
            )

            val exitSet = android.animation.AnimatorSet()
            exitSet.playTogether(translationY, alpha)
            exitSet.duration = 500L
            exitSet.interpolator = android.view.animation.AnticipateInterpolator()
            
            exitSet.doOnEnd { splashScreenView.remove() }
            exitSet.start()

            // Scomparsa fluida dello sfondo azzurro
            splashScreenView.view.animate()
                .alpha(0f)
                .setDuration(300L)
                .setStartDelay(100L)
                .start()
        }

        // Sempre tema giorno (modalità notte rimossa): forzato prima di setContentView, così
        // il tema resta coerente indipendentemente dalla modalità scura di sistema.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Nascondi Strumenti Dev per impostazione predefinita
        val devToolsItem = binding.navView.menu.findItem(R.id.nav_devtools)
        devToolsItem.isVisible = false

        // Listener per abilitare gli strumenti dev con un long click sul logo
        val headerView = binding.navView.getHeaderView(0)
        val logo = headerView.findViewById<android.widget.ImageView>(R.id.nav_header_logo)
        logo.setOnLongClickListener {
            devToolsItem.isVisible = !devToolsItem.isVisible
            val status = if (devToolsItem.isVisible) "abilitati" else "disabilitati"
            Toast.makeText(this, "Strumenti Dev $status", Toast.LENGTH_SHORT).show()
            true
        }

        binding.navView.setNavigationItemSelectedListener { menuItem ->
            val factory: (() -> Fragment)? = when (menuItem.itemId) {
                R.id.nav_map -> ({ MapFragment() })
                R.id.nav_weather -> ({ WeatherFragment() })
                R.id.nav_settings -> ({ SettingsFragment() })
                R.id.nav_devtools -> ({ DevToolsFragment() })
                R.id.nav_about -> ({ AboutFragment() })
                R.id.nav_donate -> ({ DonateFragment() })
                else -> null
            }

            factory?.let { showFragment(menuItem.itemId, menuItem.title, it) }

            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        // Gestione tasto back: prima chiude eventuali overlay aperti nella mappa (dettaglio
        // punto, salva punto, pianificazione percorso, luoghi salvati, ricerca), poi torna alla
        // Mappa se si è su un'altra voce di menu, e solo se già sulla Mappa "vuota" chiede
        // conferma (doppio tocco) prima di uscire dall'app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val drawer = binding.drawerLayout
                val mapFragment = currentFragment as? MapFragment

                when {
                    drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawer(GravityCompat.START)
                    mapFragment != null && mapFragment.handleBackPress() -> { /* overlay chiuso */ }
                    mapFragment == null -> {
                        showFragment(R.id.nav_map, "Mappa") { MapFragment() }
                        binding.navView.setCheckedItem(R.id.nav_map)
                    }
                    else -> {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPressMs < 2000L) {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        } else {
                            lastBackPressMs = now
                            Toast.makeText(this@MainActivity, "Premi di nuovo per uscire", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })

        // Mostra il consenso privacy e poi carica la mappa. La creazione del MapFragment va
        // rimandata a dopo la copia del pacchetto offline precotto (se non già presente):
        // MapLibre apre/crea il proprio database non appena la prima MapView viene istanziata,
        // quindi la copia deve avvenire prima, non dopo. Il server locale (LocalTileServer) va
        // avviato subito dopo, prima che lo stile venga caricato.
        showPrivacyConsentIfNeeded {
            LocalAssetInstaller.installIfNeeded(applicationContext) {
                LocalTileServer.startIfNeeded(applicationContext)
                showFragment(R.id.nav_map, "Mappa") { MapFragment() }
                binding.navView.setCheckedItem(R.id.nav_map)
            }
        }
    }
}
