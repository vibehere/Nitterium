package com.kaleedtc.nitterium.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Subscription(
    val username: String,
    val instanceUrl: String,
    val avatarUrl: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val groupIds: List<String> = emptyList(),
    val hideRetweets: Boolean? = null,
    val hideReplies: Boolean? = null,
    val mutedInFeed: Boolean = false
)

data class SubscriptionOptions(
    val groupIds: List<String>,
    val hideRetweets: Boolean? = null,
    val hideReplies: Boolean? = null,
    val mutedInFeed: Boolean = false
)
