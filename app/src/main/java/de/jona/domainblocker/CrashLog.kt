package de.jona.domainblocker

import android.content.Context
import java.io.File

/** Tiny persistent log for service lifecycle + crashes; surfaced in the UI. */
object CrashLog {
    private const val MAX_LEN = 16 * 1024

    fun write(context: Context, msg: String) {
        try {
            val f = File(context.filesDir, "crash.log")
            var existing = ""
            if (f.exists() && f.length() > 0) existing = f.readText()
            val entry = "${System.currentTimeMillis()}: $msg\n"
            val combined = existing + entry
            f.writeText(if (combined.length > MAX_LEN) combined.takeLast(MAX_LEN) else combined)
        } catch (_: Exception) {
        }
    }
}
