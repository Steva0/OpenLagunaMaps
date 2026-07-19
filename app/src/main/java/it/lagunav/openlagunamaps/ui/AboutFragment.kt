package it.lagunav.openlagunamaps.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import it.lagunav.openlagunamaps.R

class AboutFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_about, container, false)
        
        root.findViewById<Button>(R.id.btn_github)?.setOnClickListener {
            openUrl("https://github.com/Steva0/Steva0")
        }
        
        root.findViewById<Button>(R.id.btn_cv)?.setOnClickListener {
            openUrl("https://europa.eu/europass/eportfolio/api/eprofile/shared-profile/michele-stevanin/5f317bb5-67f7-40f7-9dd7-1027464d0870?view=html")
        }

        root.findViewById<View>(R.id.btn_menu)?.setOnClickListener {
            (activity as? it.lagunav.openlagunamaps.MainActivity)?.openDrawer()
        }
        
        return root
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}