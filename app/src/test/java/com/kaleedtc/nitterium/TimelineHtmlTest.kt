package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.security.FeedChunkFetcher
import com.kaleedtc.nitterium.data.security.TimelineHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineHtmlTest {
    @Test fun extractsTimelineItems() {
        val html = """
            <html><body><div class="timeline">
              <div class="timeline-item"><a class="tweet-link" href="/a/status/1">one</a></div>
              <div class="timeline-item"><a class="tweet-link" href="/b/status/2">two</a></div>
            </div></body></html>
        """.trimIndent()
        val items = TimelineHtml.extractItems(html)
        assertEquals(2, items.size)
        assertTrue(items[0].contains("status/1"))
        assertTrue(items[1].contains("status/2"))
        val compact = TimelineHtml.compactPage(html)
        assertTrue(compact.contains("timeline-item"))
        assertFalse(compact.contains("<html>"))
    }

    @Test fun rewriteOriginsSwapsFetchHostForShell() {
        val html = """<a href="https://fetch.example/user/status/1">x</a><img src="https://fetch.example/pic/a">"""
        val out = FeedChunkFetcher.rewriteOrigins(html, "https://fetch.example", "https://shell.example")
        assertTrue(out.contains("https://shell.example/user/status/1"))
        assertTrue(out.contains("https://shell.example/pic/a"))
        assertFalse(out.contains("https://fetch.example"))
    }
}
