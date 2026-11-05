package de.jona.domainblocker

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * XML-layout UI (layout/activity_main.xml). The activity body is wrapped in
 * a try/catch so a fresh install can never crash into a flashing/white
 * screen: any setup error is logged to crash.log and shown in the UI instead.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var switchFilter: Switch
    private lateinit var listView: ListView
    private lateinit var editDomain: EditText
    private lateinit var editRedirect: EditText
    private lateinit var crashLogView: TextView

    private val vpnRequestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            bootstrap()
        } catch (t: Throwable) {
            // Never leave the user on a crashing/flashing screen.
            logCrash(t)
            renderFallback(t)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::switchFilter.isInitialized) {
            switchFilter.isChecked = isVpnRunning()
            if (::editRedirect.isInitialized) editRedirect.setText(DomainStore.redirectUrl())
        }
    }

    private fun bootstrap() {
        setContentView(R.layout.activity_main)

        switchFilter = findViewById(R.id.switch_filter)
        listView = findViewById(R.id.list_domains)
        editDomain = findViewById(R.id.edit_domain)
        editRedirect = findViewById(R.id.edit_redirect)
        crashLogView = findViewById(R.id.crash_log)
        val btnAdd = findViewById<Button>(R.id.btn_add)
        val btnSave = findViewById<Button>(R.id.btn_save)

        DomainStore.init(this)

        switchFilter.isChecked = isVpnRunning()
        switchFilter.setOnCheckedChangeListener { _, checked ->
            if (checked) startFilter() else stopFilter()
        }
        btnAdd.setOnClickListener { addDomain() }
        btnSave.setOnClickListener {
            DomainStore.setRedirectUrl(editRedirect.text.toString())
            showMessage(getString(R.string.redirect_saved))
        }

        editRedirect.setText(DomainStore.redirectUrl())
        refreshList()
        updateCrashLog()
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
        val rows = DomainStore.all().map { mapOf("domain" to it) }
        val adapter = SimpleAdapter(
            this, rows, android.R.layout.simple_list_item_1,
            arrayOf("domain"), intArrayOf(android.R.id.text1)
        )
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val d = DomainStore.all().getOrNull(position) ?: return@setOnItemClickListener
            DomainStore.remove(d)
            refreshList()
            showMessage(getString(R.string.removed_domain, d))
        }
    }

    private fun addDomain() {
        val raw = editDomain.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return
        if (DomainStore.add(raw)) {
            editDomain.setText("")
            refreshList()
            showMessage(getString(R.string.added_domain, raw))
        } else {
            Toast.makeText(this, getString(R.string.invalid_domain), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMessage(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun isVpnRunning(): Boolean {
        val cm = getSystemService(Activity.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun logCrash(t: Throwable) {
        try {
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            val text = "${System.currentTimeMillis()}: $t\n$sw\n===\n"
            File(filesDir, "crash.log").appendText(text)
            // Also write to external storage, so the log survives even if the
            // app can never open (accessible via file manager).
            try {
                val ext = getExternalFilesDir(null)
                if (ext != null) File(ext, "crash.log").appendText(text)
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
            // Logging must never mask the original problem.
        }
    }

    private fun renderFallback(t: Throwable) {
        try {
            val tv = TextView(this)
            tv.setTextColor(0xFFB00020.toInt())
            tv.setPadding(24, 48, 24, 24)
            tv.text = getString(R.string.crashlog_label) + "\n" + t
            setContentView(tv)
        } catch (_: Exception) {
            // Nothing more we can do.
        }
    }

    private fun updateCrashLog() {
        val f = File(filesDir, "crash.log")
        if (!f.exists() || f.length() == 0L) {
            crashLogView.visibility = View.GONE
            crashLogView.text = ""
            return
        }
        val text = f.readText()
        crashLogView.text = getString(R.string.crashlog_label) + "\n" +
            (if (text.length > 4000) text.takeLast(4000) else text)
        crashLogView.visibility = View.VISIBLE
        crashLogView.setOnClickListener {
            f.delete()
            updateCrashLog()
            showMessage(getString(R.string.crashlog_cleared))
        }
    }
}
