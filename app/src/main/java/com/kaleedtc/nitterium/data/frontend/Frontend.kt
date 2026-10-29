package com.kaleedtc.nitterium.data.frontend

import java.net.URI
import java.net.URLEncoder

enum class FrontendType { AUTO, NITTER, TEAPOT }

/** Both frontends share profile/search routes and Nitter-style timeline markup. */
sealed class FrontendAdapter(val type: FrontendType) {
    open val playbackCookie = "hlsPlayback"
    open val proxyCookie = "proxyVideos"

    fun profileUrl(instance: String, username: String): String {
        require(Regex("[A-Za-z0-9_]{1,15}").matches(username)) { "Invalid username" }
        return "${instance.trimEnd('/')}/$username"
    }

    fun timelineUrl(instance: String, usernames: List<String>): String =
        timelineUrls(instance, usernames).single()

    /** Packs usernames into multi-user timeline URLs; singleton batches use `/user,user`. */
    fun timelineUrls(
        instance: String,
        usernames: List<String>,
        maxUsersPerBatch: Int = DEFAULT_MAX_USERS_PER_BATCH,
        maxUrlLength: Int = DEFAULT_MAX_TIMELINE_URL_LENGTH
    ): List<String> {
        require(usernames.isNotEmpty())
        require(maxUsersPerBatch >= 1)
        require(maxUrlLength >= 1)
        usernames.forEach { profileUrl(instance, it) }
        val base = "${instance.trimEnd('/')}/"
        val batches = mutableListOf<String>()
        val current = mutableListOf<String>()

        fun flush() {
            if (current.isEmpty()) return
            val path = if (current.size == 1) {
                val user = current.single()
                "$user,$user"
            } else {
                current.joinToString(",")
            }
            batches += base + path
            current.clear()
        }

        for (username in usernames) {
            val candidate = current + username
            val candidatePath = if (candidate.size == 1) {
                val user = candidate.single()
                "$user,$user"
            } else {
                candidate.joinToString(",")
            }
            val candidateUrl = base + candidatePath
            if (current.isNotEmpty() &&
                (candidate.size > maxUsersPerBatch || candidateUrl.length > maxUrlLength)
            ) {
                flush()
            }
            current += username
        }
        flush()
        return batches
    }

    fun searchUrl(instance: String, query: String): String =
        "${instance.trimEnd('/')}/search?f=tweets&q=${URLEncoder.encode(query, "UTF-8")}"

    open fun themeCookie(dark: Boolean, black: Boolean): String = when {
        dark && black -> "Twitter Black"
        dark -> "Twitter Dark"
        else -> "Twitter"
    }

    data object Nitter : FrontendAdapter(FrontendType.NITTER)
    data object Teapot : FrontendAdapter(FrontendType.TEAPOT) {
        override val playbackCookie = "mp4Playback"
        override val proxyCookie = "proxyMedia"
        override fun themeCookie(dark: Boolean, black: Boolean) = "teapot"
    }

    companion object {
        /** Many public instances rate-limit or reject larger multi-user URLs. */
        const val DEFAULT_MAX_USERS_PER_BATCH = 10
        const val MIN_USERS_PER_BATCH = 1
        const val MAX_USERS_PER_BATCH = 50
        const val DEFAULT_MAX_TIMELINE_URL_LENGTH = 2048

        fun clampUsersPerBatch(size: Int): Int =
            size.coerceIn(MIN_USERS_PER_BATCH, MAX_USERS_PER_BATCH)

        fun forType(type: FrontendType): FrontendAdapter = if (type == FrontendType.TEAPOT) Teapot else Nitter
        fun sameOrigin(first: String, second: String): Boolean = runCatching {
            val a = URI(first)
            val b = URI(second)
            fun port(uri: URI) = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
            a.scheme?.lowercase() in listOf("http", "https") && !a.host.isNullOrBlank() &&
                a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && port(a) == port(b)
        }.getOrDefault(false)
    }
}
