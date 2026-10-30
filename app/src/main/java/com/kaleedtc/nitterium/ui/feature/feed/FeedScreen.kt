package com.kaleedtc.nitterium.ui.feature.feed

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaleedtc.nitterium.R
import com.kaleedtc.nitterium.data.security.UrlPolicy
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

    fun navigateUp() {
        val wv = webView
        if (!state.isError && wv?.canGoBack() == true) {
            wv.goBack()
        } else {
            onNavigateBack()
        }
    }

    BackHandler(enabled = !state.isError && webView?.canGoBack() == true) {
        webView?.goBack()
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
                            if (state.feedSpreadAcrossInstances && profileUsername == null) {
                                val shell = state.multiInstancesUsed.firstOrNull()
                                    ?: state.selectedInstanceUrl.takeIf { state.currentUrl.isNotEmpty() }
                                Text(
                                    text = if (shell != null) {
                                        UrlPolicy.displayHost(shell)
                                    } else {
                                        stringResource(R.string.feed_multi_status_probing)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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
                        if (state.feedSpreadAcrossInstances && profileUsername == null) {
                            TextButton(onClick = { onEvent(FeedEvent.ShowMultiInstanceStatus) }) {
                                Icon(
                                    imageVector = Icons.Filled.Dns,
                                    contentDescription = stringResource(R.string.feed_multi_instances_cd),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.size(4.dp))
                                Text(
                                    text = if (state.multiLoadComplete || state.multiInstancesUsed.isNotEmpty()) {
                                        stringResource(
                                            if (state.multiLoadComplete) R.string.feed_multi_count
                                            else R.string.feed_multi_count_loading,
                                            state.multiInstancesUsed.size.coerceAtLeast(1)
                                        )
                                    } else {
                                        stringResource(R.string.feed_multi_count_loading, 0)
                                    }
                                )
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
                        isProfileView = profileUsername != null,
                        isFeedView = true,
                        feedAppendBatchUrls = if (profileUsername == null) {
                            state.appendBatchUrls
                        } else {
                            emptyList()
                        },
                        feedAppendHtmlChunks = if (profileUsername == null) {
                            state.appendHtmlChunks
                        } else {
                            emptyList()
                        },
                        filterSubscriptions = state.filterSubscriptions,
                        mutedWords = state.mutedWords,
                        onRefresh = {
                            onEvent(FeedEvent.Refresh)
                            if (!state.isError) {
                                webView?.reload()
                            }
                        },
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
            }

            LoadingIndicator(
                isLoading = state.isLoading
            )
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
        title = { Text(stringResource(R.string.feed_multi_status_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.multiChunksTotal > 0) {
                    Text(
                        text = stringResource(
                            R.string.feed_multi_status_progress,
                            state.multiChunksLoaded,
                            state.multiChunksTotal
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
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
                        text = stringResource(R.string.feed_multi_status_shell),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = UrlPolicy.displayHost(hosts.first()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (hosts.size > 1) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.feed_multi_status_chunk),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        hosts.drop(1).forEach { host ->
                            Text(
                                text = UrlPolicy.displayHost(host),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
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
