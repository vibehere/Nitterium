package com.kaleedtc.nitterium.data.security

/** Pulls timeline post fragments out of a Nitter/Teapot HTML page without a DOM parser. */
object TimelineHtml {
    fun extractItems(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        val items = mutableListOf<String>()
        var searchFrom = 0
        while (searchFrom < html.length) {
            val marker = indexOfAny(html, listOf("timeline-item", "class=\"tweet-body", "class='tweet-body"), searchFrom)
            if (marker < 0) break
            val open = html.lastIndexOf('<', marker)
            if (open < 0 || !html.regionMatches(open + 1, "div", 0, 3, ignoreCase = true)) {
                searchFrom = marker + 1
                continue
            }
            val fragment = balancedElement(html, open) ?: break
            if (fragment.contains("status/", ignoreCase = true) || fragment.contains("timeline-item")) {
                items += fragment
            }
            searchFrom = open + fragment.length
        }
        return items
    }

    private fun indexOfAny(haystack: String, needles: List<String>, start: Int): Int {
        var best = -1
        for (needle in needles) {
            val at = haystack.indexOf(needle, start, ignoreCase = true)
            if (at >= 0 && (best < 0 || at < best)) best = at
        }
        return best
    }

    /**
     * Compact HTML the WebView can append without shipping the whole page. The
     * "Load more" cursor is kept so the chunk's next page can be requested later.
     */
    fun compactPage(html: String): String {
        val items = extractItems(html)
        if (items.isNotEmpty()) {
            val cursor = nextPageQuery(html)
            return buildString {
                append("<div class=\"timeline\">")
                items.forEach { append(it) }
                if (cursor != null) {
                    append("<div class=\"show-more\"><a href=\"")
                    append(cursor.replace("&", "&amp;"))
                    append("\">Load more</a></div>")
                }
                append("</div>")
            }
        }
        // Never fall back to the full site document (logo/nav/search chrome).
        return extractTimelineContainer(html).orEmpty()
    }

    private val showMoreCursorLink = Regex(
        """(?is)class\s*=\s*["'][^"']*\bshow-more\b[^"']*["'][^>]*>\s*<a\b[^>]*href\s*=\s*["']([^"']*cursor=[^"']*)["']"""
    )

    /** Query string (`?cursor=…`) of the page's "Load more" link, or null on the last page. */
    fun nextPageQuery(html: String): String? {
        val href = showMoreCursorLink.find(html)?.groupValues?.get(1) ?: return null
        val query = href.replace("&amp;", "&").substringAfter('?', missingDelimiterValue = "")
        return query.takeIf { it.contains("cursor=") }?.let { "?$it" }
    }

    /**
     * Pulls an existing `.timeline` / `#timeline` block out of a Nitter page.
     * Returns null when no timeline container is found.
     */
    fun extractTimelineContainer(html: String): String? {
        if (html.isBlank()) return null
        val markers = listOf(
            "class=\"timeline\"",
            "class='timeline'",
            "id=\"timeline\"",
            "id='timeline'",
            "timeline-container"
        )
        for (marker in markers) {
            val idx = html.indexOf(marker, ignoreCase = true)
            if (idx < 0) continue
            val open = html.lastIndexOf('<', idx)
            if (open < 0 || !html.regionMatches(open, "<div", 0, 4, ignoreCase = true)) continue
            val block = balancedElement(html, open) ?: continue
            // Empty multi-user timelines are still a valid Feed shell container;
            // peer chunks append items into this node.
            if (block.contains("timeline-item", ignoreCase = true) ||
                block.contains("/status/", ignoreCase = true) ||
                block.contains("timeline", ignoreCase = true)
            ) {
                return block
            }
        }
        return null
    }

