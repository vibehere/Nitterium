package com.kaleedtc.nitterium.ui.common

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import com.kaleedtc.nitterium.data.repository.CookieInstanceOptionsStore
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.kaleedtc.nitterium.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.kaleedtc.nitterium.data.frontend.FrontendType
import com.kaleedtc.nitterium.data.frontend.FrontendAdapter
import com.kaleedtc.nitterium.data.frontend.InstanceSettingsCookies
import com.kaleedtc.nitterium.data.repository.UserPreferencesRepository
import java.net.URI
import com.kaleedtc.nitterium.data.model.Subscription
import com.kaleedtc.nitterium.data.security.FeedBatchAppend
import com.kaleedtc.nitterium.data.security.FeedFilterRules
import com.kaleedtc.nitterium.data.security.FeedFilterScript
import com.kaleedtc.nitterium.data.security.NoJsHtmlDocument
import com.kaleedtc.nitterium.data.security.PrivacyHeaders
import com.kaleedtc.nitterium.data.security.TimelineHtml
import com.kaleedtc.nitterium.data.security.UrlPolicy
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private fun WebView.loadPrivacyUrl(url: String) {
    loadUrl(url, PrivacyHeaders.navigationHeaders())
}

/** HTTPS/onion base for [WebView.loadDataWithBaseURL]; never "/" or blank (those become data:). */
private fun feedDocumentBaseUrl(instance: String, pageUrl: String): String? {
    UrlPolicy.origin(instance)?.let { return "$it/" }
    return UrlPolicy.origin(pageUrl)?.let { "$it/" }
}

/** Start loading older posts while this many screens of feed remain below. */
private const val FEED_OLDER_PREFETCH_SCREENS = 2

private class FeedShellDocument(
    val base: String,
    val html: String,
    val key: String,
    val peerCount: Int
)

