package com.kaleedtc.nitterium.data.security

import java.net.URI
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** URL checks shared by navigation, native media and incoming intents. No DNS lookups. */
object UrlPolicy {
    const val MAX_URL_LENGTH = 8192
    private val linkHosts = setOf(
        "twitter.com", "www.twitter.com", "mobile.twitter.com", "x.com", "nitter.net",
        "t.co", "xcancel.com", "rss.xcancel.com", "vxtwitter.com", "fxtwitter.com"
    )

    fun httpsUrl(value: String?): HttpUrl? {
        if (value == null || value.length > MAX_URL_LENGTH || value.any { it.isISOControl() }) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
        return value.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
    }

    /**
     * Accepts HTTPS instances, or HTTP `.onion` instances for Tor.
     * Clearnet HTTP is rejected.
     */
    fun instanceUrl(value: String?): HttpUrl? {
        if (value == null || value.length > MAX_URL_LENGTH || value.any { it.isISOControl() }) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = uri.host?.trimEnd('.') ?: return null
        if (uri.rawUserInfo != null) return null
        val onion = isOnionHost(host)
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        when {
            scheme == "https" -> Unit
            scheme == "http" && onion -> Unit
            else -> return null
        }
        return value.toHttpUrlOrNull()?.takeIf {
            it.username.isEmpty() && it.password.isEmpty() &&
                (it.isHttps || (it.scheme == "http" && isOnionHost(it.host)))
        }
    }

    fun isOnionHost(host: String): Boolean =
        host.trimEnd('.').lowercase(Locale.ROOT).endsWith(".onion")

    /** Short host label for pickers; long .onion names are abbreviated. */
    fun displayHost(url: String): String {
        val host = instanceUrl(url)?.host ?: return url
        if (!isOnionHost(host) || host.length <= 36) return host
        return host.take(14) + "…" + host.takeLast(12)
    }

    fun isTorFriendlyInstance(url: String, torFriendly: Collection<String>): Boolean {
        val parsed = instanceUrl(url) ?: return false
        if (isOnionHost(parsed.host)) return true
        val normalized = parsed.toString().trimEnd('/')
        return torFriendly.any {
            instanceUrl(it)?.toString()?.trimEnd('/') == normalized
        }
    }

    enum class InstanceGroup { TorFriendly, Public, Onion, Custom }

    fun instanceGroup(
        url: String,
        builtIn: Collection<String>,
        custom: Collection<String>,
        torFriendly: Collection<String>,
    ): InstanceGroup {
        val host = instanceUrl(url)?.host.orEmpty()
        if (isOnionHost(host)) return InstanceGroup.Onion
        val normalized = instanceUrl(url)?.toString()?.trimEnd('/')
        val isCustom = custom.any { instanceUrl(it)?.toString()?.trimEnd('/') == normalized } &&
            builtIn.none { instanceUrl(it)?.toString()?.trimEnd('/') == normalized }
        if (isCustom) return InstanceGroup.Custom
        if (isTorFriendlyInstance(url, torFriendly)) return InstanceGroup.TorFriendly
        return InstanceGroup.Public
    }

    fun groupedInstanceChoices(
        builtIn: List<String>,
        custom: Collection<String>,
        selected: String,
        torFriendly: Collection<String>,
    ): List<Pair<InstanceGroup, List<String>>> {
        val choices = instanceChoices(builtIn, custom.toSet(), selected)
        return InstanceGroup.entries.mapNotNull { group ->
            val items = choices.filter { instanceGroup(it, builtIn, custom, torFriendly) == group }
            items.takeIf { it.isNotEmpty() }?.let { group to it }
        }
    }

    fun sameOrigin(instance: String, page: String?): Boolean {
        val a = instanceUrl(instance) ?: return false
        val b = instanceUrl(page) ?: return false
        return a.host.equals(b.host, ignoreCase = true) && a.port == b.port &&
            a.scheme.equals(b.scheme, ignoreCase = true)
    }

    fun origin(url: String): String? = instanceUrl(url)?.newBuilder()
        ?.encodedPath("/")?.query(null)?.fragment(null)?.build()?.toString()?.trimEnd('/')

    fun isXHost(host: String): Boolean {
        val normalized = host.trimEnd('.').lowercase(Locale.ROOT)
        return listOf("twitter.com", "x.com", "twimg.com").any {
            normalized == it || normalized.endsWith(".$it")
        }
    }

    fun mediaUrl(value: String?, blockDirectX: Boolean): String? = httpsUrl(value)
        ?.takeUnless { blockDirectX && isXHost(it.host) }?.toString()

    fun instanceChoices(builtIn: List<String>, custom: Set<String>, selected: String): List<String> =
        (builtIn + custom + selected).filter { instanceUrl(it) != null }.distinctBy { it.trimEnd('/') }

    /** Move the current page to another instance without decoding its path or query. */
    fun switchInstance(previous: String, next: String, page: String): String? {
        val oldBase = instanceUrl(previous) ?: return null
        val newBase = instanceUrl(next) ?: return null
        val current = instanceUrl(page)?.takeIf { sameOrigin(previous, page) } ?: return null
        val oldPath = oldBase.encodedPath.trimEnd('/')
        val relativePath = if (oldPath.isNotEmpty() &&
            (current.encodedPath == oldPath || current.encodedPath.startsWith("$oldPath/"))
        ) {
            current.encodedPath.removePrefix(oldPath)
        } else current.encodedPath
        return newBase.newBuilder()
            .encodedPath(newBase.encodedPath.trimEnd('/') + relativePath.ifEmpty { "/" })
            .encodedQuery(current.encodedQuery)
            .encodedFragment(current.encodedFragment)
            .build().toString()
    }

    fun rewriteDeepLink(instance: String, incoming: String): String? {
        if (incoming.length > MAX_URL_LENGTH) return null
        val uri = runCatching { URI(incoming) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.rawUserInfo != null) return null
        val source = incoming.toHttpUrlOrNull()?.takeIf {
            it.host in linkHosts && it.username.isEmpty() && it.password.isEmpty()
        } ?: return null
        val base = instanceUrl(instance) ?: return null
        return base.newBuilder()
            .encodedPath(base.encodedPath.trimEnd('/') + source.encodedPath)
            .encodedQuery(source.encodedQuery)
            .fragment(null)
            .build().toString()
    }

    fun profileUsername(page: String, instance: String? = null): String? {
        val url = instanceUrl(page) ?: return null
        val baseSegments = if (instance == null) emptyList() else {
            if (!sameOrigin(instance, page)) return null
            instanceUrl(instance)?.pathSegments?.dropLastWhile { it.isEmpty() } ?: return null
        }
        if (url.pathSegments.take(baseSegments.size) != baseSegments) return null
        val first = url.pathSegments.getOrNull(baseSegments.size) ?: return null
        return first.takeIf {
            it.lowercase(Locale.ROOT) !in setOf("search", "settings", "about", "pic", "status") &&
                Regex("[A-Za-z0-9_]{1,15}").matches(it)
        }
    }
}
