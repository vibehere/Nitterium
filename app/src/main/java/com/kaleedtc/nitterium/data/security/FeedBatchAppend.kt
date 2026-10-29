package com.kaleedtc.nitterium.data.security

object FeedBatchAppend {
    const val DEFAULT_DELAY_MS = 2_500
    const val DEFAULT_OLDER_PAGES_PER_CHUNK = 8

    fun script(
        urls: List<String>,
        delayMs: Int = DEFAULT_DELAY_MS,
        olderPagesPerChunk: Int = DEFAULT_OLDER_PAGES_PER_CHUNK
    ): String {
        if (urls.isEmpty()) return "javascript:void(0)"
        val safeDelay = delayMs.coerceIn(500, 30_000)
        val olderCap = olderPagesPerChunk.coerceIn(0, 30)
        val json = jsStringArray(urls)
        return """
            javascript:(function() {
                var urls = $json;
                var delayMs = $safeDelay;
                var olderCap = $olderCap;
                if (!urls || !urls.length) return;
                var key = 'urls:' + urls.join('|') + ':older' + olderCap;
                if (window.__nitteriumBatchKey === key) return;
                window.__nitteriumBatchKey = key;
                var timeline = document.querySelector('.timeline') ||
                    document.querySelector('#timeline') ||
                    document.querySelector('.timeline-container');
                if (!timeline) return;
                var seen = window.__nitteriumSeenStatuses || (window.__nitteriumSeenStatuses = Object.create(null));
                var seenPages = Object.create(null);
                var olderQueue = [];
                function remember(root) {
                    root.querySelectorAll('a.tweet-link, a[href*="/status/"]').forEach(function(a) {
                        var href = a.getAttribute('href');
                        if (href) seen[href] = true;
                    });
                }
                function showMoreAnchor() {
                    return timeline.querySelector('.show-more:not(.timeline-item) a[href*="cursor"], .show-more a[href*="cursor"]') ||
                        timeline.querySelector('.show-more:not(.timeline-item) a[href], .show-more a[href]');
                }
                function insertItem(node) {
                    var imported = document.importNode(node, true);
                    var sentinel = timeline.querySelector('.show-more:not(.timeline-item), .timeline-item.show-more');
                    if (sentinel) timeline.insertBefore(imported, sentinel);
                    else timeline.appendChild(imported);
                }
                function queueOlderFrom(doc, pageUrl) {
                    if (olderCap <= 0) return;
                    var more = doc.querySelector('.show-more:not(.timeline-item) a[href], .show-more a[href]');
                    if (!more) return;
                    try {
                        var next = new URL(more.getAttribute('href'), pageUrl || location.href).href;
                        if (!next || seenPages[next]) return;
                        olderQueue.push(next);
                    } catch (e) {}
                }
                remember(timeline);
                function appendFromHtml(html, pageUrl) {
                    try {
                        var doc = new DOMParser().parseFromString(html, 'text/html');
                        var items = doc.querySelectorAll('.timeline-item');
                        items.forEach(function(item) {
                            if (item.classList.contains('show-more')) return;
                            var link = item.querySelector('a.tweet-link, a[href*="/status/"]');
                            var href = link && link.getAttribute('href');
                            if (href && seen[href]) return;
                            if (href) seen[href] = true;
                            insertItem(item);
                        });
                        queueOlderFrom(doc, pageUrl);
                    } catch (e) {}
                }
                function loadUrl(url, done) {
                    if (!url || seenPages[url]) { done(); return; }
                    seenPages[url] = true;
                    fetch(url, { credentials: 'same-origin', cache: 'no-store' })
                        .then(function(r) {
                            if (!r.ok) throw new Error('status');
                            return r.text();
                        })
                        .then(function(html) {
                            appendFromHtml(html, url);
                            if (window.__nitteriumApplyFeedFilters) window.__nitteriumApplyFeedFilters();
                            done();
                        })
                        .catch(function() { done(); });
                }
                function loadNextBatch(i) {
                    if (window.__nitteriumBatchKey !== key) return;
                    if (i >= urls.length) {
                        loadOlder(0);
                        return;
                    }
                    loadUrl(urls[i], function() {
                        setTimeout(function() { loadNextBatch(i + 1); }, delayMs);
                    });
                }
                function loadOlder(depth) {
                    if (window.__nitteriumBatchKey !== key) return;
                    if (!olderQueue.length || depth >= olderCap * urls.length) return;
                    var next = olderQueue.shift();
                    loadUrl(next, function() {
                        setTimeout(function() { loadOlder(depth + 1); }, delayMs);
                    });
                }
                var shellMore = showMoreAnchor();
                if (shellMore) {
                    try { seenPages[new URL(shellMore.href, location.href).href] = true; } catch (e) {}
                }
                setTimeout(function() { loadNextBatch(0); }, delayMs);
            })();
        """.trimIndent()
    }

    fun injectOnePageScript(html: String): String {
        if (html.isBlank()) return "javascript:void(0)"
        val encoded = jsString(html)
        return """
            javascript:(function() {
                var html = $encoded;
                if (!html) return;
                var timeline = document.querySelector('.timeline') ||
                    document.querySelector('#timeline') ||
                    document.querySelector('.timeline-container');
                if (!timeline) return;
                var seen = window.__nitteriumSeenStatuses || (window.__nitteriumSeenStatuses = Object.create(null));
                try {
                    var doc = new DOMParser().parseFromString(html, 'text/html');
                    var items = doc.querySelectorAll('.timeline-item');
                    var sentinel = timeline.querySelector('.show-more:not(.timeline-item), .timeline-item.show-more');
                    items.forEach(function(item) {
                        if (item.classList.contains('show-more')) return;
                        var link = item.querySelector('a.tweet-link, a[href*="/status/"]');
                        var href = link && link.getAttribute('href');
                        if (href && seen[href]) return;
                        if (href) seen[href] = true;
                        var imported = document.importNode(item, true);
                        if (sentinel) timeline.insertBefore(imported, sentinel);
                        else timeline.appendChild(imported);
                    });
                    if (window.__nitteriumApplyFeedFilters) window.__nitteriumApplyFeedFilters();
                } catch (e) {}
            })();
        """.trimIndent()
    }

    private fun jsStringArray(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { jsString(it) }

    private fun jsString(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("<", "\\u003c")
            .replace(">", "\\u003e") +
            "\""
}
