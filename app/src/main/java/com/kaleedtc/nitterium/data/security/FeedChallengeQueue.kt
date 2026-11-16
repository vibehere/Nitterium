package com.kaleedtc.nitterium.data.security

/**
 * Feed chunks that failed only because a Nitter served a bot check (Anubis, Gandalf,
 * Cloudflare). They wait per host until the user passes the check in a browser and
 * are then retried on that same host, so Spread keeps its account → Nitter mapping.
 */
class FeedChallengeQueue {
    private val pending = linkedMapOf<String, MutableList<FeedChunkAssignment>>()

    val hosts: List<String> get() = pending.keys.toList()

    fun add(host: String, chunk: FeedChunkAssignment) {
        val key = normalize(host) ?: return
        val chunks = pending.getOrPut(key) { mutableListOf() }
        if (chunks.none { it.usernames == chunk.usernames }) chunks += chunk
    }

    fun firstChunk(host: String): FeedChunkAssignment? =
        normalize(host)?.let { pending[it]?.firstOrNull() }

    fun take(host: String): List<FeedChunkAssignment> =
        normalize(host)?.let { pending.remove(it) }.orEmpty()

    fun clear() = pending.clear()

    companion object {
        fun normalize(host: String): String? =
            host.trim().trimEnd('/').lowercase().takeIf { it.isNotBlank() }

        /** Prefer the chunk's assigned Nitter when it was one of the challenged hosts. */
        fun hostFor(chunk: FeedChunkAssignment, challenged: Collection<String>): String? {
            val keys = challenged.mapNotNull { normalize(it) }
            val assigned = normalize(chunk.instanceUrl)
            return assigned?.takeIf { it in keys } ?: keys.firstOrNull()
        }
    }
}
