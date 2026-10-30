package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.security.FeedPrivacyPartition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedPrivacyPartitionTest {
    @Test fun chunksStayWithinSizeAndCoverEveryoneOnce() {
        val users = (1..25).map { "user$it" }
        val instances = listOf("https://a.example", "https://b.example", "https://c.example")
        val plan = FeedPrivacyPartition.plan(users, instances, chunkSize = 10, seed = 42L)
        assertEquals(3, plan.assignments.size)
        assertTrue(plan.assignments.all { it.usernames.size <= 10 })
        val all = plan.assignments.flatMap { it.usernames }.map { it.lowercase() }.sorted()
        assertEquals(users.map { it.lowercase() }.sorted(), all)
    }

    @Test fun noHostGetsEveryUserWhenPoolIsLargeEnough() {
        val users = (1..40).map { "acct$it" }
        val instances = listOf(
            "https://one.example",
            "https://two.example",
            "https://three.example",
            "https://four.example"
        )
        val plan = FeedPrivacyPartition.plan(users, instances, chunkSize = 10, seed = 7L)
        val byHost = plan.assignments.groupBy { it.instanceUrl.lowercase() }
        assertTrue(byHost.size > 1)
        byHost.values.forEach { hostChunks ->
            val count = hostChunks.sumOf { it.usernames.size }
            assertTrue("host saw $count of ${users.size}", count < users.size)
        }
    }

    @Test fun refreshSeedChangesHostMapping() {
        val users = (1..20).map { "u$it" }
        val instances = listOf("https://a.example", "https://b.example")
        val first = FeedPrivacyPartition.plan(users, instances, chunkSize = 5, seed = 1L)
        val second = FeedPrivacyPartition.plan(users, instances, chunkSize = 5, seed = 2L)
        val firstMap = first.assignments.associate { it.usernames.sorted().joinToString(",") to it.instanceUrl }
        val secondMap = second.assignments.associate { it.usernames.sorted().joinToString(",") to it.instanceUrl }
        assertTrue(firstMap != secondMap || first.assignments.map { it.usernames } != second.assignments.map { it.usernames })
    }

    @Test fun fewHostsAreReusedAcrossManyChunks() {
        val users = (1..12).map { "u$it" }
        val instances = listOf("https://only.example", "https://also.example")
        val plan = FeedPrivacyPartition.plan(users, instances, chunkSize = 1, seed = 99L)
        assertEquals(12, plan.assignments.size)
        assertTrue(plan.assignments.all { it.usernames.size == 1 })
        val byHost = plan.assignments.groupingBy { it.instanceUrl.lowercase() }.eachCount()
        assertEquals(2, byHost.size)
        assertTrue(byHost.values.all { it >= 1 })
        assertEquals(12, byHost.values.sum())
    }

    @Test fun singleHostPoolStillPlansEveryChunk() {
        val users = (1..6).map { "solo$it" }
        val plan = FeedPrivacyPartition.plan(
            users,
            listOf("https://one.example"),
            chunkSize = 2,
            seed = 3L
        )
        assertEquals(3, plan.assignments.size)
        assertTrue(plan.assignments.all { it.instanceUrl == "https://one.example" })
        assertEquals(users.map { it.lowercase() }.sorted(),
            plan.assignments.flatMap { it.usernames }.map { it.lowercase() }.sorted())
    }
}
