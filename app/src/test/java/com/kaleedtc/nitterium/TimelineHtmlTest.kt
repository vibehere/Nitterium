package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.security.FeedChunkFetcher
import com.kaleedtc.nitterium.data.security.NoJsHtmlDocument
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

    @Test fun compactPageNeverReturnsSiteChrome() {
        val full = """
            <html><head><title>nitter</title></head><body>
            <nav class="site-header"><a class="site-logo" href="/">nitter</a></nav>
            <div class="timeline">
              <div class="timeline-item"><a href="/u/status/9">x</a></div>
            </div>
            </body></html>
        """.trimIndent()
        val compact = TimelineHtml.compactPage(full)
        assertTrue(compact.contains("timeline-item"))
        assertFalse(compact.contains("site-logo"))
        assertFalse(compact.contains("<nav"))
        assertFalse(compact.contains("<html>"))
        val shellCss = TimelineHtml.SITE_CHROME_HIDE_CSS + TimelineHtml.FEED_SHELL_EXTRA_HIDE_CSS
        val mounted = NoJsHtmlDocument.prepare(compact, shellCss)
        assertTrue(mounted.contains("timeline-item"))
        assertTrue(mounted.contains("site-logo") && mounted.contains("display:none"))
        assertTrue(mounted.contains("inner-nav"))
        assertTrue(mounted.contains("preferences"))
        assertTrue(mounted.contains("profile-card"))
        assertTrue(mounted.contains(".timeline-item{align-items:flex-start}"))
        assertTrue(mounted.contains("/css/style.css"))
        assertTrue(TimelineHtml.SITE_CHROME_HIDE_CSS.contains("html body nav"))
        val withAssets = NoJsHtmlDocument.prepare(
            compact,
            shellCss,
            styleAssets = """<link rel="stylesheet" href="/css/themes/auto_(twitter).css"/>"""
        )
        assertTrue(withAssets.contains("auto_(twitter).css"))
        assertFalse(withAssets.contains("/css/themes/twitter.css"))
        val head = """
            <html><head>
            <link rel="stylesheet" type="text/css" href="/css/style.css?v=1"/>
            <link rel="stylesheet" href="/css/fontello.css"/>
            <link rel="icon" href="/favicon.ico"/>
            <link rel="preload" as="font" href="/fonts/fontello.woff2" crossorigin="anonymous"/>
            </head><body></body></html>
        """.trimIndent()
        val assets = TimelineHtml.extractStylesheetAssets(head)
        assertTrue(assets.contains("style.css"))
        assertTrue(assets.contains("fontello.css"))
        assertTrue(assets.contains("as=\"font\"") || assets.contains("as='font'"))
        assertFalse(assets.contains("favicon"))
    }

    @Test fun rewriteOriginsMovesPageLinksToShellButKeepsMediaOnFetchHost() {
        val html = """<a href="https://fetch.example/user/status/1">x</a><img src="https://fetch.example/pic/a">"""
        val out = FeedChunkFetcher.rewriteOrigins(html, "https://fetch.example", "https://shell.example")
        assertTrue(out.contains("https://shell.example/user/status/1"))
        assertTrue(out.contains("https://fetch.example/pic/a"))
        assertFalse(out.contains("https://fetch.example/user"))
    }

    @Test fun rewriteOriginsPinsRelativeMediaToFetchHost() {
        val html = """<a href="/alice">a</a><img class="avatar" src="/pic/profile_images%2F1%2Fa.jpg">""" +
            """<video poster='/pic/media%2Fb.jpg' data-url="/video/x"></video>""" +
            """<div style="background-image:url('/pic/banner')"></div>"""
        val out = FeedChunkFetcher.rewriteOrigins(html, "https://fetch.example", "https://shell.example")
        assertTrue(out.contains("""href="/alice""""))
        assertTrue(out.contains("""src="https://fetch.example/pic/profile_images%2F1%2Fa.jpg""""))
        assertTrue(out.contains("""poster='https://fetch.example/pic/media%2Fb.jpg'"""))
        assertTrue(out.contains("""data-url="https://fetch.example/video/x""""))
        assertTrue(out.contains("""url('https://fetch.example/pic/banner')"""))
    }

    @Test fun nextPageQueryReadsLoadMoreCursorInBothLinkStyles() {
        assertEquals(
            "?cursor=2122146355074232469",
            TimelineHtml.nextPageQuery(
                """<div class="show-more"><a href="/SpaceX,elonmusk?cursor=2122146355074232469">Load more</a></div>"""
            )
        )
        assertEquals(
            "?cursor=U3Bh&a=1",
            TimelineHtml.nextPageQuery(
                """<div class="show-more"><a href="?cursor=U3Bh&amp;a=1">Load more</a></div>"""
            )
        )
        assertEquals(
            null,
            TimelineHtml.nextPageQuery("""<div class="timeline-item show-more"><a href="/SpaceX">Load newest</a></div>""")
        )
    }

    @Test fun compactPageKeepsHiddenLoadMoreCursor() {
        val page = """<html><body><div class="timeline">""" +
            """<div class="timeline-item"><a href="/x/status/1">a</a></div>""" +
            """<div class="show-more"><a href="/x?cursor=9&amp;b=2">Load more</a></div>""" +
            """</div></body></html>"""
        val compact = TimelineHtml.compactPage(page)
        assertEquals("?cursor=9&b=2", TimelineHtml.nextPageQuery(compact))
        assertTrue(TimelineHtml.FEED_SHELL_EXTRA_HIDE_CSS.contains("html body .show-more"))
    }

    @Test fun directXImagesGoThroughShellPicProxy() {
        val html = """<img src="https://pbs.twimg.com/card_img/1/a?format=jpg&amp;name=800x419">"""
        val out = FeedChunkFetcher.rewriteOrigins(html, "https://fetch.example", "https://shell.example")
        assertEquals(
            """<img src="https://shell.example/pic/https%3A%2F%2Fpbs.twimg.com%2Fcard_img%2F1%2Fa%3Fformat%3Djpg%26name%3D800x419">""",
            out
        )
    }

    @Test fun rewriteOriginsLeavesShellMediaRelative() {
        val html = """<img src="/pic/a">"""
        assertEquals(
            html,
            FeedChunkFetcher.rewriteOrigins(html, "https://shell.example", "https://shell.example")
        )
    }

    @Test fun rewriteOriginsNormalizesOnionHttpsToHttpShell() {
        val onion = "http://nitterbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.onion"
        val html = """<img src="https://nitterbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.onion/pic/a">"""
        val out = FeedChunkFetcher.rewriteOrigins(html, onion, onion)
        assertTrue(out.contains("$onion/pic/a"))
        assertFalse(out.contains("https://nitterbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.onion"))
    }
}
