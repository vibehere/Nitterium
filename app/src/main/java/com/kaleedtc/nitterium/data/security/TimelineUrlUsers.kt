package com.kaleedtc.nitterium.data.security

/** Parses usernames from a multi-user Nitter/Teapot timeline path. */
object TimelineUrlUsers {
    fun parse(url: String): List<String> {
        val path = url
            .substringAfter("://", missingDelimiterValue = url)
            .substringAfter('/')
            .substringBefore('?')
            .trim('/')
        if (path.isBlank()) return emptyList()
        val parts = path.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size == 2 && parts[0].equals(parts[1], ignoreCase = true)) {
            return listOf(parts[0])
        }
        return parts
    }

    fun countInUrls(urls: List<String>): Int =
        urls.sumOf { parse(it).size }
}
