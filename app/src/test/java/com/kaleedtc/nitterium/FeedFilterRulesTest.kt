package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.model.Subscription
import com.kaleedtc.nitterium.data.security.FeedFilterRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedFilterRulesTest {
    @Test fun nullOverrideFollowsGlobal() {
        assertTrue(FeedFilterRules.resolveHide(null, true))
        assertFalse(FeedFilterRules.resolveHide(null, false))
    }

    @Test fun explicitOverrideWins() {
        assertFalse(FeedFilterRules.resolveHide(false, true))
        assertTrue(FeedFilterRules.resolveHide(true, false))
    }

    @Test fun perUserFiltersResolveAgainstGlobal() {
        val subs = listOf(
            Subscription("alice", "https://nitter.example", hideRetweets = null, hideReplies = true, mutedInFeed = true),
            Subscription("bob", "https://nitter.example", hideRetweets = false, hideReplies = null)
        )
        val resolved = FeedFilterRules.perUserFilters(subs, globalHideRetweets = true, globalHideReplies = false)
        assertEquals(2, resolved.size)
        assertTrue(resolved[0].hideRetweets)
        assertTrue(resolved[0].hideReplies)
        assertTrue(resolved[0].mutedInFeed)
        assertFalse(resolved[1].hideRetweets)
        assertFalse(resolved[1].hideReplies)
        assertFalse(resolved[1].mutedInFeed)
    }

    @Test fun mutedWordsNormalizeAndMatchCaseInsensitive() {
        val words = FeedFilterRules.normalizeMutedWords(listOf("  Foo ", "foo", "Bar", ""))
        assertEquals(listOf("Bar", "Foo"), words)
        assertTrue(FeedFilterRules.textMatchesMutedWord("this has FOO inside", words))
        assertFalse(FeedFilterRules.textMatchesMutedWord("clean text", words))
        assertFalse(FeedFilterRules.textMatchesMutedWord("anything", emptyList()))
    }
}
