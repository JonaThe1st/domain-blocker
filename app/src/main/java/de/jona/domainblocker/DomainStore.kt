package de.jona.domainblocker

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe, SharedPreferences-backed store for the blocked-domain list and
 * the redirect URL. The VPN service reads only from memory; the UI persists
 * every change, so the service picks it up without a restart.
 */
object DomainStore {

    private const val PREFS = "domain_blocker"
    private const val KEY_DOMAINS = "domains"
    private const val KEY_REDIRECT = "redirect_url"
    private const val KEY_FILTER_ENABLED = "filter_enabled"

    const val DEFAULT_REDIRECT = "https://de.wikipedia.org/wiki/Fokus"

    private val domains = ConcurrentHashMap.newKeySet<String>()
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        domains.clear()
        domains.addAll(prefs.getStringSet(KEY_DOMAINS, emptySet()) ?: emptySet())
    }

    /** True if [domain] (or a subdomain of it) is on the blocklist. Suffix match. */
    fun isBlocked(domain: String): Boolean = DomainMatcher.isBlocked(domain, domains)

    fun all(): List<String> = domains.sorted()

    /** Normalizes input like "https://www.Instagram.com/foo" -> "instagram.com". */
    fun add(raw: String): Boolean {
        val host = DomainMatcher.normalize(raw) ?: return false
        if (!domains.add(host)) return false
        persist()
        return true
    }

    fun remove(domain: String) {
        if (domains.remove(domain)) persist()
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
}
