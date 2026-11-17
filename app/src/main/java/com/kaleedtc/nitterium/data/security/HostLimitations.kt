package com.kaleedtc.nitterium.data.security

import java.util.concurrent.ConcurrentHashMap

enum class HostLimitation {
    /** Refuses multi-account timelines ("Search disabled"). */
    MULTI_TIMELINE_DISABLED,

    /** Answers RSS requests with something other than a feed. */
    NO_RSS,

    /** Only serves pages after a JavaScript bot check. */
    NEEDS_JAVASCRIPT
}

data class LimitedHost(val host: String, val limitation: HostLimitation)

/**
 * Nitters that cannot serve the current feed mode. They are set aside for a while so
 * Spread stops assigning chunks to them on every load, and listed so the user can see why.
 */
object HostLimitations {
    const val SET_ASIDE_MS = 6 * 60 * 60 * 1000L

    private val setAsideUntil = ConcurrentHashMap<Pair<String, HostLimitation>, Long>()

    /** Only meaningful for a page that is neither a timeline nor a bot check. */
    fun looksMultiTimelineDisabled(body: String): Boolean =
        body.contains("search disabled", ignoreCase = true)

    /**
     * A reply that arrived but is not a feed. Rate limits and server errors are temporary,
     * so only client errors and non-feed documents count.
     */
    fun looksRssUnsupported(code: Int, body: String): Boolean {
        if (code == 429 || code >= 500) return false
        if (code !in 200..299) return true
        return !body.contains("<rss", ignoreCase = true) &&
            !body.contains("<feed", ignoreCase = true) &&
            !body.contains("<channel", ignoreCase = true)
    }

    fun setAside(host: String, limitation: HostLimitation, nowMs: Long = System.currentTimeMillis()) {
        key(host)?.let { setAsideUntil[it to limitation] = nowMs + SET_ASIDE_MS }
    }

    fun clearLimitation(host: String, limitation: HostLimitation) {
        key(host)?.let { setAsideUntil.remove(it to limitation) }
    }

    /**
     * Writes Settings probe results into the skip list: JS walls and hosts that
     * do not serve RSS. Timeouts and 5xx/429 are not recorded.
     */
    fun recordProbe(
        host: String,
        worksHtml: Boolean,
        challenge: Boolean,
        rssWorks: Boolean,
        rssUnsupported: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ) {
        when {
            challenge -> setAside(host, HostLimitation.NEEDS_JAVASCRIPT, nowMs)
            worksHtml -> clearLimitation(host, HostLimitation.NEEDS_JAVASCRIPT)
        }
        when {
            rssUnsupported -> setAside(host, HostLimitation.NO_RSS, nowMs)
            rssWorks -> clearLimitation(host, HostLimitation.NO_RSS)
        }
    }

    fun isSetAside(host: String, limitation: HostLimitation, nowMs: Long = System.currentTimeMillis()): Boolean {
        val key = key(host) ?: return false
        val entry = key to limitation
        val until = setAsideUntil[entry] ?: return false
        if (nowMs < until) return true
        setAsideUntil.remove(entry, until)
        return false
    }

    /** First active limitation per host in [hosts] that matters for the given mode. */
    fun limitedHosts(
        hosts: Collection<String>,
        relevant: Set<HostLimitation>,
        nowMs: Long = System.currentTimeMillis()
    ): List<LimitedHost> =
        hosts.map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .mapNotNull { host ->
                HostLimitation.entries
                    .firstOrNull { it in relevant && isSetAside(host, it, nowMs) }
                    ?.let { LimitedHost(host, it) }
            }

    fun clear() = setAsideUntil.clear()

    private fun key(host: String): String? =
        host.trim().trimEnd('/').lowercase().takeIf { it.isNotBlank() }
}
