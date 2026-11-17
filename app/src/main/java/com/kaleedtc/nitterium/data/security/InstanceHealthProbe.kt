package com.kaleedtc.nitterium.data.security

import com.kaleedtc.nitterium.data.model.InstanceDirectoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Probes Nitter hosts for HTML timeline, RSS, and no-JS suitability (OkHttp HTML
 * without running page scripts). Also sniffs common DDoS/edge protection headers.
 *
 * HTML checks hit a public profile (and search fallback), never the homepage —
 * Nitter's `/` is a search shell without timeline markup and would false-fail.
 */
object InstanceHealthProbe {
    data class Progress(
        val checked: Int,
        val total: Int,
        val currentHost: String?
    )

    data class HtmlVerdict(
        val worksHtml: Boolean,
        val challenge: Boolean,
        val ddosProtection: String?
    )

    /** Public accounts used only as connectivity canaries (same for every probe). */
    internal val PROBE_USERS = listOf("NASA", "BBCBreaking")

    private val clearnetClient: OkHttpClient by lazy {
        baseClientBuilder(connectSec = 8, readSec = 12, callSec = 14).build()
    }
    private val torClient: OkHttpClient by lazy {
        // Tor circuits are slow; short timeouts mark live onions as dead.
        baseClientBuilder(connectSec = 25, readSec = 40, callSec = 50).build()
    }

    private fun baseClientBuilder(
        connectSec: Long,
        readSec: Long,
        callSec: Long
    ): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .connectTimeout(connectSec, TimeUnit.SECONDS)
            .readTimeout(readSec, TimeUnit.SECONDS)
            .callTimeout(callSec, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .proxySelector(probeProxySelector())

    private fun clientFor(url: String): OkHttpClient {
        val socks = runCatching { SocksProxyController.current().enabled }.getOrDefault(false)
        val onion = TorOnionPreference.isOnionInstance(url) ||
            url.contains(".onion", ignoreCase = true)
        return if (socks || onion) torClient else clearnetClient
    }

    private fun socksOn(): Boolean =
        runCatching { SocksProxyController.current().enabled }.getOrDefault(false)

    suspend fun probeAll(
        entries: List<InstanceDirectoryEntry>,
        userAgent: String = "Nitterium/2.0",
        concurrency: Int = 0,
        onProgress: (Progress) -> Unit = {}
    ): List<InstanceDirectoryEntry> = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext emptyList()
        val limit = when {
            concurrency > 0 -> concurrency.coerceIn(1, 8)
            socksOn() -> 2
            else -> 4
        }
        val gate = Semaphore(limit)
        val total = entries.size
        var checked = 0
        coroutineScope {
            entries.map { entry ->
                async {
                    gate.withPermit {
                        val result = probeOne(entry, userAgent)
                        val done = synchronized(this@InstanceHealthProbe) {
                            checked += 1
                            checked
                        }
                        onProgress(Progress(done, total, entry.normalizedUrl()))
                        result
                    }
                }
            }.awaitAll()
        }
    }

    fun probeOne(entry: InstanceDirectoryEntry, userAgent: String = "Nitterium/2.0"): InstanceDirectoryEntry {
        val base = entry.normalizedUrl()
        val started = System.nanoTime()
        val html = fetchFirstHtml(htmlProbeUrls(base), userAgent)
        val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
        val body = html?.body.orEmpty()
        val headers = html?.headers.orEmpty()
        val verdict = classifyHtml(body, headers, entry.ddosProtection)
        if (verdict.worksHtml) {
            FeedHostScoreboard.recordSuccess(base, elapsedMs)
        } else {
            FeedHostScoreboard.recordFailure(base, elapsedMs)
        }
        // OkHttp HTML that yields a timeline is usable with WebView JS off.
        val worksWithoutJs = verdict.worksHtml && !verdict.challenge
        val rss = probeRss(base, userAgent)
        HostLimitations.recordProbe(
            host = base,
            worksHtml = verdict.worksHtml,
            challenge = verdict.challenge,
            rssWorks = rss.works,
            rssUnsupported = rss.unsupported
        )
        return entry.copy(
            worksHtml = verdict.worksHtml,
            worksRss = rss.works,
            worksWithoutJs = worksWithoutJs,
            ddosProtection = verdict.ddosProtection,
            lastProbedEpochMs = System.currentTimeMillis()
        )
    }

    /** Paths that actually carry timeline markup (homepage does not). */
    fun htmlProbeUrls(base: String): List<String> {
        val root = base.trim().trimEnd('/')
        if (root.isEmpty()) return emptyList()
        return PROBE_USERS.map { "$root/$it" } +
            listOf("$root/search?f=tweets&q=nitter")
    }

