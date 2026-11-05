package de.jona.domainblocker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar

/**
 * Main UI: activation switch (VPN permission flow), domain blocklist editing,
 * and the redirect target URL.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var switchFilter: MaterialSwitch
    private lateinit var listView: ListView
    private lateinit var adapter: DomainAdapter
    private lateinit var editDomain: EditText
    private lateinit var editRedirect: EditText

    private val vpnRequestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DomainStore.init(this)
        buildUi()

        switchFilter.isChecked = isVpnRunning(this)
        switchFilter.setOnCheckedChangeListener { _, checked ->
            if (checked) startFilter() else stopFilter()
        }
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        switchFilter.isChecked = isVpnRunning(this)
        editRedirect.setText(DomainStore.redirectUrl())
    }

    private fun startFilter() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, vpnRequestCode)
        } else {
            startVpnService()
        }
    }

    private fun stopFilter() {
        stopService(Intent(this, BlockVpnService::class.java))
    }

    private fun startVpnService() {
        startService(Intent(this, BlockVpnService::class.java))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequestCode) {
            if (resultCode == Activity.RESULT_OK) {
                startVpnService()
            } else {
                switchFilter.isChecked = false
                showMessage(getString(R.string.vpn_permission_denied))
            }
        }
    }

    private fun refreshList() {
        adapter.clear()
        adapter.addAll(DomainStore.all())
    }

    private fun showMessage(text: String) {
        Snackbar.make(findViewById(android.R.id.content), text, Snackbar.LENGTH_SHORT).show()
    }

    private fun buildUi() {
        val dp = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
        }

        // Header row: title + how-it-works hint
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        })

        // Activation switch
        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (12 * dp).toInt(), 0, (12 * dp).toInt())
        }
        switchFilter = MaterialSwitch(this).apply {
            id = View.generateViewId()
        }
        switchRow.addView(switchFilter)
        switchRow.addView(TextView(this).apply {
            text = getString(R.string.activate_label)
            textSize = 16f
            setPadding((12 * dp).toInt(), 0, 0, 0)
        })
        root.addView(switchRow)

        // Add-domain row
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        editDomain = EditText(this).apply {
            hint = getString(R.string.domain_hint)
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        addRow.addView(editDomain)
        addRow.addView(Button(this).apply {
            text = getString(R.string.add_button)
            setOnClickListener { addDomain() }
        })
        root.addView(addRow)

        // List header + list
        root.addView(TextView(this).apply {
            text = getString(R.string.list_label)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (16 * dp).toInt(), 0, (4 * dp).toInt())
        })
        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (240 * dp).toInt()
            )
        }
        root.addView(listView)

        // Redirect target
        root.addView(TextView(this).apply {
            text = getString(R.string.redirect_label)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (16 * dp).toInt(), 0, (4 * dp).toInt())
        })
        val redirectRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        editRedirect = EditText(this).apply {
            hint = getString(R.string.redirect_hint)
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        redirectRow.addView(editRedirect)
        redirectRow.addView(Button(this).apply {
            text = getString(R.string.save_button)
            setOnClickListener {
                DomainStore.setRedirectUrl(editRedirect.text.toString())
                showMessage(getString(R.string.redirect_saved))
            }
        })
        root.addView(redirectRow)

        root.addView(TextView(this).apply {
            text = getString(R.string.blocking_hint)
            textSize = 12f
            setPadding(0, (12 * dp).toInt(), 0, 0)
        })

        setContentView(root)

        adapter = DomainAdapter(this, mutableListOf()) { domain ->
            DomainStore.remove(domain)
            refreshList()
            showMessage(getString(R.string.removed_domain, domain))
        }
        listView.adapter = adapter
    }

    private fun addDomain() {
        val raw = editDomain.text.toString().trim()
        if (raw.isEmpty()) return
        if (DomainStore.add(raw)) {
            editDomain.setText("")
            refreshList()
            showMessage(getString(R.string.added_domain, raw.trim()))
        } else {
            Toast.makeText(this, getString(R.string.invalid_domain), Toast.LENGTH_SHORT).show()
        }
    }

    private fun isVpnRunning(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
    }

    /** Simple two-line row: domain + delete button, empty state text. */
    inner class DomainAdapter(
        context: Context,
        items: MutableList<String>,
        private val onDelete: (String) -> Unit
    ) : ArrayAdapter<String>(context, 0, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val domain = getItem(position) ?: return View(context)
            val inflater = LayoutInflater.from(context)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            row.addView(TextView(context).apply {
                text = domain
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val del = ImageButton(context).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                contentDescription = getString(R.string.delete_domain)
                background = null
                setOnClickListener { onDelete(domain) }
            }
            row.addView(del)
            return row
        }
    }
}
