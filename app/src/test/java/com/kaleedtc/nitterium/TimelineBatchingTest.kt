package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.frontend.FrontendAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineBatchingTest {
    private val adapter = FrontendAdapter.Nitter

    @Test fun singleBatchWhenUnderLimits() {
        val users = (1..5).map { "user$it" }
        val urls = adapter.timelineUrls("https://nitter.example", users)
        assertEquals(1, urls.size)
        assertEquals("https://nitter.example/user1,user2,user3,user4,user5", urls.single())
        assertEquals(urls.single(), adapter.timelineUrl("https://nitter.example", users))
    }

    @Test fun splitsByUserCount() {
        val users = (1..45).map { "u$it" }
        val urls = adapter.timelineUrls(
            "https://nitter.example",
            users,
            maxUsersPerBatch = 20,
            maxUrlLength = 10_000
        )
        assertEquals(3, urls.size)
        assertEquals(20, urls[0].substringAfterLast('/').split(',').size)
        assertEquals(20, urls[1].substringAfterLast('/').split(',').size)
        assertEquals(5, urls[2].substringAfterLast('/').split(',').size)
    }

    @Test fun splitsByUrlLength() {
        val users = listOf("aaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbb", "ccccccccccccccc", "ddddddddddddddd")
        val urls = adapter.timelineUrls(
            "https://nitter.example",
            users,
            maxUsersPerBatch = 50,
            maxUrlLength = 50
        )
        assertTrue(urls.size > 1)
        urls.forEach { url ->
            val names = url.substringAfterLast('/').split(',')
            assertTrue(
                url.length <= 50 ||
                    names.size == 1 ||
                    (names.size == 2 && names[0] == names[1])
            )
        }
    }

    @Test fun emptyRejected() {
        assertTrue(runCatching { adapter.timelineUrls("https://nitter.example", emptyList()) }.isFailure)
    }

    @Test fun singletonBatchUsesDuplicatedMultiUserPathNotProfile() {
        val urls = adapter.timelineUrls(
            "https://nitter.example",
            listOf("alice", "bob", "carol"),
            maxUsersPerBatch = 1,
            maxUrlLength = 10_000
        )
        assertEquals(
            listOf(
                "https://nitter.example/alice,alice",
                "https://nitter.example/bob,bob",
                "https://nitter.example/carol,carol"
            ),
            urls
        )
        assertEquals(
            "https://nitter.example/alice,alice",
            adapter.timelineUrl("https://nitter.example", listOf("alice"))
        )
    }

    @Test fun defaultBatchSizeIsTen() {
        assertEquals(10, FrontendAdapter.DEFAULT_MAX_USERS_PER_BATCH)
        val users = (1..25).map { "user$it" }
        val urls = adapter.timelineUrls("https://nitter.example", users)
        assertEquals(3, urls.size)
        assertEquals(10, urls[0].substringAfterLast('/').split(',').size)
        assertEquals(10, urls[1].substringAfterLast('/').split(',').size)
        assertEquals(5, urls[2].substringAfterLast('/').split(',').size)
    }
}
