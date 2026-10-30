package com.kaleedtc.nitterium.data.security

import com.kaleedtc.nitterium.data.model.Subscription
import java.util.Locale

data class PerUserFeedFilter(
    val username: String,
    val hideRetweets: Boolean,
    val hideReplies: Boolean,
    val mutedInFeed: Boolean
)

object FeedFilterRules {
    fun resolveHide(override: Boolean?, global: Boolean): Boolean = override ?: global

    fun perUserFilters(
        subscriptions: List<Subscription>,
        globalHideRetweets: Boolean,
        globalHideReplies: Boolean
    ): List<PerUserFeedFilter> = subscriptions.map { sub ->
        PerUserFeedFilter(
            username = sub.username,
            hideRetweets = resolveHide(sub.hideRetweets, globalHideRetweets),
            hideReplies = resolveHide(sub.hideReplies, globalHideReplies),
            mutedInFeed = sub.mutedInFeed
        )
    }

    fun normalizeMutedWords(words: Collection<String>): List<String> =
        words.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedBy { it.lowercase(Locale.ROOT) }

    fun textMatchesMutedWord(text: String, mutedWords: Collection<String>): Boolean {
        if (text.isBlank() || mutedWords.isEmpty()) return false
        val haystack = text.lowercase(Locale.ROOT)
        return mutedWords.any { word ->
            val needle = word.trim().lowercase(Locale.ROOT)
            needle.isNotEmpty() && haystack.contains(needle)
        }
    }
}
