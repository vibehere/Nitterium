package com.kaleedtc.nitterium.data.security

import android.webkit.CookieManager
import com.kaleedtc.nitterium.data.repository.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class RssTimelineItem(
    val statusId: String,
    val username: String,
    val link: String,
    val title: String,
    val descriptionHtml: String,
    val pubDate: String
)

/**
 * Fetches per-account Nitter/Teapot RSS and turns items into timeline HTML
 * that [FeedBatchAppend.injectOnePageScript] can append into Feed All.
 */
object NitterRss {
    private val clearnetClient: OkHttpClient by lazy {
        baseClient(connectSec = 12, readSec = 18, callSec = 22)
    }
    private val torClient: OkHttpClient by lazy {
        baseClient(connectSec = 25, readSec = 40, callSec = 50)
    }

    private fun baseClient(connectSec: Long, readSec: Long, callSec: Long): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(connectSec, TimeUnit.SECONDS)
            .readTimeout(readSec, TimeUnit.SECONDS)
            .callTimeout(callSec, TimeUnit.SECONDS)
            .proxySelector(rssProxySelector())
            .build()

    private fun clientFor(url: String): OkHttpClient {
        val socks = runCatching { SocksProxyController.current().enabled }.getOrDefault(false)
        val onion = TorOnionPreference.isOnionInstance(url) ||
            url.contains(".onion", ignoreCase = true)
        return if (socks || onion) torClient else clearnetClient
    }

