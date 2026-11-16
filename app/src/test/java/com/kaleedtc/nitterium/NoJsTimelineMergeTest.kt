package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.security.NoJsHtmlDocument
import com.kaleedtc.nitterium.data.security.NoJsTimelineMerge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoJsTimelineMergeTest {
    @Test
    fun mergesPeerTimelineItemsIntoShellWithoutScripts() {
        val shell = """
            <html><head></head><body>
            <div class="timeline">
              <div class="timeline-item"><a href="/alice/status/2">a</a><div class="tweet-content">hello</div></div>
              <div class="timeline-item show-more"><a href="?cursor=1">more</a></div>
            </div>
            </body></html>
        """.trimIndent()
        val peer = """
            <html><body><div class="timeline">
              <div class="timeline-item"><a href="/bob/status/3">b</a><div class="tweet-content">world</div></div>
            </div></body></html>
        """.trimIndent()

        val merged = NoJsTimelineMerge.merge(shell, listOf(peer))
        assertTrue(merged.contains("/alice/status/2"))
        assertTrue(merged.contains("/bob/status/3"))
        assertTrue(merged.contains("show-more"))
        // Newer peer post must surface above the older shell post.
        assertTrue(merged.indexOf("/bob/status/3") < merged.indexOf("/alice/status/2"))

        val prepared = NoJsHtmlDocument.prepare(
            html = shell + "<script>alert(1)</script>",
            styleCss = "body{color:red}",
            peerPages = listOf(peer)
        )
        assertFalse(prepared.contains("<script>", ignoreCase = true))
        assertTrue(prepared.contains("nitterium-nojs-style"))
        assertTrue(prepared.contains("/bob/status/3"))
        assertTrue(prepared.indexOf("/bob/status/3") < prepared.indexOf("/alice/status/2"))
    }

    @Test
    fun compareStatusIdsOrdersByNumericSnowflake() {
        assertTrue(NoJsTimelineMerge.compareStatusIds("100", "99") > 0)
        assertTrue(NoJsTimelineMerge.compareStatusIds("99", "100") < 0)
        assertTrue(
            NoJsTimelineMerge.compareStatusIds(
                "2000000000000000001",
                "2000000000000000000"
            ) > 0
        )
    }
}
