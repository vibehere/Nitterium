package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.security.TimelineUrlUsers
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineUrlUsersTest {
    @Test
    fun parsesMultiUserPath() {
        assertEquals(
            listOf("alice", "bob", "carol"),
            TimelineUrlUsers.parse("https://nitter.example/alice,bob,carol")
        )
    }

    @Test
    fun parsesSingletonDuplicatedPath() {
        assertEquals(
            listOf("alice"),
            TimelineUrlUsers.parse("https://nitter.example/alice,alice")
        )
    }

    @Test
    fun countsAcrossBatches() {
        val urls = listOf(
            "https://nitter.example/a,b,c",
            "https://nitter.example/d,d"
        )
        assertEquals(4, TimelineUrlUsers.countInUrls(urls))
    }
}
