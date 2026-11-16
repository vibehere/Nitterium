package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.model.Subscription
import com.kaleedtc.nitterium.data.security.FeedBatchOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedBatchOrderTest {
    @Test
    fun activeUsernamesSkipsMuted() {
        val subs = listOf(
            Subscription("alice", "https://nitter.example"),
            Subscription("bob", "https://nitter.example", mutedInFeed = true),
            Subscription("carol", "https://nitter.example")
        )
        assertEquals(listOf("alice", "carol"), FeedBatchOrder.activeUsernames(subs))
    }

    @Test
    fun groupFeedKeepsSubscriptionOrder() {
        val names = listOf("a", "b", "c", "d", "e", "f")
        assertEquals(
            names,
            FeedBatchOrder.orderForFeed(names, chunkSize = 2, diversify = false)
        )
    }

    @Test
    fun allFeedDiversifiesFirstChunk() {
        val names = (1..12).map { "u$it" }
        val ordered = FeedBatchOrder.orderForFeed(names, chunkSize = 3, diversify = true)
        assertEquals(names.size, ordered.size)
        assertEquals(names.toSet(), ordered.toSet())
        // First request should not be only the first three quiet accounts.
        assertFalse(ordered.take(3) == names.take(3))
        assertTrue(ordered.take(3).contains("u1"))
    }

    @Test
    fun shuffleSeedReshufflesButKeepsSameMembers() {
        val names = listOf("a", "b", "c", "d", "e", "f")
        val a = FeedBatchOrder.orderForFeed(names, chunkSize = 1, diversify = false, shuffleSeed = 1L)
        val b = FeedBatchOrder.orderForFeed(names, chunkSize = 1, diversify = false, shuffleSeed = 2L)
        assertEquals(names.toSet(), a.toSet())
        assertEquals(names.toSet(), b.toSet())
        assertEquals(a, FeedBatchOrder.orderForFeed(names, chunkSize = 1, diversify = false, shuffleSeed = 1L))
        assertTrue(a != b)
    }
}
