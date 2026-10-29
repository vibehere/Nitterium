package com.kaleedtc.nitterium.data.security

object FeedFilterScript {
    fun script(
        globalHideRetweets: Boolean,
        globalHideReplies: Boolean,
        users: List<PerUserFeedFilter>,
        mutedWords: List<String>
    ): String {
        val usersJson = users.joinToString(prefix = "{", postfix = "}") { user ->
            val key = jsString(user.username.lowercase())
            """$key:{hideRetweets:${user.hideRetweets},hideReplies:${user.hideReplies},mutedInFeed:${user.mutedInFeed}}"""
        }
        val wordsJson = mutedWords.joinToString(prefix = "[", postfix = "]") { jsString(it.lowercase()) }
        return """
            javascript:(function() {
                var globalHideRetweets = $globalHideRetweets;
                var globalHideReplies = $globalHideReplies;
                var users = $usersJson;
                var mutedWords = $wordsJson;
                var key = JSON.stringify({gR: globalHideRetweets, gP: globalHideReplies, u: users, w: mutedWords});
                if (window.__nitteriumFilterKey === key && window.__nitteriumFilterApplied) {
                    window.__nitteriumApplyFeedFilters && window.__nitteriumApplyFeedFilters();
                    return;
                }
                window.__nitteriumFilterKey = key;

                function profileUsername() {
                    var path = (location.pathname || '').replace(/\/+$/, '');
                    var parts = path.split('/').filter(Boolean);
                    if (!parts.length) return null;
                    var first = parts[0];
                    if (!first || first.indexOf(',') >= 0) return null;
                    if (['search', 'settings', 'about', 'login', 'signup'].indexOf(first.toLowerCase()) >= 0) return null;
                    return first.toLowerCase();
                }

                function isCombinedFeed() {
                    var path = (location.pathname || '').replace(/\/+$/, '');
                    var leaf = path.split('/').filter(Boolean).pop() || '';
                    return leaf.indexOf(',') >= 0;
                }

                function itemUsername(item) {
                    var full = item.querySelector('.fullname-fullnamename, .fullname-name, a.username');
                    if (full) {
                        var t = (full.textContent || '').trim().replace(/^@/, '');
                        if (t) return t.toLowerCase();
                    }
                    var link = item.querySelector('a[href*="/status/"], a.tweet-link, a.fullname-fullnamename');
                    if (!link) return null;
                    var href = link.getAttribute('href') || '';
                    var m = href.match(/\/([A-Za-z0-9_]{1,15})(?:\/status\/|\/?$)/);
                    return m ? m[1].toLowerCase() : null;
                }

                function isRetweet(item) {
                    return !!(item.querySelector('.retweet-header, .retweet, .icon-retweet') ||
                        (item.className && String(item.className).toLowerCase().indexOf('retweet') >= 0));
                }

                function isReply(item) {
                    return !!(item.querySelector('.replying-to, .reply') ||
                        (item.className && String(item.className).toLowerCase().indexOf('reply') >= 0));
                }

                function ruleFor(username) {
                    var u = users[username] || null;
                    return {
                        hideRetweets: u ? !!u.hideRetweets : globalHideRetweets,
                        hideReplies: u ? !!u.hideReplies : globalHideReplies,
                        mutedInFeed: u ? !!u.mutedInFeed : false
                    };
                }

                function matchesMuted(item) {
                    if (!mutedWords.length) return false;
                    var text = (item.innerText || item.textContent || '').toLowerCase();
                    for (var i = 0; i < mutedWords.length; i++) {
                        if (mutedWords[i] && text.indexOf(mutedWords[i]) >= 0) return true;
                    }
                    return false;
                }

                function apply() {
                    var items = document.querySelectorAll('.timeline-item');
                    if (!items.length) return;
                    var combined = isCombinedFeed();
                    var viewing = profileUsername();
                    items.forEach(function(item) {
                        if (item.classList.contains('show-more') || item.classList.contains('timeline-item show-more')) {
                            return;
                        }
                        var username = itemUsername(item);
                        var rule = ruleFor(username || '');
                        var hide = false;
                        if (combined && rule.mutedInFeed && username) hide = true;
                        if (!hide && rule.hideRetweets && isRetweet(item)) hide = true;
                        if (!hide && rule.hideReplies && isReply(item)) hide = true;
                        if (!hide && matchesMuted(item)) hide = true;
                        if (!combined && viewing && username === viewing && rule.mutedInFeed) {
                            hide = false;
                            if (rule.hideRetweets && isRetweet(item)) hide = true;
                            if (rule.hideReplies && isReply(item)) hide = true;
                            if (matchesMuted(item)) hide = true;
                        }
                        item.style.display = hide ? 'none' : '';
                    });
                    window.__nitteriumFilterApplied = true;
                }

                window.__nitteriumApplyFeedFilters = apply;
                apply();
                if (!window.__nitteriumFilterObserver) {
                    var root = document.querySelector('.timeline') ||
                        document.querySelector('#timeline') ||
                        document.body;
                    if (root && window.MutationObserver) {
                        window.__nitteriumFilterObserver = new MutationObserver(function() {
                            apply();
                        });
                        window.__nitteriumFilterObserver.observe(root, { childList: true, subtree: true });
                    }
                }
            })();
        """.trimIndent()
    }

    private fun jsString(value: String): String =
        "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n") + "\""
}
