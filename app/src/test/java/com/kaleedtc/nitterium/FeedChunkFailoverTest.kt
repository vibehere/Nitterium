package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.security.FeedChunkFetcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedChunkFailoverTest {
    @Test fun timelineDetectionAcceptsCommonMarkup() {
        assertTrue(FeedChunkFetcher.looksLikeTimeline("""<div class="timeline-item"><a href="/u/status/1">x</a></div>"""))
        assertTrue(
            FeedChunkFetcher.looksLikeTimeline(
                """<div class="timeline"><div class="tweet-content">hi</div><a href="/u/status/9">x</a></div>"""
            )
        )
        assertFalse(FeedChunkFetcher.looksLikeTimeline("<html><body>Just Anubis challenge</body></html>"))
        assertFalse(FeedChunkFetcher.looksLikeTimeline(""))
    }
}
