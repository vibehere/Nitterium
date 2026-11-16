package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.security.FeedTimelineCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FeedTimelineCacheKeyTest {
    @Test
    fun keyIgnoresUsernameOrderAndCase() {
        val a = FeedTimelineCache.key("https://nitter.example/", listOf("Bob", "alice"))
        val b = FeedTimelineCache.key("https://nitter.example", listOf("alice", "bob"))
        assertEquals(a, b)
    }

    @Test
    fun keyDiffersByInstance() {
        val a = FeedTimelineCache.key("https://a.example", listOf("alice"))
        val b = FeedTimelineCache.key("https://b.example", listOf("alice"))
        assertNotEquals(a, b)
    }
}
