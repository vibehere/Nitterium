package com.kaleedtc.nitterium.data.security

/**
 * Prepares HTML for a JavaScript-disabled WebView: strip scripts/handlers and
 * inject app CSS so theme/header tweaks still apply without evaluateJavascript.
 */
object NoJsHtmlDocument {
    private val scriptTag = Regex("(?is)<script\\b[^>]*>.*?</script>")
    private val scriptSrc = Regex("(?is)<script\\b[^>]*/>")
    private val inlineHandler = Regex("(?is)\\s+on[a-z]+\\s*=\\s*(['\"])(.*?)\\1")
    private val javascriptHref = Regex("(?i)href\\s*=\\s*(['\"])\\s*javascript:[^'\"]*\\1")
    private val lazyVideoTag = Regex("(?is)<video\\b([^>]*?)\\sdata-url\\s*=\\s*\"([^\"]*)\"([^>]*)>")
    private val srcAttr = Regex("(?i)\\ssrc\\s*=")

    /** Nitter's play overlay only works through its scripts, which are stripped here. */
    private const val NATIVE_VIDEO_CSS = ".video-overlay{display:none!important}"

    fun prepare(
        html: String,
        styleCss: String,
        peerPages: List<String> = emptyList(),
        filterUsers: List<PerUserFeedFilter> = emptyList(),
        mutedWords: List<String> = emptyList(),
        globalHideRetweets: Boolean = false,
        globalHideReplies: Boolean = false,
        styleAssets: String = ""
    ): String {
        val merged = if (peerPages.isEmpty()) html else NoJsTimelineMerge.merge(html, peerPages)
        val items = NoJsTimelineMerge.extractTimelineItems(merged)
        val showMore = items.filter { NoJsTimelineMerge.isShowMoreItem(it) }
        val posts = NoJsTimelineMerge.filterItems(
            items = items.filterNot { NoJsTimelineMerge.isShowMoreItem(it) },
            users = filterUsers,
            mutedWords = mutedWords,
            globalHideRetweets = globalHideRetweets,
            globalHideReplies = globalHideReplies
        )
        val ordered = NoJsTimelineMerge.sortNewestFirst(posts)
        val body = NoJsTimelineMerge.replaceTimelineItems(merged, ordered + showMore)
        return injectStyles(stripActiveContent(body), styleCss + NATIVE_VIDEO_CSS, styleAssets)
    }

    fun stripActiveContent(html: String): String =
        nativeVideoPlayback(
            html.replace(scriptTag, "")
                .replace(scriptSrc, "")
                .replace(inlineHandler, "")
                .replace(javascriptHref, "href=\"#\"")
        )

    /** Lazy Nitter videos keep their stream in data-url; WebView plays it natively with controls. */
    fun nativeVideoPlayback(html: String): String =
        html.replace(lazyVideoTag) { match ->
            val (before, url, after) = match.destructured
            if (srcAttr.containsMatchIn(before + after)) {
                match.value
            } else {
                "<video$before data-url=\"$url\"$after src=\"$url\" controls preload=\"none\">"
            }
        }

    fun injectStyles(html: String, styleCss: String, styleAssets: String = ""): String {
        val assets = styleAssets.trim().ifBlank { TimelineHtml.FALLBACK_FEED_STYLE_ASSETS }
        val appStyle = if (styleCss.isBlank()) {
            ""
        } else {
            """<style id="nitterium-nojs-style">$styleCss</style>"""
        }
        val tag = assets + appStyle
        if (tag.isBlank()) return html
        val headClose = html.indexOf("</head>", ignoreCase = true)
        if (headClose >= 0) {
            return html.substring(0, headClose) + tag + html.substring(headClose)
        }
        val body = html.indexOf("<body", ignoreCase = true)
        if (body >= 0) {
            return html.substring(0, body) + "<head>$tag</head>" + html.substring(body)
        }
        return "<head>$tag</head>$html"
    }
}
