package de.jona.domainblocker

/** Pure matching/normalization logic, JVM-testable. */
object DomainMatcher {

    /** Suffix match: true if [host] equals or is a subdomain of any blocked entry. */
    fun isBlocked(host: String, blocked: Collection<String>): Boolean {
        val d = host.trim().lowercase().removeSuffix(".")
        if (d.isEmpty()) return false
        for (b in blocked) {
            if (d == b || d.endsWith(".$b")) return true
        }
        return false
    }

    /** Normalizes input like "https://www.Instagram.com/foo" -> "instagram.com". */
    fun normalize(raw: String): String? {
        var s = raw.trim().lowercase()
            .removePrefix("https://").removePrefix("http://")
        var host = s.substringBefore('/').substringBefore(':')
        if (host.startsWith("www.")) host = host.removePrefix("www.")
        host = host.removeSuffix(".")
        if (host.isEmpty() || host.contains(' ') || !host.contains('.')) return null
        return host
    }
}
