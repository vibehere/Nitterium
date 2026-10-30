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

    /** Compact HTML the WebView can append without shipping the whole page. */
    fun compactPage(html: String): String {
        val items = extractItems(html)
        if (items.isEmpty()) return ""
        return buildString {
            append("<div class=\"timeline\">")
            items.forEach { append(it) }
            append("</div>")
        }
    }

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
