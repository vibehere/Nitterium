package com.kaleedtc.nitterium.data.security

/**
 * Helpers for onion-only mode: while SOCKS/Tor is on, the app may restrict
 * connections to `.onion` Nitter hosts only (no clearnet frontends).
 */
object TorOnionPreference {
    fun isOnionInstance(url: String): Boolean =
        UrlPolicy.instanceUrl(url)?.host?.let { UrlPolicy.isOnionHost(it) } == true

    /**
     * Onions resolve only through in-app SOCKS or a system Tor VPN; the selected Nitter
     * being an onion is the only sign of the latter.
     */
    fun onionReachable(socksOn: Boolean, selectedInstance: String): Boolean =
        socksOn || isOnionInstance(selectedInstance)

    fun firstOnion(candidates: Collection<String>): String? =
        candidates.firstOrNull { isOnionInstance(it) }

    fun onionsOnly(candidates: Collection<String>): List<String> =
        candidates.filter { isOnionInstance(it) }.distinctBy { it.trimEnd('/').lowercase() }

    /** When [onionOnly] is true, drop every clearnet host from the live pool. */
    fun restrictPool(candidates: Collection<String>, onionOnly: Boolean): List<String> {
        val cleaned = candidates
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        return if (onionOnly) onionsOnly(cleaned) else cleaned
    }

    /**
     * Pick a usable host for onion-only feeds: prefer [preferred] when it is onion
     * and present in [pool], otherwise the first onion in [pool].
     */
    fun resolveOnionInstance(preferred: String, pool: Collection<String>): String? {
        val onions = onionsOnly(pool)
        if (onions.isEmpty()) return null
        val key = preferred.trim().trimEnd('/').lowercase()
        return onions.firstOrNull { it.lowercase() == key } ?: onions.first()
    }
}
