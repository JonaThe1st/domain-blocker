package de.jona.domainblocker

import android.app.Application
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Logs uncaught crashes to filesDir/crash.log so the UI can show the stack
 * trace on the next launch — enables debugging without logcat/device access.
 */
class BlockerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val text = "${System.currentTimeMillis()} ${thread.name}: $throwable\n$sw\n===\n"
                openFileOutput("crash.log", MODE_APPEND).use { it.write(text.toByteArray()) }
            } catch (_: Exception) {
                // Logging must never mask the original crash.
            } finally {
                Process.killProcess(Process.myPid())
            }
        }
    }
}
