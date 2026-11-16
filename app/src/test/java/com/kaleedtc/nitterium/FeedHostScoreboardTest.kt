package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.security.FeedHostScoreboard
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FeedHostScoreboardTest {
    @Before
    fun reset() {
        FeedHostScoreboard.resetForTests()
    }

    @Test
    fun ranksFasterSuccessfulHostsBeforeFailuresAndUnknown() {
        FeedHostScoreboard.recordSuccess("https://fast.example", 400)
        FeedHostScoreboard.recordSuccess("https://slow.example", 2_400)
        FeedHostScoreboard.recordFailure("https://dead.example", 12_000)

        assertEquals(
            listOf(
                "https://fast.example",
                "https://slow.example",
                "https://unknown.example",
                "https://dead.example"
            ),
            FeedHostScoreboard.rank(
                listOf(
                    "https://dead.example",
                    "https://unknown.example",
                    "https://slow.example",
                    "https://fast.example"
                )
            )
        )
    }
}
