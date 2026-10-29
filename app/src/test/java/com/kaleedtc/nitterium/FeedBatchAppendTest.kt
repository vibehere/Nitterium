package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.security.FeedBatchAppend
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedBatchAppendTest {
    @Test fun emptyListProducesNoop() {
        assertTrue(FeedBatchAppend.script(emptyList()).contains("void(0)"))
        assertTrue(FeedBatchAppend.injectOnePageScript("").contains("void(0)"))
    }

    @Test fun scriptFetchesAndAppendsLaterBatches() {
        val script = FeedBatchAppend.script(
            listOf("https://nitter.example/a,b", "https://nitter.example/c,d"),
            delayMs = 2_000
        )
        assertTrue(script.contains("https://nitter.example/a,b"))
        assertTrue(script.contains("https://nitter.example/c,d"))
        assertTrue(script.contains("fetch("))
        assertTrue(script.contains("timeline-item"))
        assertTrue(script.contains("2000"))
        assertTrue(script.contains("insertBefore"))
        assertTrue(script.contains("show-more"))
        assertTrue(script.contains("cursor"))
        assertFalse(script.contains("void(0)"))
    }

    @Test fun injectOnePageEscapesAndAppends() {
        val script = FeedBatchAppend.injectOnePageScript(
            """<div class="timeline-item"><a href="/x/status/1">hi</a></div>"""
        )
        assertTrue(script.contains("timeline-item"))
        assertTrue(script.contains("DOMParser"))
        assertTrue(script.contains("\\u003c"))
        assertFalse(script.contains("fetch("))
    }
}
