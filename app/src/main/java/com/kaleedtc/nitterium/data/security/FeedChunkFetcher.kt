package com.kaleedtc.nitterium.data.security

import android.webkit.CookieManager
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
import java.util.concurrent.TimeUnit

data class FeedChunkFetchResult(
    val instanceUrl: String,
    val pageUrl: String,
    val html: String
)

object FeedChunkFetcher {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .callTimeout(22, TimeUnit.SECONDS)
            .proxySelector(feedProxySelector())
            .build()
    }

    suspend fun fetchChunkTryingHosts(
        usernames: List<String>,
        hosts: List<String>,
        shellBase: String?,
        userAgent: String,
        instanceKeys: Map<String, String>,
        frontend: FrontendType,
        deadHosts: MutableSet<String> = mutableSetOf()
    ): FeedChunkFetchResult? {
        if (usernames.isEmpty()) return null
        val adapter = FrontendAdapter.forType(frontend)
        val tried = linkedSetOf<String>()
        for (rawHost in hosts) {
            val host = rawHost.trim().trimEnd('/')
            if (host.isBlank()) continue
            val key = host.lowercase()
            if (key in deadHosts || key in tried) continue
            tried += key
            val pageUrl = runCatching {
                adapter.timelineUrls(
                    host,
                    usernames,
                    maxUsersPerBatch = usernames.size.coerceAtLeast(1)
                ).single()
            }.getOrNull() ?: continue
            val html = fetchTimelineHtml(
                url = pageUrl,
                fetchBase = host,
                shellBase = shellBase ?: host,
                userAgent = userAgent,
                instanceKey = instanceKeys[UserPreferencesRepository.hostOf(host)].orEmpty()
            )
            if (!html.isNullOrBlank()) {
                return FeedChunkFetchResult(instanceUrl = host, pageUrl = pageUrl, html = html)
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
        instanceKey: String = ""
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val ua = UserPreferencesRepository.withAccessKey(userAgent, instanceKey)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", PrivacyHeaders.ACCEPT_LANGUAGE)
                .apply {
                    CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                        header("Cookie", it)
                    }
                }
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string().orEmpty()
                if (!looksLikeTimeline(body)) return@runCatching null
                val rewritten = rewriteOrigins(body, fetchBase.trimEnd('/'), shellBase.trimEnd('/'))
                TimelineHtml.compactPage(rewritten).ifBlank {
                    if (looksLikeTimeline(rewritten)) rewritten else null
                }
            }
        }.getOrNull()
    }

    fun looksLikeTimeline(html: String): Boolean {
        if (html.isBlank()) return false
        if (html.contains("timeline-item", ignoreCase = true)) return true
        return html.contains("tweet-content", ignoreCase = true) ||
            html.contains("/status/", ignoreCase = true) && html.contains("timeline", ignoreCase = true)
    }

    fun rewriteOrigins(html: String, fromBase: String, toBase: String): String {
        if (fromBase.isBlank() || toBase.isBlank() || fromBase.equals(toBase, ignoreCase = true)) {
            return html
        }
        val variants = linkedSetOf(
            fromBase,
            fromBase.replace("https://", "http://"),
            fromBase.replace("http://", "https://")
        ).filter { it.isNotBlank() && !it.equals(toBase, ignoreCase = true) }
        var out = html
        for (from in variants) {
            out = out.replace(from, toBase, ignoreCase = true)
        }
        return out
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
