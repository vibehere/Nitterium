package com.kaleedtc.nitterium.data.security

import android.util.Log
import com.kaleedtc.nitterium.data.frontend.FrontendAdapter
import com.kaleedtc.nitterium.data.frontend.FrontendType
import com.kaleedtc.nitterium.data.repository.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class FeedChunkFetchResult(
    val instanceUrl: String,
    val pageUrl: String,
    val html: String,
    /** Nitter `<link rel=stylesheet>` / font preloads for chrome-free Feed shell. */
    val styleAssets: String = ""
)

data class FetchedTimelineHtml(
    val html: String,
    val styleAssets: String = ""
)

object FeedChunkFetcher {
    private const val TAG = "FeedChunkFetcher"
    private val CLOUDFLARE_ORIGIN_ERRORS = 520..530

    private val clearnetClient: OkHttpClient by lazy {
        baseClientBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /** SOCKS or system Tor VPN + .onion need long budgets; short clearnet caps false-fail. */
    private val torClient: OkHttpClient by lazy {
        baseClientBuilder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .callTimeout(50, TimeUnit.SECONDS)
            .build()
    }

    private fun baseClientBuilder(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(WebViewCookieJar)
            .proxySelector(feedProxySelector())

    private fun clientFor(url: String): OkHttpClient {
        val socks = runCatching { SocksProxyController.current().enabled }.getOrDefault(false)
        val onion = TorOnionPreference.isOnionInstance(url) ||
            url.contains(".onion", ignoreCase = true)
        return if (socks || onion) torClient else clearnetClient
    }

    /**
     * Tries [hosts] in the given order (caller ranks/pins). Caps attempts so a
     * dead pool cannot stall Feed All for minutes.
     */
    suspend fun fetchChunkTryingHosts(
        usernames: List<String>,
        hosts: List<String>,
        shellBase: String?,
        userAgent: String,
        instanceKeys: Map<String, String>,
        frontend: FrontendType,
        deadHosts: MutableSet<String> = mutableSetOf(),
        maxHostAttempts: Int = Int.MAX_VALUE,
        retryPreferredDespiteDead: String? = null,
        onChallenge: (String) -> Unit = {}
    ): FeedChunkFetchResult? {
        if (usernames.isEmpty()) return null
        val adapter = FrontendAdapter.forType(frontend)
        val tried = linkedSetOf<String>()
        val preferredKey = retryPreferredDespiteDead
            ?.trim()?.trimEnd('/')?.lowercase()
            ?.takeIf { it.isNotBlank() }
        val attemptCap = maxHostAttempts.coerceAtLeast(1)
        var attempts = 0
        for (rawHost in hosts) {
            if (attempts >= attemptCap) break
            val host = rawHost.trim().trimEnd('/')
            if (host.isBlank()) continue
            val key = host.lowercase()
            if (key in tried) continue
            val allowDeadRetry = preferredKey != null && key == preferredKey
            if (key in deadHosts && !allowDeadRetry) continue
            if (MultiTimelineSupport.isSetAside(host)) continue
            tried += key
            attempts += 1
            val pageUrl = runCatching {
                adapter.timelineUrls(
                    host,
                    usernames,
                    maxUsersPerBatch = usernames.size.coerceAtLeast(1)
                ).single()
            }.getOrNull() ?: continue
            val fetched = fetchTimelineHtml(
                url = pageUrl,
                fetchBase = host,
                shellBase = shellBase ?: host,
                userAgent = userAgent,
                instanceKey = instanceKeys[UserPreferencesRepository.hostOf(host)].orEmpty(),
                onChallenge = onChallenge
            )
            if (fetched != null && fetched.html.isNotBlank()) {
                deadHosts.remove(key)
                return FeedChunkFetchResult(
                    instanceUrl = host,
                    pageUrl = pageUrl,
                    html = fetched.html,
                    styleAssets = fetched.styleAssets
                )
            }
            deadHosts += key
        }
        return null
    }

    suspend fun fetchTimelineHtml(
        url: String,
        fetchBase: String,
        shellBase: String,
        userAgent: String,
        instanceKey: String = "",
        onChallenge: (String) -> Unit = {}
    ): FetchedTimelineHtml? = withContext(Dispatchers.IO) {
        val host = fetchBase.trim().trimEnd('/')
        val started = System.nanoTime()
        var failReason: String? = null
        var challenged = false
        val fetched = runCatching {
            val ua = UserPreferencesRepository.withAccessKey(userAgent, instanceKey)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", PrivacyHeaders.ACCEPT_LANGUAGE)
                .get()
                .build()
            clientFor(url).newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || !looksLikeTimeline(body)) {
                    challenged = isBotChallenge(response.code, body, response.headers.toMap())
                    val disabled = !challenged && MultiTimelineSupport.looksDisabled(body)
                    if (disabled) MultiTimelineSupport.setAside(host)
                    failReason = when {
                        challenged -> "challenge_http_${response.code}"
                        disabled -> "multi_timeline_disabled"
                        !response.isSuccessful -> "http_${response.code}"
                        else -> "not_timeline_len_${body.length}"
                    }
                    return@runCatching null
                }
                val rewritten = rewriteOrigins(body, host, shellBase.trimEnd('/'))
                val assets = TimelineHtml.extractStylesheetAssets(rewritten)
                // Always compact — never inject a full Nitter document with logo/nav.
                val compact = TimelineHtml.compactPage(rewritten).takeIf { it.isNotBlank() }
                if (compact == null) {
                    failReason = "compact_empty"
                    return@runCatching null
                }
                FetchedTimelineHtml(html = compact, styleAssets = assets)
            }
        }.onFailure { err ->
            failReason = err.javaClass.simpleName + ":" + (err.message ?: "")
        }.getOrNull()
        val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
        if (fetched != null) {
            FeedHostScoreboard.recordSuccess(host, elapsedMs)
        } else {
            FeedHostScoreboard.recordFailure(host, elapsedMs)
            Log.w(TAG, "fetchTimelineHtml null host=$host ms=$elapsedMs reason=$failReason")
            if (challenged) onChallenge(host)
        }
        fetched
    }

