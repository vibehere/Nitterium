package com.kaleedtc.nitterium.data.security

import java.nio.charset.StandardCharsets
import java.util.Base64

object FeedBatchAppend {
    /** Delay between older/cursor page fetches (rate-limit friendly). */
    const val DEFAULT_DELAY_MS = 1_000
    /**
     * Delay between peer account-batch fetches. Keep short so a small
     * accounts-per-request value still pulls every subscription into All
     * before the user finishes scrolling a group they just viewed.
     */
    const val INITIAL_DELAY_MS = 200
    /** How many peer batches may be in flight at once (clearnet). */
    const val PEER_CONCURRENCY = 4
    /** Tor/SOCKS keeps fewer in-flight fetches to avoid circuit overload. */
    const val PEER_CONCURRENCY_TOR = 2
    /**
     * Extra hosts after a chunk's assigned instance fails. Scoreboard-free so
     * failovers rotate through the pool (Tor-capable when SOCKS is on) instead
     * of collapsing onto a single winner — enough to keep accounts loading.
     */
    const val PEER_FAILOVER_HOSTS = 3
    /** Cap for finding the first working shell host at multi-feed start. */
    const val SHELL_HOST_ATTEMPTS = 5
    const val DEFAULT_OLDER_PAGES_PER_CHUNK = 8

    /** Shared JS helpers for status-id ordering (newest snowflake first). */
    private val timelineHelpersJs = """
        function statusId(href) {
            if (!href) return null;
            var m = String(href).match(/\/status\/(\d+)/);
            return m ? m[1] : null;
        }
        function idLess(a, b) {
            if (!a || !b) return false;
            a = String(a).replace(/^0+/, '') || '0';
            b = String(b).replace(/^0+/, '') || '0';
            if (a.length !== b.length) return a.length < b.length;
            return a < b;
        }
        function resortTimeline(timeline) {
            if (!timeline) return;
            var sentinel = timeline.querySelector('.show-more:not(.timeline-item), .timeline-item.show-more');
            var nodes = Array.prototype.slice.call(
                timeline.querySelectorAll('.timeline-item:not(.show-more)')
            );
            nodes.sort(function(left, right) {
                var linkA = left.querySelector('a.tweet-link, a[href*="/status/"]');
                var linkB = right.querySelector('a.tweet-link, a[href*="/status/"]');
                var idA = statusId(linkA && linkA.getAttribute('href'));
                var idB = statusId(linkB && linkB.getAttribute('href'));
                if (!idA && !idB) return 0;
                if (!idA) return 1;
                if (!idB) return -1;
                if (idLess(idA, idB)) return 1;
                if (idLess(idB, idA)) return -1;
                return 0;
            });
            nodes.forEach(function(node) {
                if (sentinel) timeline.insertBefore(node, sentinel);
                else timeline.appendChild(node);
            });
        }
        function itemKey(item) {
            var link = item.querySelector('a.tweet-link, a[href*="/status/"]');
            var href = link && link.getAttribute('href');
            return statusId(href) || href;
        }
        function rememberTimeline(timeline, seen) {
            timeline.querySelectorAll('.timeline-item:not(.show-more)').forEach(function(item) {
                var key = itemKey(item);
                if (key) seen[key] = true;
            });
        }
        function appendUnseen(timeline, doc, seen) {
            doc.querySelectorAll('.timeline-item').forEach(function(item) {
                if (item.classList.contains('show-more')) return;
                var key = itemKey(item);
                if (key && seen[key]) return;
                if (key) seen[key] = true;
                insertItem(timeline, item, seen);
            });
        }
        function insertItem(timeline, node, seen) {
            var imported = document.importNode(node, true);
            var sentinel = timeline.querySelector('.show-more:not(.timeline-item), .timeline-item.show-more');
            var link = imported.querySelector('a.tweet-link, a[href*="/status/"]');
            var id = statusId(link && link.getAttribute('href'));
            if (id) {
                var items = timeline.querySelectorAll('.timeline-item:not(.show-more)');
                for (var i = 0; i < items.length; i++) {
                    var otherLink = items[i].querySelector('a.tweet-link, a[href*="/status/"]');
                    var otherId = statusId(otherLink && otherLink.getAttribute('href'));
                    if (otherId && idLess(otherId, id)) {
                        timeline.insertBefore(imported, items[i]);
                        return;
                    }
                }
            }
            if (sentinel) timeline.insertBefore(imported, sentinel);
            else timeline.appendChild(imported);
        }
    """.trimIndent()

