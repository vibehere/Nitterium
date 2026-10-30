package com.kaleedtc.nitterium.data.security

import com.kaleedtc.nitterium.data.model.Subscription

/**
 * Builds the username order for multi-user timeline requests.
 * Muted accounts are omitted so they do not consume accounts-per-request slots.
 */
object FeedBatchOrder {
    fun activeUsernames(subscriptions: List<Subscription>): List<String> =
        subscriptions.filterNot { it.mutedInFeed }.map { it.username }

    /**
     * For Feed All with a small accounts-per-request value, spread the first
     * request across the list so quiet accounts at the top do not dominate.
     * Group feeds keep subscription order so members stay in early batches.
     */
    fun orderForFeed(
        usernames: List<String>,
        chunkSize: Int,
        diversify: Boolean
    ): List<String> {
        if (!diversify || usernames.size <= chunkSize) return usernames
        return diversifyUsernames(usernames, chunkSize)
    }

    fun diversifyUsernames(usernames: List<String>, chunkSize: Int): List<String> {
        if (usernames.size <= chunkSize) return usernames
        val size = usernames.size
        val step = (size / chunkSize).coerceAtLeast(1)
        val used = BooleanArray(size)
        val result = ArrayList<String>(size)
        var start = 0
        while (result.size < size) {
            var added = false
            var i = start
            while (i < size) {
                if (!used[i]) {
                    used[i] = true
                    result.add(usernames[i])
                    added = true
                }
                i += step
            }
            if (!added) {
                for (j in 0 until size) {
                    if (!used[j]) {
                        used[j] = true
                        result.add(usernames[j])
                    }
                }
                break
            }
            start++
        }
        return result
    }
}
