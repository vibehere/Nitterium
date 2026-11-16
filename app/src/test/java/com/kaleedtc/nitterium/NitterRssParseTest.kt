package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.security.NitterRss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NitterRssParseTest {
    @Test
    fun parsesStatusItemsIntoTimelineHtml() {
        val xml = """
            <?xml version="1.0"?>
            <rss><channel>
              <item>
                <title>Hello</title>
                <dc:creator>alice</dc:creator>
                <description><![CDATA[<p>Hi</p><img src="/pic/media.jpg">]]></description>
                <pubDate>Fri, 30 Oct 2026 12:00:00 GMT</pubDate>
                <link>https://nitter.example/alice/status/1234567890123456789</link>
                <guid>https://nitter.example/alice/status/1234567890123456789#m</guid>
              </item>
            </channel></rss>
        """.trimIndent()
        val items = NitterRss.parseItems(xml, "alice", "https://nitter.example")
        assertEquals(1, items.size)
        assertEquals("1234567890123456789", items[0].statusId)
        assertTrue(items[0].descriptionHtml.contains("https://nitter.example/pic/media.jpg"))
        val html = NitterRss.toTimelineHtml(items)
        assertTrue(html.contains("timeline-item"))
        assertTrue(html.contains("/status/1234567890123456789"))
        assertTrue(html.contains("tweet-link"))
        assertTrue(html.contains("Oct 30"))
        assertFalse(html.contains("00:00:00 GMT"))
    }

    @Test
    fun formatPubDateUsesShortLocalStyle() {
        assertEquals("Oct 30", NitterRss.formatPubDate("Fri, 30 Oct 2026 12:00:00 +0000"))
        assertEquals("raw", NitterRss.formatPubDate("raw"))
    }

    @Test
    fun absolutizeMediaLeavesProtocolRelativeAlone() {
        val html = """<img src="//cdn.example/a.jpg"><img src="/pic/b.jpg">"""
        val out = NitterRss.absolutizeMedia(html, "https://nitter.example")
        assertTrue(out.contains("src=\"//cdn.example/a.jpg\""))
        assertTrue(out.contains("src=\"https://nitter.example/pic/b.jpg\""))
    }
}