    /**
     * Bot defences only a real browser can pass. Cloudflare 52x means the origin is
     * down, so those are failures rather than a check the user could solve.
     */
    fun isBotChallenge(statusCode: Int, body: String, headers: Map<String, String>): Boolean {
        if (statusCode in CLOUDFLARE_ORIGIN_ERRORS) return false
        return InstanceHealthProbe.looksLikeChallenge(body, headers)
    }

    fun looksLikeTimeline(html: String): Boolean {
        if (html.isBlank()) return false
        if (html.contains("timeline-item", ignoreCase = true)) return true
        if (html.contains("tweet-content", ignoreCase = true)) return true
        if (html.contains("/status/", ignoreCase = true) &&
            html.contains("timeline", ignoreCase = true)
        ) {
            return true
        }
        // Empty multi-user pages still ship Nitter chrome (no timeline-item). Accept
        // them as a usable Feed shell so Tor VPN / onion OkHttp can proceed.
        val lower = html.lowercase()
        if (lower.contains("class=\"timeline\"") || lower.contains("class='timeline'")) {
            return true
        }
        return lower.contains("panel-container") &&
            (lower.contains("nitter") || lower.contains("fontello"))
    }

    fun rewriteOrigins(html: String, fromBase: String, toBase: String): String {
        if (fromBase.isBlank() || toBase.isBlank()) {
            return html
        }
        val shell = toBase.trimEnd('/')
        val source = fromBase.trimEnd('/')
        // Always align http/https variants of the shell host so onion media fetched
        // as https://….onion/… still resolves against the http:// shell baseURL.
        val shellVariants = linkedSetOf(
            shell,
            shell.replace("https://", "http://"),
            shell.replace("http://", "https://")
        ).filter { it.isNotBlank() && !it.equals(shell, ignoreCase = true) }
        var out = html
        for (from in shellVariants) {
            out = out.replace(from, shell, ignoreCase = true)
        }
        out = proxyDirectXImages(out, shell)
        if (source.equals(shell, ignoreCase = true)) return out
        // Nitter forks encode /pic/ and /video/ paths differently (and some sign them), so
        // media must stay on the Nitter that produced it; only page links move to the shell.
        out = RELATIVE_MEDIA.replace(out) { match ->
            match.groupValues[1] + source + match.groupValues[2]
        }
        val sourceVariants = linkedSetOf(
            source,
            source.replace("https://", "http://"),
            source.replace("http://", "https://")
        ).filter { it.isNotBlank() && !it.equals(shell, ignoreCase = true) }
        for (from in sourceVariants) {
            val pageLinks = Regex(Regex.escape(from) + "(?!/(?:pic|video)/)", RegexOption.IGNORE_CASE)
            out = pageLinks.replace(out, Regex.escapeReplacement(shell))
        }
        return out
    }

    private val RELATIVE_MEDIA = Regex("""(=["']|url\(["']?)(/(?:pic|video)/)""")

    private val DIRECT_X_IMAGE = Regex("""https://pbs\.twimg\.com/[^"'\s)<>]+""")

    /**
     * Some Nitters serve X image URLs directly when their proxy is off; those are blocked,
     * so route them through the shell's /pic/ in the full-URL form every fork accepts.
     */
    fun proxyDirectXImages(html: String, base: String): String =
        DIRECT_X_IMAGE.replace(html) { match ->
            val url = match.value.replace("&amp;", "&")
            "${base.trimEnd('/')}/pic/" + URLEncoder.encode(url, StandardCharsets.UTF_8.name())
        }

    private fun feedProxySelector(): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val settings = SocksProxyController.current()
            if (!settings.enabled || !settings.isValid() || uri == null) {
                return listOf(Proxy.NO_PROXY)
            }
            val address = InetSocketAddress.createUnresolved(settings.host, settings.port)
            return listOf(Proxy(Proxy.Type.SOCKS, address))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
    }
}
