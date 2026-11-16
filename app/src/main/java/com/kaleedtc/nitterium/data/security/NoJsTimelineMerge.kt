package com.kaleedtc.nitterium.data.security

/**
 * Merges peer timeline HTML into a shell page without running JavaScript.
 * Used when the WebView has JS disabled so Feed All can still append batches.
 */
object NoJsTimelineMerge {
    private val statusHref = Regex("""(?i)/status/(\d+)""")
    private val showMoreClass = Regex("""(?i)class\s*=\s*["'][^"']*show-more[^"']*["']""")

    fun merge(shellHtml: String, peerPages: List<String>): String {
        if (shellHtml.isBlank()) return shellHtml
        val items = ArrayList<String>()
        val seen = LinkedHashSet<String>()
        var showMore: String? = null
        for (item in extractTimelineItems(shellHtml)) {
            if (isShowMore(item)) {
                showMore = item
                continue
            }
            val key = statusKey(item) ?: item.hashCode().toString()
            if (seen.add(key)) items += item
        }
        for (page in peerPages) {
            if (page.isBlank()) continue
            for (item in extractTimelineItems(page)) {
                if (isShowMore(item)) continue
                val key = statusKey(item) ?: continue
                if (seen.add(key)) items += item
            }
        }
        val ordered = sortNewestFirst(items)
        if (showMore != null) ordered += showMore
        return replaceTimelineItems(shellHtml, ordered)
    }

    /** Newest snowflake first so late-loaded peer batches still surface at the top. */
    fun sortNewestFirst(items: List<String>): MutableList<String> =
        items.sortedWith(statusIdDescending).toMutableList()

    private val statusIdDescending = Comparator<String> { a, b ->
        val idA = statusKey(a)
        val idB = statusKey(b)
        when {
            idA == null && idB == null -> 0
            idA == null -> 1
            idB == null -> -1
            else -> compareStatusIds(idB, idA)
        }
    }

    fun compareStatusIds(left: String, right: String): Int {
        val a = left.trimStart('0').ifEmpty { "0" }
        val b = right.trimStart('0').ifEmpty { "0" }
        return when {
            a.length != b.length -> a.length.compareTo(b.length)
            else -> a.compareTo(b)
        }
    }

    fun isShowMoreItem(item: String): Boolean = isShowMore(item)

    fun replaceTimelineItems(shellHtml: String, items: List<String>): String {
        val timelineStart = findTimelineOpen(shellHtml) ?: return shellHtml
        val openEnd = shellHtml.indexOf('>', timelineStart).takeIf { it >= 0 } ?: return shellHtml
        val timelineBlock = extractBalancedDiv(shellHtml, timelineStart) ?: return shellHtml
        val innerStart = openEnd + 1
        val innerEnd = timelineStart + timelineBlock.length - "</div>".length
        if (innerEnd <= innerStart) return shellHtml
        val rebuiltInner = buildString {
            items.forEach { append(it).append('\n') }
        }
        return shellHtml.substring(0, innerStart) + rebuiltInner + shellHtml.substring(innerEnd)
    }

    fun extractTimelineItems(html: String): List<String> {
        val out = ArrayList<String>()
        var searchFrom = 0
        while (searchFrom < html.length) {
            val classIdx = html.indexOf("timeline-item", searchFrom, ignoreCase = true)
            if (classIdx < 0) break
            val open = html.lastIndexOf('<', classIdx)
            if (open < 0 || !html.regionMatches(open, "<div", 0, 4, ignoreCase = true)) {
                searchFrom = classIdx + 13
                continue
            }
            val block = extractBalancedDiv(html, open)
            if (block == null) {
                searchFrom = classIdx + 13
                continue
            }
            out += block
            searchFrom = open + block.length
        }
        return out
    }

    fun filterItems(
        items: List<String>,
        users: List<PerUserFeedFilter>,
        mutedWords: List<String>,
        globalHideRetweets: Boolean,
        globalHideReplies: Boolean
    ): List<String> {
        val byUser = users.associateBy { it.username.lowercase() }
        return items.filter { item ->
            val username = itemUsername(item)
            val rule = username?.let { byUser[it] }
            val hideRetweets = rule?.hideRetweets ?: globalHideRetweets
            val hideReplies = rule?.hideReplies ?: globalHideReplies
            val mutedUser = rule?.mutedInFeed == true
            when {
                mutedUser -> false
                hideRetweets && isRetweet(item) -> false
                hideReplies && isReply(item) -> false
                FeedFilterRules.textMatchesMutedWord(item, mutedWords) -> false
                else -> true
            }
        }
    }

    private fun findTimelineOpen(html: String): Int? {
        val markers = listOf("class=\"timeline\"", "class='timeline'", "id=\"timeline\"", "timeline-container")
        for (marker in markers) {
            val idx = html.indexOf(marker, ignoreCase = true)
            if (idx < 0) continue
            val open = html.lastIndexOf('<', idx)
            if (open >= 0 && html.regionMatches(open, "<div", 0, 4, ignoreCase = true)) return open
        }
        return null
    }

    private fun extractBalancedDiv(html: String, start: Int): String? {
        if (!html.regionMatches(start, "<div", 0, 4, ignoreCase = true)) return null
        var i = start
        var depth = 0
        while (i < html.length) {
            val nextOpen = html.indexOf("<div", i, ignoreCase = true)
            val nextClose = html.indexOf("</div>", i, ignoreCase = true)
            if (nextClose < 0) return null
            if (nextOpen in 0 until nextClose) {
                depth++
                i = nextOpen + 4
            } else {
                depth--
                i = nextClose + 6
                if (depth == 0) return html.substring(start, i)
            }
        }
        return null
    }

    private fun isShowMore(item: String): Boolean =
        showMoreClass.containsMatchIn(item.take(240)) &&
            !item.contains("tweet-content", ignoreCase = true)

    private fun statusKey(item: String): String? =
        statusHref.find(item)?.groupValues?.getOrNull(1)

    private fun itemUsername(item: String): String? {
        val href = Regex("""(?i)href\s*=\s*["']([^"']+)["']""").findAll(item)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains("/status/", ignoreCase = true) }
            ?: return null
        val m = Regex("""(?i)/([A-Za-z0-9_]{1,15})/status/""").find(href)
        return m?.groupValues?.getOrNull(1)?.lowercase()
    }

    private fun isRetweet(item: String): Boolean =
        item.contains("retweet-header", ignoreCase = true) ||
            item.contains("icon-retweet", ignoreCase = true) ||
            Regex("""(?i)class\s*=\s*["'][^"']*retweet[^"']*["']""").containsMatchIn(item.take(400))

    private fun isReply(item: String): Boolean =
        item.contains("replying-to", ignoreCase = true) ||
            Regex("""(?i)class\s*=\s*["'][^"']*\breply\b[^"']*["']""").containsMatchIn(item.take(400))
}
