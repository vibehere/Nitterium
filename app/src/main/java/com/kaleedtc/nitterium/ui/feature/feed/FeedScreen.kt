package com.kaleedtc.nitterium.ui.feature.feed

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaleedtc.nitterium.R
import com.kaleedtc.nitterium.data.security.HostLimitation
import com.kaleedtc.nitterium.data.security.UrlPolicy
import com.kaleedtc.nitterium.ui.common.BotChallengeDialog
import com.kaleedtc.nitterium.ui.common.ErrorScreen
import com.kaleedtc.nitterium.ui.common.GroupAssignmentDialog
import com.kaleedtc.nitterium.ui.common.GroupAssignmentMenu
import com.kaleedtc.nitterium.ui.common.LoadingIndicator
import com.kaleedtc.nitterium.ui.common.LocalFullScreenMode
import com.kaleedtc.nitterium.ui.common.NitterWebView
import com.kaleedtc.nitterium.ui.common.NoInternetScreen
import com.kaleedtc.nitterium.ui.common.SubscriptionActionButton

@Composable
fun FeedScreen(
    isDarkTheme: Boolean,
    onNavigateBack: () -> Unit,
    onNavigateToGroups: () -> Unit = onNavigateBack,
    onSwitchInstance: () -> Unit,
    viewModel: FeedViewModel
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel.effect) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is FeedEffect.ShowSnackbar -> snackbarHostState.showSnackbar(effect.message)
                is FeedEffect.ShowMessage -> {
                    snackbarHostState.showSnackbar(context.getString(effect.resourceId))
                }
            }
        }
    }

    FeedContent(
        state = state,
        isDarkTheme = isDarkTheme,
        onEvent = viewModel::onEvent,
        snackbarHostState = snackbarHostState,
        onSwitchInstance = onSwitchInstance,
        onNavigateBack = onNavigateBack,
        onNavigateToGroups = onNavigateToGroups
    )
}

