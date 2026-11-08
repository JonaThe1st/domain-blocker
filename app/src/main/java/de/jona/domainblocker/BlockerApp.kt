package de.jona.domainblocker

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Application: installs a global uncaught-exception handler so every crash
 * is recorded in crash.log (visible in the UI) and — when the user wanted
 * the filter ON — the VPN service is restarted via AlarmManager, which
 * survives the dying process.
 */
class BlockerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashLog.write(this, "app create")
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            CrashLog.write(this, "CRASH thread=${thread.name}: $throwable\n$sw")
            if (DomainStore.isFilterEnabled()) {
                try {
                    val am = getSystemService(AlarmManager::class.java)
                    val pi = PendingIntent.getService(
                        this, CRASH_RESTART_REQUEST,
                        Intent(this, BlockVpnService::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RESTART_DELAY_MS, pi)
                } catch (_: Exception) {
                }
            }
        }
    }

    companion object {
        private const val CRASH_RESTART_REQUEST = 4001
        private const val RESTART_DELAY_MS = 3000L
    }
}
