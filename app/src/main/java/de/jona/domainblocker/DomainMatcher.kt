package de.jona.domainblocker

/** Pure matching/normalization logic, JVM-testable. */
object DomainMatcher {

    /** Suffix match: true if [host] equals or is a subdomain of any blocked entry. */
    fun isBlocked(host: String, blocked: Collection<String>): Boolean {
        val d = host.trim().lowercase().removeSuffix(".")
        if (d.isEmpty()) return false
        for (b in blocked) {
            val entry = b.trim().lowercase().removeSuffix(".")
            if (entry.isNotEmpty() && (d == entry || d.endsWith(".$entry"))) return true
        }
        return false
    }

    /** Normalizes input like "https://www.Instagram.com/foo" -> "instagram.com". */
    fun normalize(raw: String): String? {
        var s = raw.trim().lowercase()
            .removePrefix("https://").removePrefix("http://")
        var host = s.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        if (host.startsWith("www.")) host = host.removePrefix("www.")
        host = host.trimEnd('.')
        if (host.isEmpty() || host.any { it.isWhitespace() } || !host.contains('.') ||
            host.split('.').any { it.isEmpty() }
        ) return null
        return host
    }
}
