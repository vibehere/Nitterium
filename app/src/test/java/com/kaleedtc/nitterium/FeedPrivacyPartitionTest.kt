package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.security.FeedBatchAppend
import com.kaleedtc.nitterium.data.security.FeedHostScoreboard
import com.kaleedtc.nitterium.data.security.FeedPrivacyPartition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FeedPrivacyPartitionTest {
    @Before
    fun resetScoreboard() {
        FeedHostScoreboard.resetForTests()
    }

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

    @Test fun largeFeedRoundRobinsAcrossPool() {
        val users = (1..100).map { "user$it" }
        val instances = (1..10).map { "https://n$it.example" }
        val plan = FeedPrivacyPartition.plan(users, instances, chunkSize = 10, seed = 11L)
        assertEquals(10, plan.assignments.size)
        val hosts = plan.assignments.map { it.instanceUrl.lowercase() }.toSet()
        assertEquals(10, hosts.size)
        val counts = plan.assignments.groupingBy { it.instanceUrl.lowercase() }.eachCount()
        assertTrue(counts.values.all { it == 1 })
    }

    @Test fun peerTryOrderPrefersAssignmentAndDoesNotScoreboardRank() {
        FeedHostScoreboard.recordSuccess("https://shell.example", 200)
        FeedHostScoreboard.recordSuccess("https://fast.example", 150)
        val order = FeedPrivacyPartition.peerHostTryOrder(
            preferred = "https://assigned.example",
            pool = listOf(
                "https://assigned.example",
                "https://shell.example",
                "https://fast.example",
                "https://other.example"
            ),
            shellInstance = "https://shell.example",
            failoverLimit = FeedBatchAppend.PEER_FAILOVER_HOSTS
        )
        assertEquals("https://assigned.example", order.first())
        assertTrue(order.contains("https://fast.example"))
        assertTrue(
            !order.contains("https://shell.example") ||
                order.indexOf("https://shell.example") == order.lastIndex
        )
        assertEquals(1 + FeedBatchAppend.PEER_FAILOVER_HOSTS, order.size)
    }

    @Test fun peerTryOrderSkipsUnavailableAndPrefersUnusedNitters() {
        val order = FeedPrivacyPartition.peerHostTryOrder(
            preferred = "https://assigned.example",
            pool = listOf(
                "https://dead.example",
                "https://busy.example",
                "https://shell.example",
                "https://challenged.example",
                "https://free.example"
            ),
            shellInstance = "https://shell.example",
            failoverLimit = 3,
            usedHosts = listOf("https://busy.example", "https://shell.example/"),
            unavailable = listOf("https://dead.example", "https://CHALLENGED.example")
        )
        assertEquals(
            listOf(
                "https://assigned.example",
                "https://free.example",
                "https://busy.example",
                "https://shell.example"
            ),
            order
        )
    }

    @Test fun peerTryOrderOptionalFailoversKeepAssignmentFirstAndShellLast() {
        FeedHostScoreboard.recordSuccess("https://shell.example", 100)
        val order = FeedPrivacyPartition.peerHostTryOrder(
            preferred = "https://assigned.example",
            pool = listOf(
                "https://assigned.example",
                "https://shell.example",
                "https://only-other.example"
            ),
            shellInstance = "https://shell.example",
            failoverLimit = 2
        )
        assertEquals(
            listOf(
                "https://assigned.example",
                "https://only-other.example",
                "https://shell.example"
            ),
            order
        )
    }
}
