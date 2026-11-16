package com.kaleedtc.nitterium.ui.feature.groups

import com.kaleedtc.nitterium.data.model.Subscription
import org.junit.Assert.assertEquals
import org.junit.Test

class MemberSectionsTest {

    private fun sub(name: String) = Subscription(username = name, instanceUrl = "https://example.org")

    @Test fun sectionsFollowInitialMembershipIgnoringCase() {
        val subs = listOf(sub("Alice"), sub("bob"), sub("carol"))

        val (inGroup, notInGroup) = memberSections(subs, setOf("alice", "carol"))

        assertEquals(listOf("Alice", "carol"), inGroup.map { it.username })
        assertEquals(listOf("bob"), notInGroup.map { it.username })
    }

    @Test fun untickedMemberStaysInGroupSection() {
        val subs = listOf(sub("alice"), sub("bob"))
        val initial = setOf("alice", "bob")

        val (inGroup, _) = memberSections(subs, initial)

        assertEquals(listOf("alice", "bob"), inGroup.map { it.username })
    }
}