/** [historyUrl] keeps the instance origin so the page bridge and JS injection attach. */
private fun WebView.loadFeedDocument(base: String, html: String, historyUrl: String) {
    loadDataWithBaseURL(
        base,
        html,
        "text/html",
        StandardCharsets.UTF_8.name(),
        historyUrl.ifBlank { base }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NitterWebView(
    url: String,
    selectedInstanceUrl: String,
    isTrueBlack: Boolean,
    isSiteHeaderEnabled: Boolean,
    isBlockDirectXEnabled: Boolean,
    darkTheme: Boolean,
    onPageStarted: (String) -> Unit,
    onPageFinished: (String, WebView) -> Unit,
    onPageError: (Int, String) -> Unit,
    onWebViewCreated: (WebView) -> Unit,
    modifier: Modifier = Modifier,
    frontend: FrontendType = FrontendType.NITTER,
    onFrontendDetected: (String, FrontendType) -> Unit = { _, _ -> },
    isRefreshing: Boolean = false,
    /** Increment to clear WebView history and force-load [url] (error Retry / pull-refresh). */
    loadGeneration: Int = 0,
    isProfileView: Boolean = false,
    isFeedView: Boolean = false,
    feedAppendBatchUrls: List<String> = emptyList(),
    feedAppendHtmlChunks: List<String> = emptyList(),
    /** OkHttp shell HTML for no-JS Feed All merges. */
    feedShellHtml: String? = null,
    /** Stylesheet links from the shell host so Feed All matches normal Nitter posts. */
    feedShellStyleAssets: String? = null,
    /** Subscriptions used to resolve per-user feed filters (retweets/replies/mute). */
    filterSubscriptions: List<Subscription> = emptyList(),
    mutedWords: List<String> = emptyList(),
    /**
     * Feed All owns post/profile/comment navigation. Parent calls the returned
     * goBack when the in-app back affordance is used; it remounts the retained
     * shell without WebView history or a network refetch.
     */
    onFeedBrowseBackReady: ((() -> Boolean)?) -> Unit = {},
    onFeedBrowseCanGoBackChange: (Boolean) -> Unit = {},
    onFeedNearBottom: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onAvatarFound: (String, String) -> Unit = { _, _ -> },
) {
    // 0. State for Image Viewer
    var clickedImageState by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }

    // 0.1 State for Full Screen Video
    var customView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    val fullScreenMode = LocalFullScreenMode.current
    var savedScrollX by rememberSaveable { mutableIntStateOf(0) }
    var savedScrollY by rememberSaveable { mutableIntStateOf(0) }

    var longPressedUrl by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val positionScript = remember { context.assets.open("reading-position.js").bufferedReader().use { it.readText() } }
    val preferencesRepository = remember { UserPreferencesRepository(context) }
    val instanceKeys by preferencesRepository.instanceKeys.collectAsState(initial = emptyMap())
    val instanceKey = instanceKeys[UserPreferencesRepository.hostOf(url)].orEmpty()
    val disableWebJavascript by preferencesRepository.disableWebJavascript.collectAsState(initial = false)
    val javaScriptEnabled = !disableWebJavascript

    DisposableEffect(Unit) {
        onDispose {
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            customView = null
        }
    }

    // 1. Capture current theme colors
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val primaryColor = MaterialTheme.colorScheme.primary

    // 2. Define CSS generation logic
    fun generateThemeCss(
        bg: Color,
        surface: Color,
        text: Color,
        textVariant: Color,
        border: Color,
        primary: Color
    ): String {
        val bgHex = "#%06X".format(0xFFFFFF and bg.toArgb())
        val surfaceHex = "#%06X".format(0xFFFFFF and surface.toArgb())
        val textHex = "#%06X".format(0xFFFFFF and text.toArgb())
        val textVariantHex = "#%06X".format(0xFFFFFF and textVariant.toArgb())
        val borderHex = "#%06X".format(0xFFFFFF and border.toArgb())
        val primaryHex = "#%06X".format(0xFFFFFF and primary.toArgb())

        return """
            :root {
                --bg-color: $bgHex !important;
                --bg-color-alt: $surfaceHex !important;
                --fg-color: $textHex !important;
                --panel-bg: $surfaceHex !important;
                --border-color: $borderHex !important;
            }
            body { 
                --bg_color: $bgHex !important;
                --bg_panel: $surfaceHex !important;
                --bg_elements: $surfaceHex !important;
                --bg_overlays: $surfaceHex !important;
                --bg_hover: $surfaceHex !important;
                --fg_color: $textHex !important;
                --fg_dark: $textHex !important;
                --fg_nav: $textHex !important;
                --fg_faded: $textVariantHex !important;
                --grey: $textVariantHex !important;
                --border_grey: $borderHex !important;
                --dark_grey: $borderHex !important;
                --darker_grey: $borderHex !important;
                --darkest_grey: $surfaceHex !important;
                --accent: $primaryHex !important;
                --accent_light: $primaryHex !important;
                --accent_border: $borderHex !important;
                --tab: $textVariantHex !important;
                --tab_selected: $primaryHex !important;
                --profile_stat: $textHex !important;
                background-color: $bgHex !important; 
                color: $textHex !important;
            }
            .timeline-item, .reply, .card, .card-container, .quote, .quote-big,
            .profile-card, .profile-card-info, .profile-card-avatar,
            .tab-item, .fullname, .replying-to, .show-more, .top-ref,
            .profile-joindate, .profile-website, .profile-card-extra, .profile-card-extra-links,
            .profile-stat-header, .profile-stat-num,
            .photo-rail-card, .header, .tabs,
            .timeline-item:hover, .timeline-item:focus, .timeline-item:active,
            .reply:hover, .reply:focus, .reply:active,
            .card:hover, .card:focus, .card:active,
            .show-more:hover, .show-more:active, .show-more:focus,
            .more-replies, .more-replies:hover {
                border-color: $borderHex !important;
                background-color: $surfaceHex !important;
                color: $textHex !important;
            }
            .show-more a, .more-replies a {
                color: $primaryHex !important;
                background-color: transparent !important;
                font-weight: bold !important;
                text-decoration: none !important;
                display: block !important;
                width: 100% !important;
                padding: 10px 0 !important;
            }
            .username, .profile-card-username {
                color: $textVariantHex !important;
            }
            .tab-item a {
                color: $textVariantHex !important;
            }
            .main-thread { 
                background-color: $bgHex !important; 
                border-color: $borderHex !important; 
            }
            
            /* Site Header Theming */
            nav, .site-header {
                background-color: $surfaceHex !important;
                border-bottom: 1px solid $borderHex !important;
                color: $textHex !important;
            }
            .site-logo, .site-name {
                color: $textHex !important;
            }
            .search-header {
                background-color: $surfaceHex !important;
            }
            /* Search Field Theming */
            input[type="text"], .search-field, form[action*="search"] input {
                background-color: $bgHex !important;
                color: $textHex !important;
                border: 1px solid $borderHex !important;
            }
            input[type="text"]::placeholder {
                color: $textHex !important;
                opacity: 0.7;
            }
            button, .search-btn {
                background-color: $surfaceHex !important;
                color: $textHex !important;
                border: 1px solid $borderHex !important;
            }
            a, a:visited { color: #1d9bf0; } /* Twitter Blue for links is usually safe */
            
            /* Force LTR for headers and stats regardless of tweet content direction */
            .tweet-header, .tweet-stats, .tweet-name-row, .replying-to {
                direction: ltr !important;
                text-align: left !important;
            }
        """.trimIndent()
    }

    val currentThemeCss = generateThemeCss(
        backgroundColor,
        surfaceColor,
        onSurfaceColor,
        onSurfaceVariantColor,
        borderColor,
        primaryColor
    )

    // Additional True Black Override (if enabled and in dark mode)
    val trueBlackCss = if (isTrueBlack && darkTheme) {
        """
        :root {
            --bg-color: #000000 !important;
            --bg-color-alt: #000000 !important;
            --panel-bg: #000000 !important;
        }
        body, .timeline-item, .reply, .card, .main-thread, .show-more, .more-replies, .header, .tabs { background-color: #000000 !important; }
        body { --bg_color: #000000 !important; --bg_panel: #000000 !important; --bg_elements: #000000 !important; }
        """
    } else ""

    fun getSiteHeaderCss(currentUrl: String?): String {
        // Feed All root only — never treat an in-page profile browse as the multi-user shell.
        val isActuallyFeedRoot = isFeedView && !isProfileView && currentUrl != null && try {
            FrontendAdapter.sameOrigin(url, currentUrl) &&
                URI(url).path.trimEnd('/') == URI(currentUrl).path.trimEnd('/')
        } catch (_: Exception) {
            false
        }
        // The native feed toolbar already identifies this timeline. Teapot renders
        // a separate account-list heading outside the shared Nitter header classes.
        val feedHeadingCss = if (isActuallyFeedRoot) {
            ".multi-user-timeline > h2 { display: none !important; }"
        } else ""
        if (isSiteHeaderEnabled) return feedHeadingCss

        // Always use the strict chrome hide (including on profiles). A weaker profile
        // list left search/Preferences visible once instance style.css was restored.
        var css = TimelineHtml.SITE_CHROME_HIDE_CSS
        if (isActuallyFeedRoot) {
            css += TimelineHtml.FEED_SHELL_EXTRA_HIDE_CSS
        }
        return "$css $feedHeadingCss"
    }

    fun getJsInjection(currentUrl: String?): String {
        val headerCss = getSiteHeaderCss(currentUrl)
        val instanceOptions = InstanceSettingsCookies.read(
            runCatching { CookieManager.getInstance().getCookie(currentUrl ?: url).orEmpty() }.getOrDefault(""), frontend
        )
        val squareAvatars = instanceOptions.squareAvatars == true
        val stickyProfile = instanceOptions.stickyProfile == true
        val localeScript = PrivacyHeaders.navigatorLocaleScript()
        return """
            javascript:(function() {
                $localeScript

                function updateStyle(id, css) {
                    var style = document.getElementById(id);
                    if (!style) {
                        style = document.createElement('style');
                        style.id = id;
                        var head = document.head || document.getElementsByTagName('head')[0] || document.documentElement;
                        if (head) {
                            head.appendChild(style);
                        }
                    }
                    if (style && style.innerHTML !== css) {
                        style.innerHTML = css;
                    }
                }

                updateStyle('app-theme-override', `$currentThemeCss`);
                updateStyle('true-black-override', `$trueBlackCss`);
                updateStyle('header-override', `$headerCss`);
                updateStyle('hide-retweets-override', ``);

                updateStyle('general-cleanup', `
                    body, .container, .timeline-container { padding-top: 0 !important; margin-top: 0 !important; }
                    body.fixed-nav .container, body.fixed-nav .timeline-container { padding-top: 0 !important; }
                    body.fixed-nav .profile-tab { top: 0 !important; }
                    .tabs { margin-top: 0 !important; padding-top: 0 !important; }
                    .timeline { margin-top: 0 !important; border-top: none !important; border-radius: 0 !important; }
                    .tweet-published { display: none !important; }
                `);

                function fixDirection() {
                    // History snapshots and newly inserted posts may contain
                    // avatar classes from before the instance options changed.
                    document.querySelectorAll('img.avatar').forEach(function(el) {
                        el.classList.toggle('round', ${!squareAvatars});
                    });
                    document.querySelectorAll('.profile-tab').forEach(function(el) {
                        el.classList.toggle('sticky', $stickyProfile);
                    });
                    var contentSelectors = [
                        '.tweet-content', '.quote-text', '.card-title', '.card-description',
                        '.reply-content', '.reply-text', '.tweet-text', '.comment-text'
                    ].join(',');

                    document.querySelectorAll(contentSelectors).forEach(function(el) {
                        el.setAttribute('dir', 'auto');
                        el.style.setProperty('text-align', 'start', 'important');

                        // Do not force display/width in grid views, as it breaks the grid layout
                        if (el.closest('.timeline-grid') || el.closest('.photo-rail-grid')) {
                            return;
                        }

                        if (window.getComputedStyle(el).display !== 'none') {
                            el.style.setProperty('display', 'inline-block', 'important');
                            el.style.setProperty('width', '100%', 'important');
                        }
                    });
                }

                if ($darkTheme) {
                    if (document.body) document.body.classList.add('dark');
                    if (document.documentElement) document.documentElement.style.colorScheme = 'dark';
                } else {
                    if (document.body) document.body.classList.remove('dark', 'h-dark');
                    if (document.documentElement) document.documentElement.style.colorScheme = 'light';
                }

                if (!window.nitterImageListenerAttached) {
                    document.addEventListener('click', function(e) {
                        var target = e.target;
                        while (target && target.tagName !== 'A') {
                            target = target.parentElement;
                        }
                        if (target && target.tagName === 'A') {
                            var isImage = /\.(jpg|jpeg|png|gif|webp)($|\?|#)/i.test(target.href) || 
                                          target.href.includes('/pic/') || 
                                          target.closest('.profile-banner') !== null || 
                                          target.closest('.profile-card-avatar') !== null;
                            if (isImage) {
                                e.preventDefault();
                                e.stopPropagation();
                                
                                var container = target.closest('.attachments') || target.closest('.gallery-row');
                                var imageLinks = [];
                                var clickedIndex = 0;
                                
                                if (container) {
                                    var links = Array.from(container.querySelectorAll('a'));
                                    links.forEach(function(link) {
                                        var linkIsImage = /\.(jpg|jpeg|png|gif|webp)($|\?|#)/i.test(link.href) || 
                                                          link.href.includes('/pic/') || 
                                                          link.closest('.profile-banner') !== null || 
                                                          link.closest('.profile-card-avatar') !== null;
                                        if (linkIsImage) {
                                            imageLinks.push(link.href);
                                            if (link.href === target.href) {
                                                clickedIndex = imageLinks.length - 1;
                                            }
                                        }
                                    });
                                } else {
                                    imageLinks.push(target.href);
                                }
                                if (window.NitterAndroid) NitterAndroid.openGallery(JSON.stringify(imageLinks), clickedIndex);
                            }
                        }
                    }, true);
                    window.nitterImageListenerAttached = true;
                }

                if (!window.nitterContextMenuListenerAttached) {
                    document.addEventListener('contextmenu', function(e) {
                        var target = e.target;
                        while (target && target.tagName !== 'A') {
                            target = target.parentElement;
                        }
                        if (target && target.tagName === 'A' && target.href) {
                            if (window.NitterAndroid && NitterAndroid.handleLongPress) {
                                NitterAndroid.handleLongPress(target.href);
                            }
                        }
                    }, true);
                    window.nitterContextMenuListenerAttached = true;
                }

                if (!window.nitteriumTweetOpenAttached) {
                    document.addEventListener('click', function(e) {
                        var target = e.target;
                        if (!target || !target.closest) return;
                        if (target.closest('a, button, video, audio, input, textarea, select, .gallery-row, .attachments, .tweet-stats, .icon-container, .video-container')) {
                            return;
                        }
                        var item = target.closest('.timeline-item, .main-tweet, .reply');
                        if (!item) return;
                        var link = item.querySelector('a.tweet-link, .tweet-date a[href*="/status/"], a[href*="/status/"]');
                        if (!link || !link.href) return;
                        e.preventDefault();
                        e.stopPropagation();
                        window.location.href = link.href;
                    }, false);
                    window.nitteriumTweetOpenAttached = true;
                }

                function linkifyMultiFeedHeading() {
                    var heading = document.querySelector('.multi-user-timeline > h2, .timeline-header h2, .timeline-header');
                    if (!heading || heading.dataset.nitteriumLinked === '1') return;
                    var text = (heading.textContent || '').trim();
                    if (!text || text.indexOf(',') < 0) return;
                    var parts = text.split(',').map(function(part) { return part.trim(); }).filter(Boolean);
                    if (parts.length < 2 || parts.some(function(part) { return !/^[A-Za-z0-9_]{1,15}$/.test(part); })) return;
                    heading.innerHTML = parts.map(function(name) {
                        return '<a href="/' + name + '">@' + name + '</a>';
                    }).join(', ');
                    heading.dataset.nitteriumLinked = '1';
                }
                linkifyMultiFeedHeading();

                fixDirection();
                if (document.body) {
                    new MutationObserver(function() {
                        fixDirection();
                        linkifyMultiFeedHeading();
                    }).observe(document.body, { childList: true, subtree: true });
                }
            })()
        """.trimIndent()
    }

    val nitterThemeCookieValue = remember(frontend, darkTheme, isTrueBlack) {
        FrontendAdapter.forType(frontend).themeCookie(darkTheme, isTrueBlack)
    }

    val instanceOptions = remember { CookieInstanceOptionsStore() }

    val rendererGoneError = stringResource(R.string.renderer_gone_error)
    val instanceUnavailableError = stringResource(R.string.instance_unavailable_error)
    val latestJsInjection by rememberUpdatedState { url: String? -> getJsInjection(url) }
    val latestOnFrontendDetected by rememberUpdatedState(onFrontendDetected)
    val latestOnPageStarted by rememberUpdatedState(onPageStarted)
    val latestOnPageFinished by rememberUpdatedState(onPageFinished)
    val latestOnPageError by rememberUpdatedState(onPageError)
    val latestInstance by rememberUpdatedState(selectedInstanceUrl)
    val latestOnAvatarFound by rememberUpdatedState(onAvatarFound)
    val latestFeedAppendBatches by rememberUpdatedState(feedAppendBatchUrls)
    val latestFeedAppendHtml by rememberUpdatedState(feedAppendHtmlChunks)
    val latestFeedShellHtml by rememberUpdatedState(feedShellHtml)
    val latestFeedShellStyleAssets by rememberUpdatedState(feedShellStyleAssets)
    val latestIsFeedView by rememberUpdatedState(isFeedView)
    val latestIsProfileView by rememberUpdatedState(isProfileView)
    val latestFilterSubscriptions by rememberUpdatedState(filterSubscriptions)
    val latestMutedWords by rememberUpdatedState(mutedWords)
    val latestJavaScriptEnabled by rememberUpdatedState(javaScriptEnabled)
    var injectedHtmlCount by remember(url) { mutableIntStateOf(0) }
    var loadedNoJsDocumentKey by remember(url) { mutableStateOf<String?>(null) }
    // Owned Feed All browse stack (post → comments → profile). WebView history is
    // not used for back — goBack remounts the retained OkHttp shell in memory.
    val feedNavStackRef = remember { AtomicReference(emptyList<String>()) }
    var feedNavDepth by remember { mutableIntStateOf(0) }
    // True while the in-memory Feed All document is on screen. A remote page start is
    // only a user click when it leaves this document; any other live load (restore,
    // redirects) must not be pushed or the shell never mounts until Back is pressed.
    val feedShellShowingRef = remember { AtomicBoolean(false) }
    var feedShellShowing by remember { mutableStateOf(false) }
    // The shell reports the feed URL as its page URL; this marks its own page start.
    val feedShellLoadPendingRef = remember { AtomicBoolean(false) }

    fun setFeedNavStack(stack: List<String>) {
        feedNavStackRef.set(stack)
        feedNavDepth = stack.size
    }

    fun setFeedShellShowing(showing: Boolean): Boolean {
        val was = feedShellShowingRef.getAndSet(showing)
        feedShellShowing = showing
        return was
    }

    fun mountFeedDocument(view: WebView, base: String, html: String) {
        setFeedShellShowing(true)
        feedShellLoadPendingRef.set(true)
        view.loadFeedDocument(base, html, url)
    }

    fun applyFeedFilters(view: WebView, pageUrl: String?) {
        if (!latestJavaScriptEnabled) return
        if (!UrlPolicy.sameOrigin(selectedInstanceUrl, pageUrl ?: view.url)) return
        val options = InstanceSettingsCookies.read(
            runCatching { CookieManager.getInstance().getCookie(pageUrl ?: url).orEmpty() }.getOrDefault(""),
            frontend
        )
        val users = FeedFilterRules.perUserFilters(
            latestFilterSubscriptions,
            globalHideRetweets = options.hideRetweets,
            globalHideReplies = options.hideReplies
        )
        view.evaluateJavascript(
            FeedFilterScript.script(
                globalHideRetweets = options.hideRetweets,
                globalHideReplies = options.hideReplies,
                users = users,
                mutedWords = latestMutedWords
            ),
            null
        )
    }

    fun noJsStyleCss(pageUrl: String?): String = buildString {
        append(currentThemeCss)
        append('\n')
        append(trueBlackCss)
        append('\n')
        append(getSiteHeaderCss(pageUrl))
        append(
            """
            body, .container, .timeline-container { padding-top: 0 !important; margin-top: 0 !important; }
            body.fixed-nav .container, body.fixed-nav .timeline-container { padding-top: 0 !important; }
            body.fixed-nav .profile-tab { top: 0 !important; }
            .tabs { margin-top: 0 !important; padding-top: 0 !important; }
            .timeline { margin-top: 0 !important; border-top: none !important; border-radius: 0 !important; }
            .tweet-published { display: none !important; }
            """.trimIndent()
        )
    }

    /**
     * Chrome-free Feed All document: shell + every peer page received so far, merged,
     * filtered and sorted in one load so nothing races a post-load JS inject.
     */
    fun feedShellCss(): String =
        noJsStyleCss(url) + TimelineHtml.SITE_CHROME_HIDE_CSS + TimelineHtml.FEED_SHELL_EXTRA_HIDE_CSS

    /** Identifies the mounted shell document; JS mode injects later peers in place. */
    fun feedShellKey(): String? {
        val shell = latestFeedShellHtml
        if (!latestIsFeedView || shell.isNullOrBlank() || url.isBlank()) return null
        val styled = "${shell.length}:${feedShellCss().length}:${latestFeedShellStyleAssets.orEmpty().length}"
        return if (latestJavaScriptEnabled) {
            "js:$styled"
        } else {
            "nojs:$styled:${latestFeedAppendHtml.size}:${latestFilterSubscriptions.hashCode()}:${latestMutedWords.hashCode()}"
        }
    }

    fun buildFeedShellDocument(): FeedShellDocument? {
        val key = feedShellKey() ?: return null
        val shell = latestFeedShellHtml ?: return null
        val base = feedDocumentBaseUrl(latestInstance, url) ?: return null
        val options = InstanceSettingsCookies.read(
            runCatching { CookieManager.getInstance().getCookie(url).orEmpty() }.getOrDefault(""),
            frontend
        )
        val users = FeedFilterRules.perUserFilters(
            latestFilterSubscriptions,
            globalHideRetweets = options.hideRetweets,
            globalHideReplies = options.hideReplies
        )
        val peers = latestFeedAppendHtml
        val html = NoJsHtmlDocument.prepare(
            html = shell,
            styleCss = feedShellCss(),
            peerPages = peers,
            filterUsers = users,
            mutedWords = latestMutedWords,
            globalHideRetweets = options.hideRetweets,
            globalHideReplies = options.hideReplies,
            styleAssets = latestFeedShellStyleAssets.orEmpty()
        )
        return FeedShellDocument(base = base, html = html, key = key, peerCount = peers.size)
    }

    fun remountMergedFeedShell(view: WebView): Boolean {
        val document = buildFeedShellDocument() ?: return false
        loadedNoJsDocumentKey = document.key
        injectedHtmlCount = document.peerCount
        view.stopLoading()
        view.alpha = 1f
        view.clearHistory()
        mountFeedDocument(view, document.base, document.html)
        return true
    }

    fun pushFeedBrowse(view: WebView, dest: String) {
        val stack = feedNavStackRef.get()
        if (stack.lastOrNull()?.trimEnd('/')?.equals(dest.trimEnd('/'), ignoreCase = true) == true) {
            return
        }
        setFeedNavStack(stack + dest)
        feedShellLoadPendingRef.set(false)
        view.stopLoading()
        view.alpha = 1f
        view.loadPrivacyUrl(dest)
    }

    fun popFeedBrowse(view: WebView): Boolean {
        val stack = feedNavStackRef.get()
        if (stack.isEmpty()) {
            if (feedShellShowingRef.get()) return false
            return remountMergedFeedShell(view)
        }
        val next = stack.dropLast(1)
        setFeedNavStack(next)
        if (next.isEmpty()) {
            return remountMergedFeedShell(view)
        }
        view.stopLoading()
        view.alpha = 1f
        view.loadPrivacyUrl(next.last())
        return true
    }

    val latestPushFeedBrowse by rememberUpdatedState { v: WebView, dest: String -> pushFeedBrowse(v, dest) }
    val latestPopFeedBrowse by rememberUpdatedState { v: WebView -> popFeedBrowse(v) }
    val latestSyncFeedNavTip by rememberUpdatedState { pageUrl: String ->
        val stack = feedNavStackRef.get()
        if (stack.isNotEmpty() &&
            !stack.last().trimEnd('/').equals(pageUrl.trimEnd('/'), ignoreCase = true)
        ) {
            setFeedNavStack(stack.dropLast(1) + pageUrl)
        }
    }
    val latestOnFeedBrowseBackReady by rememberUpdatedState(onFeedBrowseBackReady)
    val latestOnFeedBrowseCanGoBackChange by rememberUpdatedState(onFeedBrowseCanGoBackChange)
    val latestOnFeedNearBottom by rememberUpdatedState(onFeedNearBottom)

    // Request interception runs on a background thread.
    val blockDirectXState = remember { AtomicBoolean(isBlockDirectXEnabled) }
    blockDirectXState.set(isBlockDirectXEnabled)
    val selectedInstanceState = remember { AtomicReference(selectedInstanceUrl) }
    selectedInstanceState.set(selectedInstanceUrl)
    val pageBridge = remember {
        SecurePageBridge(
            selectedInstance = { latestInstance },
            blockDirectX = { blockDirectXState.get() },
            onGallery = { urls, index -> clickedImageState = urls to index },
            onLongPress = { longPressedUrl = it }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        var webViewRef by remember { mutableStateOf<WebView?>(null) }

        LaunchedEffect(instanceKey, webViewRef) {
            val view = webViewRef ?: return@LaunchedEffect
            val wanted = UserPreferencesRepository.withAccessKey(view.settings.userAgentString, instanceKey)
            if (view.settings.userAgentString != wanted) {
                view.settings.userAgentString = wanted
                view.reload()
            }
        }

        val webViewStateBundle = rememberSaveable(
            saver = Saver(
                save = { saved ->
                    Bundle(saved).also { bundle -> webViewRef?.saveReadingState(bundle) }
                },
                restore = { it }
            )
        ) { Bundle() }

        val lifecycleOwner = LocalLifecycleOwner.current

        // Re-inject theme when it changes to ensure it's always up to date
        // even without a page reload (e.g. when switching system theme).
        LaunchedEffect(currentThemeCss, trueBlackCss, darkTheme, frontend, isSiteHeaderEnabled, javaScriptEnabled) {
            if (!javaScriptEnabled) return@LaunchedEffect
            webViewRef?.let { wv ->
                if (UrlPolicy.sameOrigin(latestInstance, wv.url)) {
                    wv.evaluateJavascript(latestJsInjection(wv.url), null)
                }
            }
        }

        LaunchedEffect(url) {
            injectedHtmlCount = 0
            loadedNoJsDocumentKey = null
            setFeedNavStack(emptyList())
        }

        val pendingNoJsScrollRef = remember {
            java.util.concurrent.atomic.AtomicReference<Pair<Int, Int>?>(null)
        }
        var appliedLoadGeneration by remember { mutableIntStateOf(0) }

        LaunchedEffect(feedNavDepth, feedShellShowing, webViewRef, feedShellHtml, isFeedView) {
            val active = isFeedView && !feedShellHtml.isNullOrBlank() && webViewRef != null
            if (!active) {
                latestOnFeedBrowseBackReady(null)
                latestOnFeedBrowseCanGoBackChange(false)
                return@LaunchedEffect
            }
            latestOnFeedBrowseCanGoBackChange(feedNavDepth > 0 || !feedShellShowing)
            latestOnFeedBrowseBackReady {
                val view = webViewRef ?: return@latestOnFeedBrowseBackReady false
                latestPopFeedBrowse(view)
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                latestOnFeedBrowseBackReady(null)
                latestOnFeedBrowseCanGoBackChange(false)
            }
        }

        // Only when loadGeneration bumps (Retry/Refresh)—not on every in-page URL change.
        LaunchedEffect(loadGeneration, webViewRef) {
            if (loadGeneration <= 0 || loadGeneration == appliedLoadGeneration) return@LaunchedEffect
            val view = webViewRef ?: return@LaunchedEffect
            val target = url
            if (target.isEmpty() || !UrlPolicy.sameOrigin(latestInstance, target)) return@LaunchedEffect
            appliedLoadGeneration = loadGeneration
            webViewStateBundle.clear()
            injectedHtmlCount = 0
            loadedNoJsDocumentKey = null
            setFeedNavStack(emptyList())
            pendingNoJsScrollRef.set(null)
            view.stopLoading()
            view.clearHistory()
            if (!remountMergedFeedShell(view)) {
                view.loadPrivacyUrl(target)
            }
        }

        // Apply JS on/off without destroying the WebView (avoids blank/jumpy remounts).
        var previousJavaScriptEnabled by remember { mutableStateOf<Boolean?>(null) }
        LaunchedEffect(javaScriptEnabled, webViewRef) {
            val view = webViewRef ?: return@LaunchedEffect
            view.settings.javaScriptEnabled = javaScriptEnabled
            view.settings.domStorageEnabled = javaScriptEnabled
            val previous = previousJavaScriptEnabled
            previousJavaScriptEnabled = javaScriptEnabled
            if (previous == null || previous == javaScriptEnabled) return@LaunchedEffect
            injectedHtmlCount = 0
            loadedNoJsDocumentKey = null
            if (feedNavStackRef.get().isEmpty() && remountMergedFeedShell(view)) return@LaunchedEffect
            if (url.isNotEmpty() && UrlPolicy.sameOrigin(latestInstance, url)) {
                view.loadPrivacyUrl(url)
            }
        }

        LaunchedEffect(
            feedShellHtml,
            feedShellStyleAssets,
            feedAppendHtmlChunks.size,
            filterSubscriptions,
            mutedWords,
            currentThemeCss,
            trueBlackCss,
            isSiteHeaderEnabled,
            javaScriptEnabled,
            webViewRef,
            url,
            feedNavDepth,
            feedShellShowing
        ) {
            val view = webViewRef ?: return@LaunchedEffect
            val shell = latestFeedShellHtml
            // Owned browse stack is showing a remote post/profile — do not remount shell.
            if (feedNavDepth > 0) return@LaunchedEffect
            val shellOnScreen = feedShellShowingRef.get()
            // Feed All with OkHttp shell: render a chrome-free document instead of the
            // live Nitter page (avoids logo/nav flash). Works for JS and no-JS.
            if (latestIsFeedView && !shell.isNullOrBlank() && url.isNotBlank()) {
                instanceOptions.ensureProxyDefaults(url)
                if (blockDirectXState.get()) instanceOptions.ensureMediaProxyEnabled(url)
                val key = feedShellKey() ?: return@LaunchedEffect
                if (loadedNoJsDocumentKey != key || !shellOnScreen) {
                    // No-JS merges re-render per peer page; keep the reader's place.
                    if (!javaScriptEnabled && shellOnScreen && loadedNoJsDocumentKey != null) {
                        pendingNoJsScrollRef.set(view.scrollX to view.scrollY)
                    }
                    remountMergedFeedShell(view)
                    return@LaunchedEffect
                }
            }
            if (!javaScriptEnabled) return@LaunchedEffect
            if (!latestIsFeedView) return@LaunchedEffect
            if (!feedShellShowingRef.get()) return@LaunchedEffect
            val pages = latestFeedAppendHtml
            if (pages.size <= injectedHtmlCount) return@LaunchedEffect
            for (index in injectedHtmlCount until pages.size) {
                val html = pages[index]
                if (html.isNotBlank()) {
                    view.evaluateJavascript(FeedBatchAppend.injectOnePageScript(html), null)
                }
                injectedHtmlCount = index + 1
            }
            applyFeedFilters(view, view.url)
        }

        LaunchedEffect(filterSubscriptions, mutedWords, webViewRef, url, javaScriptEnabled) {
            if (!javaScriptEnabled) return@LaunchedEffect
            val view = webViewRef ?: return@LaunchedEffect
            applyFeedFilters(view, view.url)
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> webViewRef?.onPause()
                    Lifecycle.Event.ON_RESUME -> webViewRef?.let { wv ->
                        wv.onResume()
                        if (latestJavaScriptEnabled && UrlPolicy.sameOrigin(latestInstance, wv.url)) {
                            wv.evaluateJavascript(latestJsInjection(wv.url), null)
                        }
                    }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                webViewRef?.let { wv ->
                    // Save while the browser is still alive. Navigation may request
                    // the saveable state after this composition has been disposed.
                    wv.saveReadingState(webViewStateBundle)
                    pageBridge.close()
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.removeAllViews()
                    wv.destroy()
                }
                webViewRef = null
            }
        }

        LaunchedEffect(url, selectedInstanceUrl, webViewRef, javaScriptEnabled, feedShellHtml) {
            val wv = webViewRef
            if (wv != null && url.isNotEmpty()) {
                if (!UrlPolicy.sameOrigin(latestInstance, url)) {
                    pageBridge.close()
                    wv.stopLoading()
                    latestOnPageError(-1, "Blocked navigation outside the selected instance")
                    return@LaunchedEffect
                }
                // Feed All with a fetched shell is driven by loadDataWithBaseURL
                // (chrome-free). Do not also hit the live Nitter URL — but still seed
                // proxy cookies so subsequent peer OkHttp fetches and media resolve.
                if (latestIsFeedView && !latestFeedShellHtml.isNullOrBlank()) {
                    instanceOptions.ensureProxyDefaults(url)
                    if (blockDirectXState.get()) instanceOptions.ensureMediaProxyEnabled(url)
                    return@LaunchedEffect
                }
                // In-page profile browse keeps Compose [url] at the Feed All root while
                // the WebView already shows the profile. Syncing would load the live
                // multi-user page and flash Nitter's top Preferences/search chrome.
                if (latestIsFeedView && latestIsProfileView) {
                    return@LaunchedEffect
                }
                instanceOptions.ensureProxyDefaults(url)
                if (blockDirectXState.get()) instanceOptions.ensureMediaProxyEnabled(url)
                val currentUrl = wv.url
                // Only load if the current URL is well-defined and different from the target.
                // If it is null or about:blank, it means the WebView is either starting fresh 
                // or currently restoring its state, both of which are handled by the factory block.
                if (currentUrl != null && currentUrl != "about:blank") {
                    val normalizedCurrent = currentUrl.trimEnd('/')
                    val normalizedTarget = url.trimEnd('/')
                    if (normalizedCurrent != normalizedTarget) {
                        // Skip remount only when Compose is syncing back to a URL already
                        // in history (Feed All root after in-page browse). New Search/Feed
                        // destinations must always load—even if the WebView can go back.
                        val history = wv.copyBackForwardList()
                        val targetAlreadyVisited = (0 until history.size).any { index ->
                            history.getItemAtIndex(index)?.url?.trimEnd('/')
                                .equals(normalizedTarget, ignoreCase = true)
                        }
                        if (targetAlreadyVisited &&
                            wv.canGoBack() &&
                            UrlPolicy.sameOrigin(latestInstance, currentUrl) &&
                            UrlPolicy.sameOrigin(latestInstance, url)
                        ) {
                            return@LaunchedEffect
                        }
                        wv.loadPrivacyUrl(url)
                    }
                }
            }
        }

        AndroidView(
            factory = { context ->
                val swipeRefreshLayout = SwipeRefreshLayout(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setOnRefreshListener { onRefresh() }
                    setBackgroundColor(backgroundColor.toArgb())
                    setProgressBackgroundColorSchemeColor(surfaceColor.toArgb())
                    setColorSchemeColors(primaryColor.toArgb())
                }

                val webView = WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setOnScrollChangeListener { view, _, scrollY, _, _ ->
                        if (!latestIsFeedView || !feedShellShowingRef.get()) return@setOnScrollChangeListener
                        val webView = view as WebView
                        val contentPx = webView.contentHeight * webView.resources.displayMetrics.density
                        if (contentPx - (scrollY + webView.height) < webView.height * FEED_OLDER_PREFETCH_SCREENS) {
                            latestOnFeedNearBottom()
                        }
                    }

                    settings.apply {
                        this.javaScriptEnabled = latestJavaScriptEnabled
                        domStorageEnabled = latestJavaScriptEnabled
                        cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        allowFileAccess = false
                        allowContentAccess = false
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        userAgentString = UserPreferencesRepository.withAccessKey(
                            UserPreferencesRepository.browserUserAgent(userAgentString),
                            instanceKey
                        )
                    }

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptThirdPartyCookies(this, false)

                    setOnLongClickListener { v ->
                        val result = (v as WebView).hitTestResult
                        if (result.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                            val urlResult = result.extra
                            if (!urlResult.isNullOrEmpty()) {
                                val finalUrl = try {
                                    val baseUri = URI(v.url ?: url)
                                    baseUri.resolve(urlResult).toString()
                                } catch (_: Exception) {
                                    urlResult
                                }
                                if (!UrlPolicy.sameOrigin(latestInstance, v.url)) return@setOnLongClickListener false
                                longPressedUrl = UrlPolicy.httpsUrl(finalUrl)?.toString() ?: return@setOnLongClickListener false
                                return@setOnLongClickListener true
                            }
                        }
                        false
                    }

                    // Leave HTTP error documents in the WebView so browser-verification
                    // scripts and redirects can finish using the default HTTP handling.
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            pageBridge.close()
                            clickedImageState = null
                            longPressedUrl = null
                            if (UrlPolicy.isWebViewLocalDocumentUrl(url)) {
                                feedShellLoadPendingRef.set(false)
                                url?.let { latestOnPageStarted(it) }
                                return
                            }
                            if (!UrlPolicy.sameOrigin(latestInstance, url)) {
                                view?.stopLoading()
                                latestOnPageError(-1, "Blocked navigation outside the selected instance")
                                return
                            }
                            if (feedShellLoadPendingRef.getAndSet(false)) {
                                url?.let { latestOnPageStarted(it) }
                                return
                            }
                            // A live page replacing the on-screen shell is a link click that
                            // skipped shouldOverrideUrlLoading; keep it on the owned stack.
                            val leftShell = setFeedShellShowing(false)
                            if (leftShell &&
                                latestIsFeedView &&
                                !latestFeedShellHtml.isNullOrBlank() &&
                                url != null &&
                                feedNavStackRef.get().isEmpty()
                            ) {
                                setFeedNavStack(listOf(url))
                            }
                            if (feedNavStackRef.get().isEmpty()) {
                                view?.alpha = 0f
                            }
                            url?.let { latestOnPageStarted(it) }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (view == null || url == null) return
                            if (UrlPolicy.isWebViewLocalDocumentUrl(url) ||
                                UrlPolicy.isWebViewLocalDocumentUrl(view.url)
                            ) {
                                val local = url.takeIf { UrlPolicy.isWebViewLocalDocumentUrl(it) }
                                    ?: view.url
                                    ?: url
                                latestOnPageFinished(local, view)
                                view.alpha = 1f
                                return
                            }
                            val pageUrl = url.takeIf { UrlPolicy.sameOrigin(latestInstance, it) }
                                ?: view.url?.takeIf { UrlPolicy.sameOrigin(latestInstance, it) }
                                ?: return
                            latestSyncFeedNavTip(pageUrl)
                            if (!latestJavaScriptEnabled) {
                                pageBridge.attach(view, pageUrl)
                                latestOnPageFinished(pageUrl, view)
                                val mergeScroll = pendingNoJsScrollRef.getAndSet(null)
                                when {
                                    mergeScroll != null -> view.post {
                                        view.scrollTo(mergeScroll.first, mergeScroll.second)
                                    }
                                    webViewStateBundle.containsKey("readingY") -> {
                                        val x = webViewStateBundle.getInt("readingX")
                                        val y = webViewStateBundle.getInt("readingY")
                                        webViewStateBundle.remove("readingY")
                                        view.post { view.scrollTo(x, y) }
                                    }
                                }
                                view.alpha = 1f
                                return
                            }
                            if (view.url != pageUrl || !UrlPolicy.sameOrigin(latestInstance, pageUrl)) {
                                view.alpha = 1f
                                return
                            }
                            pageBridge.attach(view, pageUrl)
                            view.evaluateJavascript("""
                                (function() {
                                    // A verification redirect may have replaced the finished document.
                                    if (window.location.href !== ${org.json.JSONObject.quote(pageUrl)}) return null;
                                    var errors = Array.from(document.querySelectorAll('.error-panel, .error-message, .error'))
                                        .filter(function(element) { return !element.closest('.timeline-item, .main-tweet'); });
                                    if (!document.querySelector('.timeline-item, .main-tweet') && document.body) {
                                        errors.push(document.body);
                                    }
                                    var unavailable = errors.find(function(element) {
                                        var text = (element.innerText || '').replace(/\s+/g, ' ');
                                        return /instance has been rate limited|instance has no auth tokens,? or is fully rate limited/i.test(text);
                                    });
                                    if (unavailable) return {instanceUnavailable: (unavailable.innerText || '').trim()};
                                    if (document.querySelector('link[href*="/css/clean.css"]')) return "TEAPOT";
                                    if (document.querySelector('.timeline, .profile-card, link[href*="/css/style.css"]')) return "NITTER";
                                    return null;
                                })()
                            """.trimIndent()) { detected ->
                                if (view.url != pageUrl || !UrlPolicy.sameOrigin(latestInstance, pageUrl)) return@evaluateJavascript
                                if (detected.startsWith('{')) {
                                    val message = if (detected.length <= 65_536) runCatching {
                                        org.json.JSONObject(detected).optString("instanceUnavailable").takeIf { it.isNotBlank() }
                                    }.getOrNull() else instanceUnavailableError
                                    if (message == null) return@evaluateJavascript
                                    latestOnPageError(503, message)
                                    return@evaluateJavascript
                                }
                                when (detected) {
                                    "\"TEAPOT\"" -> latestOnFrontendDetected(pageUrl, FrontendType.TEAPOT)
                                    "\"NITTER\"" -> latestOnFrontendDetected(pageUrl, FrontendType.NITTER)
                                }
                                latestOnPageFinished(pageUrl, view)
                                // Read the main document directly; no avatar interface is exposed to frames.
                                view.evaluateJavascript("""
                                    (function() {
                                        if (location.href !== ${org.json.JSONObject.quote(pageUrl)}) return null;
                                        var avatar = document.querySelector('a.profile-card-avatar');
                                        if (avatar) return avatar.href;
                                        var image = document.querySelector('.main-tweet .tweet-avatar img, .tweet-avatar img');
                                        if (image) return image.src;
                                        var meta = document.querySelector('meta[property="og:image"]');
                                        return location.pathname.indexOf('/status/') === -1 && meta ? meta.content : null;
                                    })()
                                """.trimIndent()) avatarResult@{ result ->
                                    if (view.url != pageUrl || !UrlPolicy.sameOrigin(latestInstance, pageUrl) ||
                                        result.length > 65_536 || !result.startsWith('"')) return@avatarResult
                                    val value = runCatching { org.json.JSONTokener(result).nextValue() as? String }.getOrNull()
                                    val avatar = UrlPolicy.mediaUrl(value, blockDirectXState.get()) ?: return@avatarResult
                                    latestOnAvatarFound(pageUrl, avatar)
                                }
                            }
                            view.evaluateJavascript(latestJsInjection(pageUrl)) {
                                if (view.url != pageUrl || !UrlPolicy.sameOrigin(latestInstance, pageUrl)) return@evaluateJavascript
                                view.evaluateJavascript(positionScript, null)
                                applyFeedFilters(view, pageUrl)
                                val append = latestFeedAppendBatches
                                if (latestIsFeedView && append.isNotEmpty() &&
                                    pageUrl.trimEnd('/') == (view.url ?: "").trimEnd('/')
                                ) {
                                    view.evaluateJavascript(FeedBatchAppend.script(append), null)
                                }
                                if (webViewStateBundle.containsKey("readingY")) {
                                    val x = webViewStateBundle.getInt("readingX")
                                    val y = webViewStateBundle.getInt("readingY")
                                    webViewStateBundle.remove("readingY")
                                    view.post { view.scrollTo(x, y) }
                                }
                                view.alpha = 1f
                            }
                        }

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: android.webkit.RenderProcessGoneDetail?
                        ): Boolean {
                            if (view != null) {
                                latestOnPageError(-1, rendererGoneError)
                            }
                            return true
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                view?.alpha = 1f
                                latestOnPageError(
                                    error?.errorCode ?: 0,
                                    error?.description.toString()
                                )
                            }
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: android.webkit.SslErrorHandler?,
                            error: android.net.http.SslError?
                        ) {
                            // User privacy is first: do not proceed on SSL errors.
                            // Cancel the connection and trigger an error state.
                            handler?.cancel()
                            
                            val errorMessage = error?.let {
                                when (it.primaryError) {
                                    android.net.http.SslError.SSL_EXPIRED -> "SSL Certificate Expired"
                                    android.net.http.SslError.SSL_IDMISMATCH -> "SSL Hostname Mismatch"
                                    android.net.http.SslError.SSL_UNTRUSTED -> "SSL Certificate Untrusted"
                                    android.net.http.SslError.SSL_DATE_INVALID -> "SSL Date Invalid"
                                    android.net.http.SslError.SSL_NOTYETVALID -> "SSL Certificate Not Yet Valid"
                                    else -> "SSL Error"
                                }
                            } ?: "Unknown SSL Error"
                            
                            latestOnPageError(-3, errorMessage)
                        }

                        override fun shouldInterceptRequest(

                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val requestUrl = request?.url?.toString()
                            // loadDataWithBaseURL may surface as data:/about: main-frame;
                            // sameOrigin rejects those and a 403 becomes ERR_HTTP_RESPONSE_CODE_FAILURE.
                            if (UrlPolicy.isWebViewLocalDocumentUrl(requestUrl)) {
                                return super.shouldInterceptRequest(view, request)
                            }
                            if (request?.isForMainFrame == true &&
                                !UrlPolicy.sameOrigin(selectedInstanceState.get(), requestUrl)) {
                                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
                                    mapOf("Cache-Control" to "no-store"), java.io.ByteArrayInputStream(ByteArray(0)))
                            }
                            if (blockDirectXState.get()) {
                                val requestHost = request?.url?.host?.lowercase()
                                if (requestHost != null && UrlPolicy.isXHost(requestHost)) {
                                    val emptyStream = java.io.ByteArrayInputStream(ByteArray(0))
                                    val headers = mapOf(
                                        "Cache-Control" to "no-store, no-cache",
                                        "Pragma" to "no-cache"
                                    )
                                    return WebResourceResponse(
                                        "text/plain",
                                        "UTF-8",
                                        403,
                                        "Blocked",
                                        headers,
                                        emptyStream
                                    )
                                }
                            }
                            if (requestUrl != null &&
                                !UrlPolicy.sameOrigin(selectedInstanceState.get(), requestUrl) &&
                                looksLikeScriptRequest(request)
                            ) {
                                return WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    403,
                                    "Blocked",
                                    mapOf("Cache-Control" to "no-store"),
                                    java.io.ByteArrayInputStream(ByteArray(0))
                                )
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val requestUrl = request?.url ?: return true
                            // instanceUrl allows http://…onion (httpsUrl alone blocks Tor media/nav).
                            val target = UrlPolicy.instanceUrl(requestUrl.toString()) ?: return true
                            if (blockDirectXState.get() && UrlPolicy.isXHost(target.host)) return true
                            val dest = target.toString()
                            if (UrlPolicy.sameOrigin(latestInstance, dest)) {
                                // Feed All: own same-origin main-frame clicks so back can
                                // remount the retained shell instead of relying on history.
                                if (latestIsFeedView &&
                                    !latestFeedShellHtml.isNullOrBlank() &&
                                    request.isForMainFrame &&
                                    !request.isRedirect &&
                                    view != null
                                ) {
                                    latestPushFeedBrowse(view, dest)
                                    return true
                                }
                                return false
                            }
                            if (request.isForMainFrame && request.hasGesture()) {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, requestUrl)) }
                            }
                            return true
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                            savedScrollX = this@apply.scrollX
                            savedScrollY = this@apply.scrollY
                            customView = view
                            customViewCallback = callback
                            fullScreenMode.value = true
                        }

                        override fun onHideCustomView() {
                            customView = null
                            customViewCallback?.onCustomViewHidden()
                            customViewCallback = null
                            fullScreenMode.value = false

                            // Restore scroll position after a short delay to allow layout changes to settle
                            this@apply.postDelayed({
                                this@apply.scrollTo(savedScrollX, savedScrollY)
                            }, 100)
                        }
                    }
                    onWebViewCreated(this)
                }

                if (url.isNotEmpty() && UrlPolicy.sameOrigin(latestInstance, url)) {
                    instanceOptions.ensureProxyDefaults(url)
                    if (blockDirectXState.get()) instanceOptions.ensureMediaProxyEnabled(url)
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    try {
                        cookieManager.setCookie(
                            url,
                            "theme=$nitterThemeCookieValue; Path=/; SameSite=Lax"
                        )
                        cookieManager.flush()
                    } catch (_: Exception) {}
                }
                // A nonempty bundle can contain scroll coordinates without valid
                // browser history (for example if the page was still starting).
                // Skip restore after Refresh/Retry so a failed page isn't remounted.
                if (loadGeneration > 0) {
                    webViewStateBundle.clear()
                }
                // The shell effect mounts Feed All from memory; a restored or live page
                // load here would race it and leave the feed blank until Back.
                val feedShellAvailable = isFeedView && !feedShellHtml.isNullOrBlank()
                val restored = if (loadGeneration > 0 || feedShellAvailable) {
                    null
                } else {
                    webView.restoreState(webViewStateBundle)
                }
                val safeHistory = restored != null && (0 until restored.size).all {
                    UrlPolicy.sameOrigin(latestInstance, restored.getItemAtIndex(it).url)
                }
                if (!safeHistory && !feedShellAvailable) {
                    webView.stopLoading()
                    webView.clearHistory()
                    if (restored != null) webViewStateBundle.clear()
                    if (url.isNotEmpty() && UrlPolicy.sameOrigin(latestInstance, url)) {
                        webView.loadPrivacyUrl(url)
                    } else if (url.isNotEmpty()) {
                        latestOnPageError(-1, "Invalid instance URL")
                    }
                }

                webViewRef = webView
                swipeRefreshLayout.addView(webView)
                swipeRefreshLayout
            },
            update = { swipeRefreshLayout ->
                swipeRefreshLayout.isRefreshing = isRefreshing
                swipeRefreshLayout.setBackgroundColor(backgroundColor.toArgb())
                swipeRefreshLayout.setProgressBackgroundColorSchemeColor(surfaceColor.toArgb())
                swipeRefreshLayout.setColorSchemeColors(primaryColor.toArgb())
                var webView: WebView? = null
                for (i in 0 until swipeRefreshLayout.childCount) {
                    val child = swipeRefreshLayout.getChildAt(i)
                    if (child is WebView) {
                        webView = child
                        break
                    }
                }

                if (webView != null) {
                    webView.settings.javaScriptEnabled = javaScriptEnabled
                    webView.settings.domStorageEnabled = javaScriptEnabled
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    try {
                        val targetUrl = if (webView.url.isNullOrEmpty()) url else webView.url
                        if (!targetUrl.isNullOrEmpty() && UrlPolicy.sameOrigin(latestInstance, targetUrl)) {
                            cookieManager.setCookie(
                                targetUrl,
                                "theme=$nitterThemeCookieValue; Path=/; SameSite=Lax"
                            )
                            cookieManager.flush()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        )

        // Full Screen Video Overlay
        if (customView != null) {
            BackHandler {
                customViewCallback?.onCustomViewHidden()
                customView = null
                customViewCallback = null
                fullScreenMode.value = false
            }
            AndroidView(
                factory = { customView!! },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(10f)
            )
        }

        // Image Viewer Overlay
        val currentImageState = clickedImageState
        if (currentImageState != null) {
            DisposableEffect(Unit) {
                fullScreenMode.value = true
                onDispose {
                    if (customView == null) fullScreenMode.value = false
                }
            }
            key(currentImageState) {
                ImageViewer(
                    imageUrls = currentImageState.first,
                    initialIndex = currentImageState.second,
                    onDismiss = { clickedImageState = null }
                )
            }
        }

        // Link Options Dialog
        longPressedUrl?.let { urlToCopy ->
            val linkCopiedMsg = stringResource(R.string.link_copied)
            val shareLinkTitle = stringResource(R.string.share_link)
            val shareContentStr = stringResource(R.string.share_content)
            val contentExtErrStr = stringResource(R.string.content_extraction_error)
            val isTweetUrl = urlToCopy.contains("/status/")
            val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

            ModalBottomSheet(
                onDismissRequest = { longPressedUrl = null },
                sheetState = sheetState
            ) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = urlToCopy,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    androidx.compose.material3.HorizontalDivider()
                    
                    if (isTweetUrl) {
                        ListItem(
                            modifier = Modifier.clickable {
                                val jsCode = shareTweetScript(urlToCopy)
                                webViewRef?.takeIf { UrlPolicy.sameOrigin(latestInstance, it.url) }?.evaluateJavascript(jsCode) { result ->
                                    try {
                                        require(result.length <= 65_536 && result.startsWith('"'))
                                        val extractedText = org.json.JSONObject("{\"t\":$result}").getString("t")
                                        if (extractedText.isNotEmpty()) {
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, extractedText)
                                            }
                                            context.startActivity(Intent.createChooser(intent, shareContentStr))
                                        } else {
                                            Toast.makeText(context, contentExtErrStr, Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (_: Exception) {
                                        Toast.makeText(context, contentExtErrStr, Toast.LENGTH_SHORT).show()
                                    }
                                }
                                longPressedUrl = null
                            }
                        ) {
                            Text(shareContentStr)
                        }
                    }
                    ListItem(
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, urlToCopy)
                            }
                            context.startActivity(Intent.createChooser(intent, shareLinkTitle))
                            longPressedUrl = null
                        }
                    ) {
                        Text(stringResource(R.string.share_link))
                    }
                    ListItem(
                        modifier = Modifier.clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Copied Link", urlToCopy)
                            // The SDK's nullable getter and non-null setter do not form a writable Kotlin property.
                            @Suppress("UsePropertyAccessSyntax")
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, linkCopiedMsg, Toast.LENGTH_SHORT).show()
                            longPressedUrl = null
                        }
                    ) {
                        Text(stringResource(R.string.copy_link))
                    }
                }
            }
        }
    }
}

private fun looksLikeScriptRequest(request: WebResourceRequest?): Boolean {
    if (request == null) return false
    val path = request.url?.lastPathSegment?.lowercase().orEmpty()
    if (path.endsWith(".js") || path.endsWith(".mjs")) return true
    val accept = request.requestHeaders?.entries
        ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
        ?.value
        .orEmpty()
        .lowercase()
    return accept.contains("javascript") || accept.contains("ecmascript")
}

private fun WebView.saveReadingState(destination: Bundle) {
    // Preserve a pending restoration if state is requested before the restored
    // document has finished loading and applied its saved scroll coordinates.
    if (destination.containsKey("readingY")) return
    val snapshot = Bundle()
    if (saveState(snapshot) != null) {
        snapshot.putInt("readingX", scrollX)
        snapshot.putInt("readingY", scrollY)
        destination.clear()
        destination.putAll(snapshot)
    }
}