@Composable
fun FeedContent(
    state: FeedState,
    isDarkTheme: Boolean,
    onEvent: (FeedEvent) -> Unit,
    snackbarHostState: SnackbarHostState,
    onSwitchInstance: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToGroups: () -> Unit = onNavigateBack
) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var feedBrowseGoBack by remember { mutableStateOf<(() -> Boolean)?>(null) }
    var feedBrowseCanGoBack by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val isFullScreen = LocalFullScreenMode.current.value
    val profileUsername = state.currentUsername

    state.groupAssignment?.let { subscription ->
        GroupAssignmentDialog(
            subscription = subscription,
            allGroups = state.groups,
            isSubscribed = state.groupAssignmentIsSubscribed,
            isSaving = state.isSavingGroups,
            onDismiss = { onEvent(FeedEvent.DismissGroups) },
            onConfirm = { onEvent(FeedEvent.SaveGroups(it)) }
        )
    }

    if (state.showUnsubscribeDialog && profileUsername != null) {
        AlertDialog(
            onDismissRequest = { onEvent(FeedEvent.DismissUnsubscribeDialog) },
            title = { Text(stringResource(R.string.unsubscribe_title)) },
            text = { Text(stringResource(R.string.unsubscribe_message, profileUsername)) },
            confirmButton = {
                TextButton(onClick = { onEvent(FeedEvent.ConfirmUnsubscribe) }) {
                    Text(stringResource(R.string.unsubscribe_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(FeedEvent.DismissUnsubscribeDialog) }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (state.showMultiInstanceStatus) {
        MultiInstanceStatusDialog(
            state = state,
            onDismiss = { onEvent(FeedEvent.DismissMultiInstanceStatus) }
        )
    }

    state.challengeVerification?.let { verification ->
        key(verification.url) {
            BotChallengeDialog(
                host = verification.host,
                url = verification.url,
                onPassed = { onEvent(FeedEvent.ChallengePassed(verification.host)) },
                onUnsupported = { onEvent(FeedEvent.ChallengeUnsupported(verification.host)) },
                onSkip = { onEvent(FeedEvent.SkipChallenge(verification.host)) },
                onDismiss = { onEvent(FeedEvent.DismissChallenge) }
            )
        }
    }

    fun navigateUp() {
        if (!state.isError && feedBrowseGoBack?.invoke() == true) return
        val wv = webView
        if (!state.isError &&
            state.feedShellHtml.isNullOrBlank() &&
            wv?.canGoBack() == true
        ) {
            wv.stopLoading()
            wv.goBack()
            return
        }
        onNavigateBack()
    }

    BackHandler(
        enabled = !state.isError && (
            feedBrowseCanGoBack ||
                (state.feedShellHtml.isNullOrBlank() && webView?.canGoBack() == true)
            )
    ) {
        navigateUp()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.navigationBars),
        topBar = {
            if (!isFullScreen) {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (profileUsername != null) {
                                    "@$profileUsername"
                                } else {
                                    state.groupName ?: stringResource(R.string.nav_feed)
                                }
                            )
                            if (profileUsername == null && state.accountsTotal > 0) {
                                // NewPipe-like account progress for both Spread and single-host Feed All.
                                val progressText = when {
                                    !state.accountsLoadComplete -> stringResource(
                                        R.string.feed_accounts_checked_progress,
                                        state.accountsChecked,
                                        state.accountsTotal
                                    )
                                    state.accountsFromCache > 0 -> stringResource(
                                        R.string.feed_accounts_checked_cached,
                                        state.accountsChecked,
                                        state.accountsTotal,
                                        state.accountsFromCache
                                    )
                                    else -> stringResource(
                                        R.string.feed_accounts_checked_done,
                                        state.accountsChecked
                                    )
                                }
                                Text(
                                    text = progressText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (state.feedSpreadAcrossInstances) {
                                    val nitterCount = state.multiInstancesUsed.size
                                    val spreadLine = when {
                                        nitterCount <= 0 && !state.multiLoadComplete ->
                                            stringResource(R.string.feed_multi_status_probing)
                                        nitterCount > 0 && state.multiLoadComplete ->
                                            stringResource(
                                                R.string.feed_multi_spread_subtitle,
                                                nitterCount
                                            )
                                        nitterCount > 0 ->
                                            stringResource(
                                                R.string.feed_multi_spread_subtitle_loading,
                                                nitterCount
                                            )
                                        else -> null
                                    }
                                    if (spreadLine != null) {
                                        Text(
                                            text = spreadLine,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    val hostsLine = formatSpreadHostsLine(state.multiInstancesUsed)
                                    if (hostsLine != null) {
                                        Text(
                                            text = hostsLine,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigateUp() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    },
                    actions = {
                        if (profileUsername != null && !state.isError) {
                            GroupAssignmentMenu(profileUsername) {
                                onEvent(FeedEvent.EditGroups)
                            }
                        }
                        if ((state.feedSpreadAcrossInstances || state.skippedHosts.isNotEmpty()) &&
                            profileUsername == null
                        ) {
                            val nitterBadge = when {
                                state.multiInstancesUsed.isNotEmpty() ->
                                    state.multiInstancesUsed.size.toString()
                                !state.multiLoadComplete -> "…"
                                else -> "0"
                            }
                            IconButton(onClick = { onEvent(FeedEvent.ShowMultiInstanceStatus) }) {
                                BadgedBox(
                                    badge = { Badge { Text(nitterBadge) } }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Dns,
                                        contentDescription = stringResource(
                                            R.string.feed_multi_instances_cd
                                        )
                                    )
                                }
                            }
                        }
                        IconButton(onClick = onNavigateToGroups) {
                            Icon(
                                imageVector = Icons.Filled.Folder,
                                contentDescription = stringResource(R.string.groups)
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (profileUsername != null && !state.isError && !isFullScreen) {
                SubscriptionActionButton(
                    isSubscribed = state.isSubscribed,
                    onClick = { onEvent(FeedEvent.ToggleSubscription(profileUsername)) },
                    onManageGroups = { onEvent(FeedEvent.EditGroups) }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (profileUsername == null &&
                state.hasSubscriptions &&
                state.accountsTotal > 0 &&
                !state.accountsLoadComplete
            ) {
                LinearProgressIndicator(
                    progress = {
                        state.accountsChecked.toFloat() /
                            state.accountsTotal.coerceAtLeast(1).toFloat()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
            if (!state.hasSubscriptions) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.RssFeed,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.no_subscriptions_feed),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (!state.isConnected) {
                NoInternetScreen(
                    onRetry = { onEvent(FeedEvent.Refresh) }
                )
            } else if (state.currentUrl.isNotEmpty()) {
                if (state.isError) {
                    ErrorScreen(
                        message = state.errorMessage ?: stringResource(R.string.failed_to_load_content),
                        onRetry = {
                            onEvent(FeedEvent.Refresh)
                        },
                        onSwitchInstance = onSwitchInstance
                    )
                } else {
                    NitterWebView(
                        url = state.currentUrl,
                        selectedInstanceUrl = state.selectedInstanceUrl,
                        frontend = state.frontend,
                        onFrontendDetected = { url, type -> onEvent(FeedEvent.FrontendDetected(url, type)) },
                        onAvatarFound = { page, avatar -> onEvent(FeedEvent.OnAvatarFound(page, avatar)) },
                        isTrueBlack = state.isTrueBlack,
                        isSiteHeaderEnabled = state.isSiteHeaderEnabled,
                        isBlockDirectXEnabled = state.isBlockDirectXEnabled,
                        darkTheme = isDarkTheme,
                        isRefreshing = state.isRefreshing,
                        loadGeneration = state.loadGeneration,
                        isProfileView = profileUsername != null,
                        isFeedView = true,
                        feedAppendBatchUrls = state.appendBatchUrls,
                        feedAppendHtmlChunks = state.appendHtmlChunks,
                        feedShellHtml = state.feedShellHtml,
                        feedShellStyleAssets = state.feedShellStyleAssets,
                        filterSubscriptions = state.filterSubscriptions,
                        mutedWords = state.mutedWords,
                        onFeedBrowseBackReady = { feedBrowseGoBack = it },
                        onFeedBrowseCanGoBackChange = { feedBrowseCanGoBack = it },
                        onFeedNearBottom = {
                            if (state.canLoadOlder) onEvent(FeedEvent.LoadOlder)
                        },
                        onRefresh = { onEvent(FeedEvent.Refresh) },
                        onPageStarted = { onEvent(FeedEvent.OnPageStarted(it)) },
                        onPageFinished = { url, _ ->
                            onEvent(FeedEvent.OnPageFinished(url))
                        },
                        onPageError = { code, description ->
                            if (code != -2 && code != -10) {
                                onEvent(FeedEvent.OnPageError(description.takeIf { code == 503 }))
                            }
                        },
                        onWebViewCreated = { webView = it }
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = when {
                            state.feedSpreadAcrossInstances ->
                                stringResource(R.string.feed_multi_status_probing)
                            state.accountsTotal > 0 -> stringResource(
                                R.string.feed_accounts_checked_progress,
                                state.accountsChecked,
                                state.accountsTotal
                            )
                            else -> stringResource(R.string.feed_multi_status_probing)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(onClick = { onEvent(FeedEvent.Refresh) }) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }

            // Account-check bar already shows progress for Feed All—avoid a second bar.
            val showPageLoadBar = state.isLoading &&
                (
                    profileUsername != null ||
                        !state.hasSubscriptions ||
                        state.accountsTotal <= 0 ||
                        state.accountsLoadComplete
                    )
            LoadingIndicator(
                modifier = Modifier.align(Alignment.TopCenter),
                isLoading = showPageLoadBar
            )
            LoadingIndicator(
                modifier = Modifier.align(Alignment.BottomCenter),
                isLoading = state.isLoadingOlder
            )
            if (state.challengedHosts.isNotEmpty() &&
                profileUsername == null &&
                !isFullScreen
            ) {
                ChallengeBanner(
                    hostCount = state.challengedHosts.size,
                    onVerify = { onEvent(FeedEvent.VerifyChallenges) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun ChallengeBanner(
    hostCount: Int,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.feed_challenge_banner, hostCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onVerify) {
                Text(stringResource(R.string.feed_challenge_verify))
            }
        }
    }
}

@Composable
private fun MultiInstanceStatusDialog(
    state: FeedState,
    onDismiss: () -> Unit
) {
    val hosts = state.multiInstancesUsed
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (hosts.isNotEmpty()) {
                    stringResource(R.string.feed_multi_status_title_count, hosts.size)
                } else {
                    stringResource(R.string.feed_multi_status_title)
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.multiChunksTotal > 0) {
                    if (state.multiPoolSize > 0) {
                        Text(
                            text = stringResource(
                                R.string.feed_multi_status_pool,
                                state.multiPoolSize,
                                hosts.size
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.feed_multi_status_progress,
                            state.multiChunksLoaded,
                            state.multiChunksTotal
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (state.accountsTotal > 0) {
                        Text(
                            text = stringResource(
                                R.string.feed_accounts_checked_progress,
                                state.accountsChecked,
                                state.accountsTotal
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (state.multiChunksFailed > 0) {
                        Text(
                            text = stringResource(
                                R.string.feed_multi_status_failed,
                                state.multiChunksFailed
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        text = stringResource(
                            if (state.multiLoadComplete) R.string.feed_multi_status_done
                            else R.string.feed_multi_status_loading
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(stringResource(R.string.feed_multi_status_probing))
                }
                if (hosts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.feed_multi_status_hosts),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    hosts.forEachIndexed { index, host ->
                        val label = UrlPolicy.displayHost(host)
                        Text(
                            text = if (index == 0) {
                                stringResource(R.string.feed_multi_status_host_page, label)
                            } else {
                                label
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                if (state.skippedHosts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.feed_multi_status_skipped),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    state.skippedHosts.forEach { skipped ->
                        Text(
                            text = stringResource(
                                R.string.feed_multi_status_skipped_host,
                                UrlPolicy.displayHost(skipped.host),
                                stringResource(skippedReason(skipped.limitation))
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.feed_multi_status_close))
            }
        }
    )
}

private fun skippedReason(limitation: HostLimitation): Int = when (limitation) {
    HostLimitation.MULTI_TIMELINE_DISABLED -> R.string.feed_skipped_reason_search_disabled
    HostLimitation.NO_RSS -> R.string.feed_skipped_reason_no_rss
    HostLimitation.NEEDS_JAVASCRIPT -> R.string.feed_skipped_reason_needs_js
}

private fun formatSpreadHostsLine(hosts: List<String>, maxVisible: Int = 3): String? {
    if (hosts.isEmpty()) return null
    val names = hosts.map { UrlPolicy.displayHost(it) }
    if (names.size <= maxVisible) return names.joinToString(" · ")
    val shown = names.take(maxVisible - 1)
    val remaining = names.size - shown.size
    return shown.joinToString(" · ") + " · +$remaining"
}
