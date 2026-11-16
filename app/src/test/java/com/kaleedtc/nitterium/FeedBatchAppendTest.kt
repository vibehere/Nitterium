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
        assertTrue(script.contains("/status/"))
        assertTrue(script.contains("peerDelayMs"))
        assertTrue(script.contains("concurrency"))
        assertTrue(script.contains("loadPeerBatches"))
        assertTrue(script.contains("resortTimeline"))
        assertTrue(script.contains("idLess"))
        assertFalse(script.contains("void(0)"))
    }

    @Test fun injectOnePageUsesBase64AndResorts() {
        val script = FeedBatchAppend.injectOnePageScript(
            """<div class="timeline-item"><a href="/x/status/1">hi</a></div>"""
        )
        assertTrue(script.contains("atob"))
        assertTrue(script.contains("TextDecoder"))
        assertTrue(script.contains("DOMParser"))
        assertTrue(script.contains("resortTimeline"))
        assertTrue(script.contains("idLess"))
        assertFalse(script.contains("fetch("))
    }

    @Test fun injectOnePageSkipsStatusesAlreadyOnScreen() {
        val script = FeedBatchAppend.injectOnePageScript(
            """<div class="timeline-item"><a href="/x/status/1">hi</a></div>"""
        )
        assertTrue(script.contains("return statusId(href) || href"))
        val remember = script.indexOf("rememberTimeline(timeline, seen);")
        val append = script.indexOf("appendUnseen(timeline, doc, seen);")
        assertTrue(remember in 0 until append)
    }
}
