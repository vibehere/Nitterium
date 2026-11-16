package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.security.FeedChallengeQueue
import com.kaleedtc.nitterium.data.security.FeedChunkAssignment
import com.kaleedtc.nitterium.data.security.FeedChunkFetcher
import com.kaleedtc.nitterium.data.security.WebViewCookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedChallengeTest {
    private fun chunk(host: String, vararg users: String) =
        FeedChunkAssignment(host, users.toList(), "$host/${users.joinToString(",")}")

    @Test fun detectsCommonBotChecks() {
        assertTrue(
            FeedChunkFetcher.isBotChallenge(
                200,
                "<title>Making sure you&#39;re not a bot!</title>",
                emptyMap()
            )
        )
        assertTrue(
            FeedChunkFetcher.isBotChallenge(
                200,
                """<meta http-equiv="refresh" content="0; url=/.gandalf/check">""",
                emptyMap()
            )
        )
        assertTrue(
            FeedChunkFetcher.isBotChallenge(403, "<div class=\"captcha\"></div>", emptyMap())
        )
    }

    @Test fun originErrorsAndRealTimelinesAreNotChallenges() {
        assertFalse(FeedChunkFetcher.isBotChallenge(522, "captcha", emptyMap()))
        assertFalse(
            FeedChunkFetcher.isBotChallenge(
                200,
                "<div class=\"timeline-item\">solving a captcha today</div>",
                emptyMap()
            )
        )
    }

    @Test fun queueGroupsChunksPerHostWithoutDuplicates() {
        val queue = FeedChallengeQueue()
        queue.add("https://Nitter.A/", chunk("https://nitter.a", "alice"))
        queue.add("https://nitter.a", chunk("https://nitter.a", "alice"))
        queue.add("https://nitter.a", chunk("https://nitter.a", "bob"))
        queue.add("https://nitter.b", chunk("https://nitter.b", "carol"))
        queue.add(" ", chunk("https://nitter.c", "dave"))

        assertEquals(listOf("https://nitter.a", "https://nitter.b"), queue.hosts)
        assertEquals(listOf("alice"), queue.firstChunk("https://nitter.a")?.usernames)
        assertEquals(2, queue.take("https://NITTER.A").size)
        assertEquals(listOf("https://nitter.b"), queue.hosts)
        queue.clear()
        assertTrue(queue.hosts.isEmpty())
        assertNull(queue.firstChunk("https://nitter.b"))
    }

    @Test fun hostForPrefersAssignedNitter() {
        val assigned = chunk("https://nitter.b", "alice")
        assertEquals(
            "https://nitter.b",
            FeedChallengeQueue.hostFor(assigned, listOf("https://nitter.a", "https://nitter.b/"))
        )
        assertEquals(
            "https://nitter.a",
            FeedChallengeQueue.hostFor(assigned, listOf("https://nitter.a"))
        )
        assertNull(FeedChallengeQueue.hostFor(assigned, emptyList()))
    }

    @Test fun cookieHeaderParsesEachPair() {
        val cookies = WebViewCookieJar.parseCookieHeader(
            "https://nitter.example/alice".toHttpUrl(),
            "within.website-x-cmd-anubis-auth=token; ; theme=dark"
        )
        assertEquals(
            listOf("within.website-x-cmd-anubis-auth" to "token", "theme" to "dark"),
            cookies.map { it.name to it.value }
        )
    }
}
