package de.jona.domainblocker

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe, SharedPreferences-backed store for the blocked-domain list and
 * the redirect URL. The VPN service reads only from memory; the UI persists
 * every change, so the service picks it up without a restart.
 */
object DomainStore {
    const val ACTION_DOMAINS_CHANGED = "de.jona.domainblocker.action.DOMAINS_CHANGED"

    private const val PREFS = "domain_blocker"
    private const val KEY_DOMAINS = "domains"
    private const val KEY_REDIRECT = "redirect_url"
    private const val KEY_FILTER_ENABLED = "filter_enabled"

    const val DEFAULT_REDIRECT = "https://de.wikipedia.org/wiki/Fokus"

    private val domains = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var domainSnapshot: Set<String> = emptySet()
    private lateinit var prefs: SharedPreferences

    @Volatile
    var version: Long = 0
        private set
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        domains.clear()
        domains.addAll(prefs.getStringSet(KEY_DOMAINS, emptySet()) ?: emptySet())
        publishSnapshot()
    }

    fun reload(context: Context) {
        init(context)
    }

    /** True if [domain] (or a subdomain of it) is on the blocklist. Suffix match. */
    fun isBlocked(domain: String): Boolean = DomainMatcher.isBlocked(domain, domainSnapshot)

    fun all(): List<String> = domainSnapshot.sorted()

    /** Normalizes input like "https://www.Instagram.com/foo" -> "instagram.com". */
    fun add(raw: String): Boolean {
        val host = DomainMatcher.normalize(raw) ?: return false
        if (!domains.add(host)) return false
        publishSnapshot()
        persist()
        broadcastChanged()
        return true
    }

    fun remove(domain: String) {
        if (domains.remove(domain)) {
            publishSnapshot()
            persist()
            broadcastChanged()
        }
    }

    fun redirectUrl(): String =
        prefs.getString(KEY_REDIRECT, DEFAULT_REDIRECT) ?: DEFAULT_REDIRECT

    fun setRedirectUrl(url: String) {
        prefs.edit().putString(KEY_REDIRECT, url.trim().ifEmpty { DEFAULT_REDIRECT }).apply()
    }

    /** Persisted intent: the user wants the filter ON (for self-heal + boot). */
    fun setFilterEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FILTER_ENABLED, enabled).apply()
    }

    fun isFilterEnabled(): Boolean = prefs.getBoolean(KEY_FILTER_ENABLED, false)

    private fun persist() {
        prefs.edit().putStringSet(KEY_DOMAINS, domains.toSet()).apply()
    }

    private fun broadcastChanged() {
        appContext.sendBroadcast(
            Intent(ACTION_DOMAINS_CHANGED)
                .setPackage(appContext.packageName)
                .putExtra("version", version)
        )
    }

    private fun publishSnapshot() {
        domainSnapshot = domains.toSet()
        version++
    }
}