    private val rssDateIn = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val rssDateOut = SimpleDateFormat("MMM d", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    /**
     * Fast mode (NewPipe-style): one account RSS at a time by default so order
     * can rotate and Tor/SOCKS is not flooded. Pass [concurrency] > 1 only for tests.
     */
    suspend fun fetchUsersTimelineHtml(
        instanceUrl: String,
        usernames: List<String>,
        userAgent: String,
        instanceKey: String = "",
        concurrency: Int = 1
    ): String? = withContext(Dispatchers.IO) {
        if (usernames.isEmpty()) return@withContext null
        val base = instanceUrl.trimEnd('/')
        if (HostLimitations.isSetAside(base, HostLimitation.NO_RSS)) return@withContext null
        val ordered = usernames
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        val limit = concurrency.coerceIn(1, 8)
        val replies = coroutineScope {
            ordered.chunked(limit).flatMap { wave ->
                wave.map { username ->
                    async {
                        username to fetchRssXml(
                            url = "$base/$username/rss",
                            userAgent = userAgent,
                            instanceKey = instanceKey
                        )
                    }
                }.awaitAll()
            }
        }
        // One missing account answers 404 too, so only a host where no account got a feed is recorded.
        if (replies.isNotEmpty() && replies.all { (_, reply) -> reply.unsupported }) {
            HostLimitations.setAside(base, HostLimitation.NO_RSS)
        }
        val items = replies.flatMap { (username, reply) ->
            reply.xml?.let { parseItems(it, fallbackUsername = username, instanceBase = base) }.orEmpty()
        }
        if (items.isEmpty()) return@withContext null
        toTimelineHtml(items.sortedByDescending { it.statusId.toLongOrNull() ?: 0L })
    }

    fun parseItems(
        xml: String,
        fallbackUsername: String,
        instanceBase: String
    ): List<RssTimelineItem> {
        if (xml.isBlank() || !xml.contains("<item", ignoreCase = true)) return emptyList()
        val out = ArrayList<RssTimelineItem>()
        var search = 0
        while (true) {
            val start = xml.indexOf("<item", search, ignoreCase = true)
            if (start < 0) break
            val end = xml.indexOf("</item>", start, ignoreCase = true)
            if (end < 0) break
            val block = xml.substring(start, end + "</item>".length)
            search = end + "</item>".length
            val link = textBetween(block, "<link>", "</link>")
                ?: textBetween(block, "<guid>", "</guid>")
                ?: continue
            val statusId = Regex("/status/(\\d+)").find(link)?.groupValues?.getOrNull(1) ?: continue
            val title = decodeXml(textBetween(block, "<title>", "</title>").orEmpty())
            val description = absolutizeMedia(
                cdataOrText(block, "description"),
                instanceBase.trimEnd('/')
            )
            val pubDate = textBetween(block, "<pubDate>", "</pubDate>").orEmpty()
            val creator = textBetween(block, "<dc:creator>", "</dc:creator>")
                ?: textBetween(block, "<creator>", "</creator>")
            val username = creator
                ?.trim()
                ?.removePrefix("@")
                ?.takeIf { it.isNotBlank() }
                ?: fallbackUsername
            val absoluteLink = when {
                link.startsWith("http", ignoreCase = true) -> link
                link.startsWith("/") -> instanceBase.trimEnd('/') + link
                else -> "$instanceBase/$username/status/$statusId"
            }
            out += RssTimelineItem(
                statusId = statusId,
                username = username,
                link = absoluteLink,
                title = title,
                descriptionHtml = description,
                pubDate = pubDate
            )
        }
        return out
    }

    fun toTimelineHtml(items: List<RssTimelineItem>): String {
        if (items.isEmpty()) return ""
        return buildString {
            append("<div class=\"timeline\">")
            for (item in items) {
                val safeUser = escapeHtml(item.username)
                val safeTitle = escapeHtml(item.title)
                val body = item.descriptionHtml.ifBlank { safeTitle }
                val whenText = escapeHtml(formatPubDate(item.pubDate))
                append("<div class=\"timeline-item\">")
                append("<div class=\"tweet-body\">")
                append("<div class=\"tweet-header\">")
                append("<a class=\"fullname\" href=\"/").append(safeUser).append("\">")
                append(safeUser).append("</a> ")
                append("<a class=\"username\" href=\"/").append(safeUser).append("\">@")
                append(safeUser).append("</a> ")
                append("<span class=\"tweet-date\"><a href=\"")
                append(escapeHtml(item.link))
                append("\">").append(whenText).append("</a></span>")
                append("</div>")
                append("<div class=\"tweet-content media-body\" dir=\"auto\">")
                append(body)
                append("</div>")
                append("<a class=\"tweet-link\" href=\"/")
                append(safeUser).append("/status/").append(item.statusId)
                append("\"></a>")
                append("</div></div>")
            }
            append("</div>")
        }
    }

    /** Nitter-like short date (device TZ) instead of raw RFC822 GMT strings. */
    fun formatPubDate(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        val parsed = runCatching { rssDateIn.parse(trimmed) }.getOrNull()
            ?: runCatching {
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(trimmed)
            }.getOrNull()
        return parsed?.let { rssDateOut.format(it) } ?: trimmed
    }

    /**
     * Point relative `/pic/`, `/img/`, and root-relative media at the instance so
     * RSS chunks injected into Feed All resolve like regular HTML timelines.
     */
    fun absolutizeMedia(html: String, instanceBase: String): String {
        if (html.isBlank() || instanceBase.isBlank()) return html
        val base = instanceBase.trimEnd('/')
        var out = html
        // src="/…" and href="/…" (not // protocol-relative)
        out = Regex("""(?i)(\b(?:src|href|data-src)=["'])/(?!/)""")
            .replace(out) { match -> match.groupValues[1] + "$base/" }
        // Protocol-relative //host/… left alone (clearnet CDN via Tor VPN).
        return out
    }

    private class RssReply(val xml: String?, val unsupported: Boolean)

    private fun fetchRssXml(url: String, userAgent: String, instanceKey: String): RssReply =
        runCatching {
            val ua = UserPreferencesRepository.withAccessKey(userAgent, instanceKey)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*;q=0.8")
                .header("Accept-Language", PrivacyHeaders.ACCEPT_LANGUAGE)
                .apply {
                    CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                        header("Cookie", it)
                    }
                }
                .get()
                .build()
            clientFor(url).newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val unsupported = HostLimitations.looksRssUnsupported(response.code, body)
                val hasItems = body.contains("<item", ignoreCase = true) ||
                    body.contains("<entry", ignoreCase = true)
                RssReply(
                    xml = body.takeIf { response.isSuccessful && hasItems },
                    unsupported = unsupported
                )
            }
        }.getOrElse { RssReply(xml = null, unsupported = false) }

    private fun cdataOrText(block: String, tag: String): String {
        val cdata = Regex(
            "<$tag[^>]*>\\s*<!\\[CDATA\\[(.*?)]]>\\s*</$tag>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(block)?.groupValues?.getOrNull(1)
        if (cdata != null) return cdata
        return decodeXml(textBetween(block, "<$tag>", "</$tag>").orEmpty())
    }

    private fun textBetween(block: String, open: String, close: String): String? {
        val start = block.indexOf(open, ignoreCase = true)
        if (start < 0) return null
        val from = start + open.length
        val end = block.indexOf(close, from, ignoreCase = true)
        if (end < 0) return null
        return block.substring(from, end).trim()
    }

    private fun decodeXml(value: String): String =
        value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

    private fun escapeHtml(value: String): String =
        buildString(value.length) {
            for (ch in value) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    else -> append(ch)
                }
            }
        }

    private fun rssProxySelector(): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val settings = runCatching { SocksProxyController.current() }.getOrNull()
            if (settings == null || !settings.enabled || !settings.isValid() || uri == null) {
                return listOf(Proxy.NO_PROXY)
            }
            val address = InetSocketAddress.createUnresolved(settings.host, settings.port)
            return listOf(Proxy(Proxy.Type.SOCKS, address))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
    }
}
