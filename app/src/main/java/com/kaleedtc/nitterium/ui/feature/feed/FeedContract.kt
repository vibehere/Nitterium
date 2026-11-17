package com.kaleedtc.nitterium.ui.feature.feed

import com.kaleedtc.nitterium.data.frontend.FrontendType
import com.kaleedtc.nitterium.data.model.FeedGroup
import com.kaleedtc.nitterium.data.model.Subscription
import com.kaleedtc.nitterium.data.model.SubscriptionOptions
import com.kaleedtc.nitterium.data.security.LimitedHost

data class FeedState(
    val frontend: FrontendType = FrontendType.NITTER,
    val currentUrl: String = "",
    val displayedPageUrl: String? = null,
    val selectedInstanceUrl: String = "https://shitter.thepixora.com",
    val groupId: String? = null,
    val groupName: String? = null,
    val batchIndex: Int = 0,
    val batchCount: Int = 0,
    val appendBatchUrls: List<String> = emptyList(),
    val appendHtmlChunks: List<String> = emptyList(),
    /** Shell timeline HTML for no-JS Feed All merges (null when unused). */
    val feedShellHtml: String? = null,
    /** Instance stylesheet links for chrome-free Feed All (from shell page head). */
    val feedShellStyleAssets: String? = null,
    val disableWebJavascript: Boolean = false,
    /** Accounts already loaded into Feed All (NewPipe-style progress). */
    val accountsChecked: Int = 0,
    val accountsTotal: Int = 0,
    val accountsFromCache: Int = 0,
    val accountsLoadComplete: Boolean = true,
    val feedSpreadAcrossInstances: Boolean = false,
    val filterSubscriptions: List<Subscription> = emptyList(),
    val mutedWords: List<String> = emptyList(),
    val multiInstancesUsed: List<String> = emptyList(),
    /** How many distinct Nitters the current spread plan assigned (pool size). */
    val multiPoolSize: Int = 0,
    val multiChunksLoaded: Int = 0,
    val multiChunksFailed: Int = 0,
    val multiChunksTotal: Int = 0,
    val multiLoadComplete: Boolean = false,
    val showMultiInstanceStatus: Boolean = false,
    /** Pool Nitters left out because they cannot serve the current feed mode. */
    val skippedHosts: List<LimitedHost> = emptyList(),
    /** Nitters whose bot check blocked some accounts until it is passed in a browser. */
    val challengedHosts: List<String> = emptyList(),
    val challengeVerification: ChallengeVerification? = null,
    val canLoadOlder: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val isRefreshing: Boolean = false,
    /** Bumped on Refresh/Retry so WebView skips stale history and force-loads. */
    val loadGeneration: Int = 0,
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

data class ChallengeVerification(
    val host: String,
    val url: String
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
    data object EditGroups : FeedEvent
    data object DismissGroups : FeedEvent
    data object ConfirmUnsubscribe : FeedEvent
    data object DismissUnsubscribeDialog : FeedEvent
    data object ShowMultiInstanceStatus : FeedEvent
    data object DismissMultiInstanceStatus : FeedEvent
    data object VerifyChallenges : FeedEvent
    data class ChallengePassed(val host: String) : FeedEvent
    data class ChallengeUnsupported(val host: String) : FeedEvent
    data class SkipChallenge(val host: String) : FeedEvent
    data object DismissChallenge : FeedEvent
    data object LoadOlder : FeedEvent
}

sealed interface FeedEffect {
    data class ShowSnackbar(val message: String) : FeedEffect
    data class ShowMessage(val resourceId: Int) : FeedEffect
}
