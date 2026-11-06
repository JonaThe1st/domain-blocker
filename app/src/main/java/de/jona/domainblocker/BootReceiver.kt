package de.jona.domainblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts the filter service after a device reboot or an app update,
 * but only if the user had the filter switched ON before.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        DomainStore.init(context)
        if (!DomainStore.isFilterEnabled()) return

        val serviceIntent = Intent(context, BlockVpnService::class.java)
        try {
            context.startForegroundService(serviceIntent)
        } catch (_: Exception) {
            try {
                context.startService(serviceIntent)
            } catch (_: Exception) {
                // Foreground service start may be restricted right after
                // boot on some Android versions; the self-heal in
                // MainActivity covers the rest.
            }
        }
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON"
        )
    }
}
