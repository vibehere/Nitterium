package com.kaleedtc.nitterium.ui.feature.feed

import com.kaleedtc.nitterium.data.frontend.FrontendType
import com.kaleedtc.nitterium.data.model.FeedGroup
import com.kaleedtc.nitterium.data.model.Subscription
import com.kaleedtc.nitterium.data.model.SubscriptionOptions

data class FeedState(
    val frontend: FrontendType = FrontendType.NITTER,
    val currentUrl: String = "",
    val displayedPageUrl: String? = null,
    val selectedInstanceUrl: String = "https://xitter.cf",
    val groupId: String? = null,
    val groupName: String? = null,
    val batchIndex: Int = 0,
    val batchCount: Int = 0,
    val appendBatchUrls: List<String> = emptyList(),
    val appendHtmlChunks: List<String> = emptyList(),
    val feedAutoLoadBatches: Boolean = true,
    val feedSpreadAcrossInstances: Boolean = false,
    val filterSubscriptions: List<Subscription> = emptyList(),
    val mutedWords: List<String> = emptyList(),
    val multiInstancesUsed: List<String> = emptyList(),
    val multiChunksLoaded: Int = 0,
    val multiChunksTotal: Int = 0,
    val multiLoadComplete: Boolean = false,
    val showMultiInstanceStatus: Boolean = false,
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val isRefreshing: Boolean = false,
    val isConnected: Boolean = true,
    val hasSubscriptions: Boolean = true,
    val isTrueBlack: Boolean = false,
    val isSiteHeaderEnabled: Boolean = false,
    val isBlockDirectXEnabled: Boolean = false,
    val currentUsername: String? = null,
    val isSubscribed: Boolean = false,
    val avatarUrl: String? = null,
    val showUnsubscribeDialog: Boolean = false,
    val groups: List<FeedGroup> = emptyList(),
    val groupAssignment: Subscription? = null,
    val groupAssignmentIsSubscribed: Boolean = false,
    val isSavingGroups: Boolean = false
)

sealed interface FeedEvent {
    data class FrontendDetected(val url: String, val type: FrontendType) : FeedEvent
    data class OnPageStarted(val url: String) : FeedEvent
    data class OnPageFinished(val url: String) : FeedEvent
    data class OnPageError(val description: String? = null) : FeedEvent
    data class OnAvatarFound(val pageUrl: String, val url: String) : FeedEvent
    data class ConnectivityChanged(val isConnected: Boolean) : FeedEvent
    data class ToggleSubscription(val username: String) : FeedEvent
    data class SaveGroups(val options: SubscriptionOptions) : FeedEvent
    data object Refresh : FeedEvent
    data object ClearError : FeedEvent
    data object NextBatch : FeedEvent
    data object PreviousBatch : FeedEvent
    data object EditGroups : FeedEvent
    data object DismissGroups : FeedEvent
    data object ConfirmUnsubscribe : FeedEvent
    data object DismissUnsubscribeDialog : FeedEvent
    data object ShowMultiInstanceStatus : FeedEvent
    data object DismissMultiInstanceStatus : FeedEvent
}

sealed interface FeedEffect {
    data class ShowSnackbar(val message: String) : FeedEffect
    data class ShowMessage(val resourceId: Int) : FeedEffect
}
