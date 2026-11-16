package com.kaleedtc.nitterium.data.security

import com.kaleedtc.nitterium.data.model.InstanceDirectoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Discovers public Nitter/Teapot hosts from community status APIs
 * (status.d420.de and d420.net).
 */
object PublicInstanceDirectory {
    const val STATUS_D420 = "https://status.d420.de/api/v1/instances"
    const val D420_NET = "https://d420.net/api/v1/instances"

    private val json = Json { ignoreUnknownKeys = true }

    private val clearnetClient: OkHttpClient by lazy {
        baseClientBuilder().build()
    }

    private val torClient: OkHttpClient by lazy {
        baseClientBuilder().build()
    }

    private fun baseClientBuilder(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .callTimeout(22, TimeUnit.SECONDS)
            .proxySelector(scanProxySelector())

    private fun socksEnabledSafe(): Boolean =
        runCatching { SocksProxyController.current().enabled }.getOrDefault(false)

    private fun client(): OkHttpClient =
        if (socksEnabledSafe()) torClient else clearnetClient

    data class ScanResult(
        val entries: List<InstanceDirectoryEntry>,
        val sourcesOk: Int,
        val sourcesTried: Int
    ) {
        val healthy: List<String> get() = entries.map { it.normalizedUrl() }
    }

    /**
     * @param includeOnion when SOCKS/Tor is on, keep `.onion` hosts from the APIs.
     * Pass explicitly from the UI; default stays false so unit tests never touch Android SOCKS.
     */
    suspend fun scanHealthy(
        endpoints: List<String> = listOf(STATUS_D420, D420_NET),
        includeOnion: Boolean = false
    ): ScanResult = withContext(Dispatchers.IO) {
        val byUrl = linkedMapOf<String, InstanceDirectoryEntry>()
        var ok = 0
        for (endpoint in endpoints) {
            val body = fetch(endpoint) ?: continue
            val hosts = runCatching {
                json.decodeFromString(InstanceList.serializer(), body).hosts
            }.getOrNull() ?: continue
            ok += 1
            for (host in hosts) {
                if (!host.healthy || host.isBadHost || host.unreliable) continue
                val raw = host.url.trim().trimEnd('/')
                if (raw.isBlank()) continue
                val isOnion = raw.contains(".onion", ignoreCase = true)
                if (isOnion) {
                    if (!includeOnion) continue
                    if (UrlPolicy.instanceUrl(raw) == null) continue
                } else if (!raw.startsWith("https://", ignoreCase = true)) {
                    continue
                } else if (UrlPolicy.instanceUrl(raw) == null) {
                    continue
                }
                val key = raw.lowercase()
                val entry = InstanceDirectoryEntry(
                    url = raw,
                    version = host.version.takeIf { it.isNotBlank() },
                    rssReported = host.rss,
                    healthyReported = true,
                    connectivity = host.connectivity.takeIf { it.isNotBlank() },
                    country = host.country.takeIf { it.isNotBlank() },
                    points = host.points.takeIf { it > 0 }
                )
                val previous = byUrl[key]
                byUrl[key] = if (previous == null) entry else mergeEntries(previous, entry)
            }
        }
        ScanResult(
            entries = byUrl.values.sortedBy { it.normalizedUrl().lowercase() },
            sourcesOk = ok,
            sourcesTried = endpoints.size
        )
    }

    private fun mergeEntries(
        a: InstanceDirectoryEntry,
        b: InstanceDirectoryEntry
    ): InstanceDirectoryEntry = a.copy(
        version = b.version ?: a.version,
        rssReported = b.rssReported ?: a.rssReported,
        connectivity = b.connectivity ?: a.connectivity,
        country = b.country ?: a.country,
        points = b.points ?: a.points,
        worksHtml = a.worksHtml ?: b.worksHtml,
        worksRss = a.worksRss ?: b.worksRss,
        worksWithoutJs = a.worksWithoutJs ?: b.worksWithoutJs,
        ddosProtection = a.ddosProtection ?: b.ddosProtection,
        lastProbedEpochMs = a.lastProbedEpochMs ?: b.lastProbedEpochMs
    )

    private fun fetch(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Nitterium/2.0")
            .get()
            .build()
        client().newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            response.body?.string()?.takeIf { it.isNotBlank() && it.trimStart().startsWith('{') }
        }
    }.getOrNull()

    private fun scanProxySelector(): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val settings = runCatching { SocksProxyController.current() }.getOrNull()
            if (settings == null || !settings.enabled || !settings.isValid() || uri == null) {
                return listOf(Proxy.NO_PROXY)
            }
            // Unresolved → Tor/SOCKS resolves DNS (avoids clearnet DNS for onions).
            val address = InetSocketAddress.createUnresolved(settings.host, settings.port)
            return listOf(Proxy(Proxy.Type.SOCKS, address))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
    }

    @Serializable
    private data class InstanceList(
        val hosts: List<Host> = emptyList()
    )

    @Serializable
    private data class Host(
        val url: String = "",
        val healthy: Boolean = false,
        @SerialName("is_bad_host") val isBadHost: Boolean = false,
        val unreliable: Boolean = false,
        val rss: Boolean? = null,
        val version: String = "",
        val connectivity: String = "",
        val country: String = "",
        val points: Int = 0
    )
}