    fun classifyHtml(
        body: String,
        headers: Map<String, String>,
        previousDdos: String? = null
    ): HtmlVerdict {
        if (body.isBlank()) {
            return HtmlVerdict(
                worksHtml = false,
                challenge = false,
                ddosProtection = previousDdos
            )
        }
        val challenge = looksLikeChallenge(body, headers)
        val looksTimeline = FeedChunkFetcher.looksLikeTimeline(body)
        val worksHtml = looksTimeline && !challenge
        val protection = detectDdos(headers, body) ?: previousDdos
        return HtmlVerdict(
            worksHtml = worksHtml,
            challenge = challenge,
            ddosProtection = protection
        )
    }

    private data class FetchResult(val body: String, val headers: Map<String, String>)

    private fun fetchFirstHtml(urls: List<String>, userAgent: String): FetchResult? {
        var last: FetchResult? = null
        for (url in urls) {
            val result = fetch(url, userAgent) ?: continue
            last = result
            val verdict = classifyHtml(result.body, result.headers)
            if (verdict.worksHtml) return result
            if (verdict.challenge) return result
        }
        return last
    }

    private fun fetch(url: String, userAgent: String): FetchResult? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", PrivacyHeaders.ACCEPT_LANGUAGE)
            .get()
            .build()
        clientFor(url).newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val headerMap = response.headers.names().associateWith { name ->
                response.header(name).orEmpty()
            }
            val body = response.body?.string().orEmpty()
            FetchResult(body, headerMap)
        }
    }.getOrNull()

    internal data class RssVerdict(val works: Boolean, val unsupported: Boolean)

    internal fun probeRss(base: String, userAgent: String): RssVerdict {
        val root = base.trimEnd('/')
        val candidates = PROBE_USERS.map { "$root/$it/rss" } + listOf("$root/rss")
        var unsupported = false
        for (url in candidates) {
            val verdict = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header(
                        "Accept",
                        "application/rss+xml, application/atom+xml, application/xml, text/xml, */*"
                    )
                    .get()
                    .build()
                clientFor(url).newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val code = response.code
                    val works = body.contains("<rss", ignoreCase = true) ||
                        body.contains("<feed", ignoreCase = true) ||
                        body.contains("<item", ignoreCase = true)
                    RssVerdict(
                        works = works && code in 200..299,
                        unsupported = HostLimitations.looksRssUnsupported(code, body)
                    )
                }
            }.getOrNull() ?: continue
            if (verdict.works) return verdict
            if (verdict.unsupported) unsupported = true
        }
        return RssVerdict(works = false, unsupported = unsupported)
    }

    fun looksLikeChallenge(body: String, headers: Map<String, String>): Boolean {
        val lower = body.lowercase()
        // Posts can mention "captcha"/"anubis"; a page with timeline items is real content.
        if (lower.contains("timeline-item")) return false
        if (lower.contains("cf-browser-verification") ||
            lower.contains("just a moment") ||
            lower.contains("attention required") && lower.contains("cloudflare") ||
            lower.contains("anubis") ||
            lower.contains("making sure you're not a bot") ||
            lower.contains("making sure you&#39;re not a bot") ||
            lower.contains("checking your browser") ||
            lower.contains("/.gandalf/") ||
            lower.contains("/cdn-cgi/challenge-platform") ||
            lower.contains("captcha") ||
            lower.contains("dogwaf") ||
            lower.contains("prove you're human") ||
            lower.contains("prove you&#39;re human")
        ) {
            return true
        }
        val server = headers.entries.firstOrNull { it.key.equals("server", ignoreCase = true) }?.value.orEmpty()
        return server.contains("cloudflare", ignoreCase = true) &&
            !lower.contains("timeline-item") &&
            !lower.contains("tweet-content")
    }

    fun detectDdos(headers: Map<String, String>, body: String): String? {
        val lower = body.lowercase()
        val cfRay = headers.entries.any { it.key.equals("cf-ray", ignoreCase = true) && it.value.isNotBlank() }
        val server = headers.entries.firstOrNull { it.key.equals("server", ignoreCase = true) }?.value.orEmpty()
        return when {
            lower.contains("anubis") -> "Anubis"
            cfRay || server.contains("cloudflare", ignoreCase = true) ||
                lower.contains("cloudflare") -> "Cloudflare"
            server.contains("ddos", ignoreCase = true) -> server
            body.isBlank() -> null
            else -> "None"
        }
    }

    private fun probeProxySelector(): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val settings = runCatching { SocksProxyController.current() }.getOrNull()
            if (settings == null || !settings.enabled || !settings.isValid() || uri == null) {
                return listOf(Proxy.NO_PROXY)
            }
            // Unresolved host → SOCKS resolves via Tor (avoids clearnet DNS leaks).
            val address = InetSocketAddress.createUnresolved(settings.host, settings.port)
            return listOf(Proxy(Proxy.Type.SOCKS, address))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
    }
}
