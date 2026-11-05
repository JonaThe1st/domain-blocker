package de.jona.domainblocker

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * STAGE TEST build: shows a stage label on screen before each step.
 * The last visible stage tells us exactly where the process dies, so the
 * app can be debugged without logcat. Any Java exception is shown inline.
 */
class MainActivity : Activity() {

    private fun stage(text: String) {
        val tv = TextView(this)
        tv.text = "DB-Test: $text"
        tv.textSize = 20f
        tv.setPadding(40, 80, 40, 40)
        setContentView(tv)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            stage("1/6 onCreate")
            super.onCreate(savedInstanceState)

            stage("2/6 vor Layout")
            setContentView(R.layout.activity_main)

            stage("3/6 Layout OK")
            val sw = findViewById<android.widget.Switch>(R.id.switch_filter)
            val listView = findViewById<android.widget.ListView>(R.id.list_domains)
            val editDomain = findViewById<android.widget.EditText>(R.id.edit_domain)
            val editRedirect = findViewById<android.widget.EditText>(R.id.edit_redirect)
            val btnAdd = findViewById<android.widget.Button>(R.id.btn_add)
            val btnSave = findViewById<android.widget.Button>(R.id.btn_save)
            stage("4/6 Views OK")

            DomainStore.init(this)
            stage("5/6 Store OK")

            sw.isChecked = false
            btnAdd.setOnClickListener { }
            btnSave.setOnClickListener { }
            listView.adapter = null
            editDomain.setText("")
            editRedirect.setText(DomainStore.redirectUrl())
            stage("6/6 fertig")
        } catch (t: Throwable) {
            val tv = TextView(this)
            tv.text = "EXC: $t"
            tv.textSize = 14f
            tv.setPadding(40, 80, 40, 40)
            setContentView(tv)
        }
    }
}