    /**
     * Stylesheet / font-preload tags from a full Nitter page head.
     * Kept so chrome-free Feed All still looks like a normal timeline.
     */
    fun extractStylesheetAssets(html: String): String {
        if (html.isBlank()) return ""
        val out = StringBuilder()
        val linkTag = Regex("""(?is)<link\b[^>]*>""")
        for (match in linkTag.findAll(html)) {
            val tag = match.value
            val lower = tag.lowercase()
            val stylesheet = lower.contains("stylesheet")
            val fontPreload = lower.contains("as=\"font\"") || lower.contains("as='font'") ||
                lower.contains("as=font")
            if (stylesheet || fontPreload) {
                out.append(tag).append('\n')
            }
        }
        return out.toString().trim()
    }

    /** Relative links resolve against [WebView.loadDataWithBaseURL] instance base. */
    const val FALLBACK_FEED_STYLE_ASSETS: String =
        """<link rel="stylesheet" type="text/css" href="/css/style.css"/>
<link rel="stylesheet" type="text/css" href="/css/fontello.css"/>
<link rel="stylesheet" type="text/css" href="/css/themes/twitter.css"/>
<link rel="preload" type="font/woff2" as="font" href="/fonts/fontello.woff2" crossorigin="anonymous"/>"""

    /**
     * Hide Nitter/Teapot site chrome (logo, search, Preferences cog).
     * High specificity so restored instance `style.css` cannot win with `nav { display:flex }`.
     */
    const val SITE_CHROME_HIDE_CSS: String =
        """html body nav,html body nav.site-header,html body .site-header,html body .inner-nav,
html body .site-logo,html body .site-name,html body .icon-sprite,html body .top-nav,
html body .search-bar,html body .search-header,html body .search-field,html body .search-panel,
html body .timeline-header,html body .header,html body form[action*="search"],
html body .preferences,html body .preferences-container{
display:none!important}
body.fixed-nav .container,body.fixed-nav .timeline-container{padding-top:0!important}
body.fixed-nav .profile-tab{top:0!important}"""

    /**
     * Extra chrome stripped only on chrome-free Feed All shells. "Load more" is hidden
     * because older pages are fetched per chunk instead of following one Nitter's cursor.
     * Peer posts come from other Nitter forks; stretching their body to the row height
     * lets card images size against it and overflow into the next post.
     */
    const val FEED_SHELL_EXTRA_HIDE_CSS: String =
        """html body .tabs,html body .profile-card,html body .profile-tabs,
html body .profile-banner,html body .profile-header,html body ul.tab,
html body .multi-user-timeline>h2,html body .show-more{display:none!important}
body{margin:0;padding:0}
.timeline-item{align-items:flex-start}"""

    private fun balancedElement(html: String, openAngle: Int): String? {
        if (openAngle !in html.indices || html[openAngle] != '<') return null
        val tagEnd = html.indexOf('>', openAngle + 1)
        if (tagEnd < 0) return null
        val afterSlash = html[tagEnd - 1] == '/'
        if (afterSlash) return html.substring(openAngle, tagEnd + 1)

        val name = html.substring(openAngle + 1, tagEnd)
            .takeWhile { it.isLetterOrDigit() || it == '-' }
            .lowercase()
        if (name.isEmpty()) return null

        var depth = 1
        var i = tagEnd + 1
        while (i < html.length && depth > 0) {
            val next = html.indexOf('<', i)
            if (next < 0) return null
            if (html.startsWith("<!--", next)) {
                val endComment = html.indexOf("-->", next + 4)
                if (endComment < 0) return null
                i = endComment + 3
                continue
            }
            val close = html.indexOf('>', next + 1)
            if (close < 0) return null
            val selfClosing = html[close - 1] == '/'
            when {
                html.regionMatches(next + 1, "/$name", 0, name.length + 1, ignoreCase = true) -> depth--
                !selfClosing && html.regionMatches(next + 1, name, 0, name.length, ignoreCase = true) &&
                    (next + 1 + name.length >= html.length ||
                        !html[next + 1 + name.length].isLetterOrDigit()) -> depth++
            }
            i = close + 1
            if (depth == 0) return html.substring(openAngle, close + 1)
        }
        return null
    }
}
