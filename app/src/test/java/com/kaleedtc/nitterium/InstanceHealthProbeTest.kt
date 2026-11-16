package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.security.InstanceHealthProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstanceHealthProbeTest {
    @Test
    fun htmlProbeUrlsUseProfileNotHomepage() {
        val urls = InstanceHealthProbe.htmlProbeUrls("https://nitter.example")
        assertTrue(urls.none { it == "https://nitter.example" || it == "https://nitter.example/" })
        assertTrue(urls.any { it.endsWith("/NASA") })
        assertTrue(urls.any { it.contains("/search?") })
    }

    @Test
    fun classifyHtmlAcceptsTimelineAndRejectsHomepageShell() {
        val timeline = InstanceHealthProbe.classifyHtml(
            """<div class="timeline-item"><div class="tweet-content">hi</div></div>""",
            emptyMap()
        )
        assertTrue(timeline.worksHtml)
        assertFalse(timeline.challenge)

        val homepage = InstanceHealthProbe.classifyHtml(
            """<html><title>nitter</title><form class="search-bar"></form></html>""",
            emptyMap()
        )
        assertFalse(homepage.worksHtml)
    }

    @Test
    fun classifyHtmlDetectsAnubisAndCloudflareChallenges() {
        val anubis = InstanceHealthProbe.classifyHtml(
            """<html><body>Anubis is making sure you're not a bot</body></html>""",
            emptyMap()
        )
        assertTrue(anubis.challenge)
        assertFalse(anubis.worksHtml)
        assertEquals("Anubis", anubis.ddosProtection)

        val cf = InstanceHealthProbe.classifyHtml(
            """<html><body>Just a moment...</body></html>""",
            mapOf("server" to "cloudflare", "cf-ray" to "abc")
        )
        assertTrue(cf.challenge)
        assertFalse(cf.worksHtml)
        assertEquals("Cloudflare", cf.ddosProtection)
    }

    @Test
    fun looksLikeChallengeDoesNotFlagRealTimelineBehindCfServer() {
        assertFalse(
            InstanceHealthProbe.looksLikeChallenge(
                """<div class="timeline-item"><div class="tweet-content">x</div></div>""",
                mapOf("server" to "cloudflare")
            )
        )
    }
}
