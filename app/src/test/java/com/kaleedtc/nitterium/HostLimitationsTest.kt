package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.security.HostLimitation
import com.kaleedtc.nitterium.data.security.HostLimitations
import com.kaleedtc.nitterium.data.security.LimitedHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostLimitationsTest {
    @After fun reset() = HostLimitations.clear()

    @Test fun detectsSearchDisabledPage() {
        assertTrue(
            HostLimitations.looksMultiTimelineDisabled(
                "<div class=\"disabled-feature\">Search disabled on this instance</div>"
            )
        )
        assertFalse(HostLimitations.looksMultiTimelineDisabled("<div class=\"error-panel\">Rate limited</div>"))
    }

    @Test fun rssUnsupportedOnlyForRealNonFeedReplies() {
        assertTrue(HostLimitations.looksRssUnsupported(404, "<html>Not found</html>"))
        assertTrue(HostLimitations.looksRssUnsupported(200, "<html><body>Anubis</body></html>"))
        assertFalse(HostLimitations.looksRssUnsupported(200, "<rss><channel></channel></rss>"))
        assertFalse(HostLimitations.looksRssUnsupported(429, "Too many requests"))
        assertFalse(HostLimitations.looksRssUnsupported(502, "Bad gateway"))
    }

    @Test fun setAsideIsPerLimitationAndExpires() {
        HostLimitations.setAside("https://Nitter.Example/", HostLimitation.NO_RSS, nowMs = 1_000)

        assertTrue(HostLimitations.isSetAside("https://nitter.example", HostLimitation.NO_RSS, nowMs = 2_000))
        assertFalse(
            HostLimitations.isSetAside("https://nitter.example", HostLimitation.NEEDS_JAVASCRIPT, nowMs = 2_000)
        )
        assertFalse(
            HostLimitations.isSetAside(
                "https://nitter.example",
                HostLimitation.NO_RSS,
                nowMs = 1_000 + HostLimitations.SET_ASIDE_MS
            )
        )
    }

    @Test fun recordProbeSetsJsAndRssAndClearsWhenHostRecovers() {
        HostLimitations.recordProbe(
            host = "https://nitter.example",
            worksHtml = false,
            challenge = true,
            rssWorks = false,
            rssUnsupported = true,
            nowMs = 10
        )
        assertTrue(
            HostLimitations.isSetAside("https://nitter.example", HostLimitation.NEEDS_JAVASCRIPT, nowMs = 11)
        )
        assertTrue(HostLimitations.isSetAside("https://nitter.example", HostLimitation.NO_RSS, nowMs = 11))

        HostLimitations.recordProbe(
            host = "https://nitter.example",
            worksHtml = true,
            challenge = false,
            rssWorks = true,
            rssUnsupported = false,
            nowMs = 12
        )
        assertFalse(
            HostLimitations.isSetAside("https://nitter.example", HostLimitation.NEEDS_JAVASCRIPT, nowMs = 13)
        )
        assertFalse(HostLimitations.isSetAside("https://nitter.example", HostLimitation.NO_RSS, nowMs = 13))
    }

    @Test fun recordProbeIgnoresTemporaryRssFailures() {
        HostLimitations.recordProbe(
            host = "https://nitter.example",
            worksHtml = true,
            challenge = false,
            rssWorks = false,
            rssUnsupported = false,
            nowMs = 10
        )
        assertFalse(HostLimitations.isSetAside("https://nitter.example", HostLimitation.NO_RSS, nowMs = 11))
        assertFalse(
            HostLimitations.isSetAside("https://nitter.example", HostLimitation.NEEDS_JAVASCRIPT, nowMs = 11)
        )
    }

    @Test fun listsOnlyLimitationsRelevantToTheMode() {
        HostLimitations.setAside("https://a.example", HostLimitation.NO_RSS, nowMs = 0)
        HostLimitations.setAside("https://b.example", HostLimitation.NEEDS_JAVASCRIPT, nowMs = 0)
        val hosts = listOf("https://a.example/", "https://b.example", "https://c.example")

        assertEquals(
            listOf(LimitedHost("https://b.example", HostLimitation.NEEDS_JAVASCRIPT)),
            HostLimitations.limitedHosts(hosts, setOf(HostLimitation.NEEDS_JAVASCRIPT), nowMs = 1)
        )
        assertEquals(
            listOf("https://a.example", "https://b.example"),
            HostLimitations.limitedHosts(hosts, HostLimitation.entries.toSet(), nowMs = 1).map { it.host }
        )
    }
}
