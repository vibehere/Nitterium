package com.kaleedtc.nitterium.data.security

import com.kaleedtc.nitterium.data.frontend.FrontendAdapter
import com.kaleedtc.nitterium.data.frontend.FrontendType
import kotlin.random.Random

data class FeedChunkAssignment(
    val instanceUrl: String,
    val usernames: List<String>,
    val url: String
)

data class FeedPrivacyPlan(
    val assignments: List<FeedChunkAssignment>
)

object FeedPrivacyPartition {
    fun plan(
        usernames: List<String>,
        instances: List<String>,
        chunkSize: Int,
        seed: Long,
        frontend: FrontendType = FrontendType.NITTER
    ): FeedPrivacyPlan {
        require(usernames.isNotEmpty()) { "usernames must not be empty" }
        val size = chunkSize.coerceAtLeast(1)
        val pool = instances
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        require(pool.isNotEmpty()) { "instances must not be empty" }

        val shuffledUsers = usernames
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .shuffled(Random(seed))
        require(shuffledUsers.isNotEmpty()) { "usernames must not be empty" }

        val rng = Random(seed xor 0x9E3779B97F4A7C15UL.toLong())
        val adapter = FrontendAdapter.forType(frontend)
        val chunks = shuffledUsers.chunked(size)
        // Round-robin a shuffled pool so large feeds actually use many hosts,
        // instead of randomly piling most chunks onto one lucky instance.
        val rotatedPool = pool.toMutableList().also { it.shuffle(rng) }
        val assignments = chunks.mapIndexed { index, chunk ->
            val instance = rotatedPool[index % rotatedPool.size]
            val url = adapter.timelineUrls(
                instance,
                chunk,
                maxUsersPerBatch = chunk.size.coerceAtLeast(1),
                maxUrlLength = FrontendAdapter.DEFAULT_MAX_TIMELINE_URL_LENGTH
            ).single()
            FeedChunkAssignment(instanceUrl = instance, usernames = chunk, url = url)
        }
        return FeedPrivacyPlan(assignments)
    }

    /**
     * Spread peers stick to [preferred]. Failovers skip [unavailable] hosts before the
     * limit is applied and favour Nitters no other chunk uses yet, so one host does not
     * end up seeing most of the subscriptions; the shell host is the last resort.
     */
    fun peerHostTryOrder(
        preferred: String,
        pool: List<String>,
        shellInstance: String,
        failoverLimit: Int = FeedBatchAppend.PEER_FAILOVER_HOSTS,
        usedHosts: Collection<String> = emptyList(),
        unavailable: Collection<String> = emptyList()
    ): List<String> {
        val preferredHost = preferred.trim().trimEnd('/')
        if (failoverLimit <= 0) {
            return listOfNotNull(preferredHost.takeIf { it.isNotBlank() })
        }
        val preferredKey = preferredHost.lowercase()
        val shellKey = shellInstance.trim().trimEnd('/').lowercase()
        val used = usedHosts.map { it.trim().trimEnd('/').lowercase() }.toSet()
        val skipped = unavailable.map { it.trim().trimEnd('/').lowercase() }.toSet()
        val others = pool
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .filterNot { it.lowercase() == preferredKey || it.lowercase() in skipped }
        val (shell, nonShell) = others.partition { it.lowercase() == shellKey }
        val (alreadyUsed, unused) = nonShell.partition { it.lowercase() in used }
        // Keep input order within each group — do not scoreboard-rank toward one host.
        val failovers = unused + alreadyUsed + shell
        return buildList {
            if (preferredHost.isNotBlank()) add(preferredHost)
            addAll(failovers.take(failoverLimit))
        }.distinctBy { it.lowercase() }
    }
}
