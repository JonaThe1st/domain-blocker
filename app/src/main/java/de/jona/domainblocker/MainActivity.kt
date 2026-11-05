package de.jona.domainblocker

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * SMOKE TEST build: deliberately minimal activity with a single TextView.
 * Purpose: prove that this APK's dex/classes load and start on the device.
 * If this does NOT appear, the problem is in the build/toolchain itself.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = "Domain Blocker v0.3.0-smoke OK"
        tv.textSize = 22f
        tv.setPadding(48, 96, 48, 48)
        setContentView(tv)
    }
}
