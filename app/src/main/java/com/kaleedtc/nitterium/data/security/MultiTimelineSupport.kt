package com.kaleedtc.nitterium.data.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Nitters that refuse multi-account timelines ("Search disabled"). They are set aside
 * for a while so Spread stops assigning chunks to them on every load.
 */
object MultiTimelineSupport {
    const val SET_ASIDE_MS = 6 * 60 * 60 * 1000L

    private val setAsideUntil = ConcurrentHashMap<String, Long>()

    /** Only meaningful for a page that is neither a timeline nor a bot check. */
    fun looksDisabled(body: String): Boolean = body.contains("search disabled", ignoreCase = true)

    fun setAside(host: String, nowMs: Long = System.currentTimeMillis()) {
        key(host)?.let { setAsideUntil[it] = nowMs + SET_ASIDE_MS }
    }

    fun isSetAside(host: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val key = key(host) ?: return false
        val until = setAsideUntil[key] ?: return false
        if (nowMs < until) return true
        setAsideUntil.remove(key, until)
        return false
    }

    fun clear() = setAsideUntil.clear()

    private fun key(host: String): String? =
        host.trim().trimEnd('/').lowercase().takeIf { it.isNotBlank() }
}
