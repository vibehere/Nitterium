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
        val assignments = chunks.map { chunk ->
            val instance = pool[rng.nextInt(pool.size)]
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
}
