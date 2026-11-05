package de.jona.domainblocker

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * STAGE TEST v2: the full bootstrap runs for real; the stage label is
 * written into the layout's own title TextView so the content view is
 * never replaced. The last visible stage pinpoints any crash location
 * (or any Java exception, shown inline).
 */
class MainActivity : Activity() {

    private var titleView: TextView? = null

    private fun stage(text: String) {
        val tv = titleView
        if (tv != null) {
            tv.text = "DB-Test: $text"
        } else {
            val fallback = TextView(this)
            fallback.text = "DB-Test: $text"
            fallback.textSize = 20f
            fallback.setPadding(40, 80, 40, 40)
            setContentView(fallback)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            stage("1/7 onCreate")
            super.onCreate(savedInstanceState)

            stage("2/7 vor Layout")
            setContentView(R.layout.activity_main)
            titleView = findViewById(R.id.title)

            stage("3/7 Layout OK")
            val sw = findViewById<android.widget.Switch>(R.id.switch_filter)
            val listView = findViewById<android.widget.ListView>(R.id.list_domains)
            val editDomain = findViewById<android.widget.EditText>(R.id.edit_domain)
            val editRedirect = findViewById<android.widget.EditText>(R.id.edit_redirect)
            val btnAdd = findViewById<android.widget.Button>(R.id.btn_add)
            val btnSave = findViewById<android.widget.Button>(R.id.btn_save)

            stage("4/7 Views OK")

            DomainStore.init(this)

            stage("5/7 Store OK")

            sw.isChecked = false
            btnAdd.setOnClickListener { }
            btnSave.setOnClickListener { }
            listView.adapter = null
            editDomain.setText("")
            editRedirect.setText(DomainStore.redirectUrl())

            stage("6/7 UI OK")

            // real bootstrap steps
            refreshList()
            updateCrashLog()

            stage("7/7 fertig")
        } catch (t: Throwable) {
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            val tv = titleView ?: TextView(this)
            tv.text = "EXC: $t\n$sw"
            tv.textSize = 14f
            tv.setPadding(40, 80, 40, 40)
            setContentView(tv)
        }
    }

    private fun refreshList() {
        val listView = findViewById<android.widget.ListView>(R.id.list_domains)
        val rows = DomainStore.all().map { mapOf("domain" to it) }
        val adapter = android.widget.SimpleAdapter(
            this, rows, android.R.layout.simple_list_item_1,
            arrayOf("domain"), intArrayOf(android.R.id.text1)
        )
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val d = DomainStore.all().getOrNull(position) ?: return@setOnItemClickListener
            DomainStore.remove(d)
            refreshList()
        }
    }

    private fun updateCrashLog() {
        val f = java.io.File(filesDir, "crash.log")
        if (!f.exists() || f.length() == 0L) return
        val text = f.readText()
        val tv = titleView
        if (tv != null && tv.text.toString().startsWith("DB-Test")) {
            tv.text = "DB-Test: 7/7 fertig\n\nLetzter Crash:\n${text.take(2000)}"
        }
    }
}