    fun script(
        urls: List<String>,
        delayMs: Int = DEFAULT_DELAY_MS,
        olderPagesPerChunk: Int = DEFAULT_OLDER_PAGES_PER_CHUNK
    ): String {
        if (urls.isEmpty()) return "javascript:void(0)"
        val safeDelay = delayMs.coerceIn(250, 30_000)
        val olderCap = olderPagesPerChunk.coerceIn(0, 30)
        val peerDelay = INITIAL_DELAY_MS.coerceIn(100, safeDelay)
        val concurrency = PEER_CONCURRENCY.coerceIn(1, 6)
        val json = jsStringArray(urls)
        return """
            javascript:(function() {
                var urls = $json;
                var delayMs = $safeDelay;
                var peerDelayMs = $peerDelay;
                var concurrency = $concurrency;
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
                $timelineHelpersJs
                function showMoreAnchor() {
                    return timeline.querySelector('.show-more:not(.timeline-item) a[href*="cursor"], .show-more a[href*="cursor"]') ||
                        timeline.querySelector('.show-more:not(.timeline-item) a[href], .show-more a[href]');
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
                rememberTimeline(timeline, seen);
                function appendFromHtml(html, pageUrl) {
                    try {
                        var doc = new DOMParser().parseFromString(html, 'text/html');
                        appendUnseen(timeline, doc, seen);
                        queueOlderFrom(doc, pageUrl);
                        resortTimeline(timeline);
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
                function loadPeerBatches() {
                    if (window.__nitteriumBatchKey !== key) return;
                    var next = 0;
                    var inflight = 0;
                    var finished = false;
                    function pump() {
                        if (window.__nitteriumBatchKey !== key) return;
                        while (inflight < concurrency && next < urls.length) {
                            (function(url) {
                                inflight++;
                                loadUrl(url, function() {
                                    inflight--;
                                    if (next < urls.length) {
                                        setTimeout(pump, peerDelayMs);
                                    } else if (inflight === 0 && !finished) {
                                        finished = true;
                                        resortTimeline(timeline);
                                        loadOlder(0);
                                    }
                                });
                            })(urls[next++]);
                        }
                        if (next >= urls.length && inflight === 0 && !finished) {
                            finished = true;
                            resortTimeline(timeline);
                            loadOlder(0);
                        }
                    }
                    pump();
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
                setTimeout(loadPeerBatches, peerDelayMs);
            })();
        """.trimIndent()
    }

    fun injectOnePageScript(html: String): String {
        if (html.isBlank()) return "javascript:void(0)"
        // Base64 keeps huge peer pages under evaluateJavascript string limits.
        val b64 = Base64.getEncoder().encodeToString(html.toByteArray(StandardCharsets.UTF_8))
        return """
            javascript:(function() {
                function decodeHtml(b64) {
                    var bin = atob(b64);
                    var bytes = new Uint8Array(bin.length);
                    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                    return new TextDecoder('utf-8').decode(bytes);
                }
                var html = decodeHtml(${jsString(b64)});
                if (!html) return;
                var timeline = document.querySelector('.timeline') ||
                    document.querySelector('#timeline') ||
                    document.querySelector('.timeline-container');
                if (!timeline) return;
                var seen = window.__nitteriumSeenStatuses || (window.__nitteriumSeenStatuses = Object.create(null));
                $timelineHelpersJs
                try {
                    var doc = new DOMParser().parseFromString(html, 'text/html');
                    rememberTimeline(timeline, seen);
                    appendUnseen(timeline, doc, seen);
                    resortTimeline(timeline);
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
