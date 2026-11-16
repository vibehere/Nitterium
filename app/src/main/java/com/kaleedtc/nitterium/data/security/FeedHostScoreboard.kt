package com.kaleedtc.nitterium.data.security

/**
 * Session-wide ranking of Nitter/Teapot hosts by recent success and latency so
 * feed fetches try faster hosts first.
 */
object FeedHostScoreboard {
    private data class Stats(
        var successes: Int = 0,
        var failures: Int = 0,
        var avgMs: Double = 0.0
    )

    private val lock = Any()
    private val stats = linkedMapOf<String, Stats>()

    fun recordSuccess(host: String, elapsedMs: Long) {
        val key = normalize(host) ?: return
        val ms = elapsedMs.coerceAtLeast(0L).toDouble()
        synchronized(lock) {
            val row = stats.getOrPut(key) { Stats() }
            row.successes += 1
            row.avgMs = if (row.successes == 1) ms else (row.avgMs * 0.7) + (ms * 0.3)
        }
    }

    fun recordFailure(host: String, elapsedMs: Long = 0L) {
        val key = normalize(host) ?: return
        val ms = elapsedMs.coerceAtLeast(0L).toDouble()
        synchronized(lock) {
            val row = stats.getOrPut(key) { Stats() }
            row.failures += 1
            if (ms > 0) {
                row.avgMs = if (row.avgMs <= 0) ms else (row.avgMs * 0.8) + (ms * 0.2)
            } else if (row.avgMs <= 0) {
                row.avgMs = 30_000.0
            }
        }
    }

    /** Stable sort: more reliable + faster hosts first; unknown hosts keep input order. */
    fun rank(hosts: Collection<String>): List<String> {
        val indexed = hosts.mapIndexed { index, host -> Triple(index, host, normalize(host)) }
        return synchronized(lock) {
            indexed.sortedWith(
                compareBy<Triple<Int, String, String?>> { (_, _, key) ->
                    val row = key?.let { stats[it] }
                    when {
                        row == null -> 1
                        row.successes == 0 && row.failures > 0 -> 2
                        else -> 0
                    }
                }.thenBy { (_, _, key) ->
                    val row = key?.let { stats[it] } ?: return@thenBy Double.MAX_VALUE / 2
                    if (row.successes == 0) Double.MAX_VALUE / 2 else row.avgMs
                }.thenBy { it.first }
            ).map { it.second }
        }
    }

    fun resetForTests() {
        synchronized(lock) { stats.clear() }
    }

    private fun normalize(host: String): String? {
        val trimmed = host.trim().trimEnd('/')
        return trimmed.takeIf { it.isNotBlank() }?.lowercase()
    }
}
