package com.kaleedtc.nitterium.ui.feature.feed

import android.webkit.WebSettings
import androidx.lifecycle.viewModelScope
import com.kaleedtc.nitterium.R
import com.kaleedtc.nitterium.data.ConnectivityMonitor
import com.kaleedtc.nitterium.data.frontend.FrontendAdapter
import com.kaleedtc.nitterium.data.frontend.FrontendType
import com.kaleedtc.nitterium.data.model.Subscription
import com.kaleedtc.nitterium.data.repository.FeedGroupRepository
import com.kaleedtc.nitterium.data.repository.SubscriptionRepository
import com.kaleedtc.nitterium.data.repository.UserPreferencesRepository
import com.kaleedtc.nitterium.data.security.FeedBatchAppend
import com.kaleedtc.nitterium.data.security.FeedBatchOrder
import com.kaleedtc.nitterium.data.security.FeedChunkAssignment
import com.kaleedtc.nitterium.data.security.FeedChunkFetcher
import com.kaleedtc.nitterium.data.security.FeedPrivacyPartition
import com.kaleedtc.nitterium.data.security.UrlPolicy
import com.kaleedtc.nitterium.ui.common.MviViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class FeedViewModel(
    private val preferencesRepository: UserPreferencesRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val connectivityMonitor: ConnectivityMonitor,
    private val groupRepository: FeedGroupRepository,
    private val knownInstances: List<String> = emptyList(),
    private val defaultUserAgent: String = "Mozilla/5.0"
) : MviViewModel<FeedState, FeedEvent, FeedEffect>(FeedState()) {
    private val selectedGroupId = MutableStateFlow<String?>(null)
    private val batchIndex = MutableStateFlow(0)
    private val batchUrls = MutableStateFlow<List<String>>(emptyList())
    private val usersPerBatch = MutableStateFlow(FrontendAdapter.DEFAULT_MAX_USERS_PER_BATCH)
    private val spreadAcrossInstances = MutableStateFlow(false)
    private val shuffleSeed = MutableStateFlow(System.nanoTime())
    private var multiFetchJob: Job? = null
    private val sessionDeadHosts = mutableSetOf<String>()
    private var multiPlan: List<FeedChunkAssignment> = emptyList()
    private var multiPool: List<String> = emptyList()
    private var multiPrimaryFallback: String = ""
    private var multiFrontend: FrontendType = FrontendType.NITTER
    private var shellFailoverAttempts = 0
    private var lastFeedFingerprint: String? = null

    init {
        viewModelScope.launch {
            preferencesRepository.frontend.collect { frontend -> setState { copy(frontend = frontend) } }
        }
        viewModelScope.launch {
            preferencesRepository.feedUsersPerBatch.collect { usersPerBatch.value = it }
        }
        viewModelScope.launch {
            preferencesRepository.feedAutoLoadBatches.collect { enabled ->
                val append = appendUrlsFor(batchIndex.value, enabled)
                setState { copy(feedAutoLoadBatches = enabled, appendBatchUrls = append) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.feedSpreadAcrossInstances.collect { enabled ->
                spreadAcrossInstances.value = enabled
                setState { copy(feedSpreadAcrossInstances = enabled) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.mutedWords.collect { words ->
                setState { copy(mutedWords = words) }
            }
        }
        viewModelScope.launch {
            connectivityMonitor.isConnected.collect { isConnected ->
                onEvent(FeedEvent.ConnectivityChanged(isConnected))
            }
        }
        viewModelScope.launch {
            preferencesRepository.trueBlack.collect { isTrueBlack ->
                setState { copy(isTrueBlack = isTrueBlack) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.enableSiteHeader.collect { enabled ->
                setState { copy(isSiteHeaderEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.blockDirectX.collect { enabled ->
                setState { copy(isBlockDirectXEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            subscriptionRepository.subscriptions.collect { subscriptions ->
                val username = state.value.currentUsername
                setState {
                    copy(
                        filterSubscriptions = subscriptions,
                        isSubscribed = username != null && subscriptions.any {
                            it.username.equals(username, ignoreCase = true)
                        }
                    )
                }
            }
        }

        viewModelScope.launch {
            val base = combine(
                subscriptionRepository.subscriptions,
                preferencesRepository.instanceUrl,
                selectedGroupId,
                preferencesRepository.frontend,
                usersPerBatch
            ) { subscriptions, instanceUrl, groupId, frontend, perBatch ->
                FeedPlanBase(subscriptions, instanceUrl, groupId, frontend, perBatch)
            }
            combine(
                base,
                preferencesRepository.customInstances,
                spreadAcrossInstances,
                shuffleSeed
            ) { planBase, customInstances, spread, seed ->
                FeedPlanInputs(
                    subscriptions = planBase.subscriptions,
                    instanceUrl = planBase.instanceUrl,
                    groupId = planBase.groupId,
                    frontend = planBase.frontend,
                    perBatch = planBase.perBatch,
                    customInstances = customInstances,
                    spread = spread,
                    seed = seed
                )
            }.collect { inputs ->
                val filtered = if (inputs.groupId == null) {
                    inputs.subscriptions
                } else {
                    inputs.subscriptions.filter { it.groupIds.contains(inputs.groupId) }
                }
                rebuildFeed(filtered, inputs)
            }
        }

        viewModelScope.launch {
            batchIndex.collect {
                val instanceUrl = state.value.selectedInstanceUrl
                if (batchUrls.value.isNotEmpty() && instanceUrl.isNotBlank() && !spreadAcrossInstances.value) {
                    applyBatchUrl(instanceUrl, resetBrowsing = true)
                }
            }
        }
    }

    fun setGroup(id: String?, name: String?) {
        lastFeedFingerprint = null
        setState { copy(groupId = id, groupName = name) }
        batchIndex.value = 0
        shuffleSeed.value = System.nanoTime()
        selectedGroupId.value = id
    }

    private fun rebuildFeed(filteredSubscriptions: List<Subscription>, inputs: FeedPlanInputs) {
        if (filteredSubscriptions.isEmpty()) {
            multiFetchJob?.cancel()
            multiFetchJob = null
            lastFeedFingerprint = null
            batchUrls.value = emptyList()
            batchIndex.value = 0
            setState {
                copy(
                    hasSubscriptions = false,
                    selectedInstanceUrl = inputs.instanceUrl,
                    currentUrl = "",
                    batchIndex = 0,
                    batchCount = 0,
                    appendBatchUrls = emptyList(),
                    appendHtmlChunks = emptyList(),
                    multiInstancesUsed = emptyList(),
                    multiChunksLoaded = 0,
                    multiChunksTotal = 0,
                    multiLoadComplete = false,
                    isLoading = false,
                    isError = false,
                    currentUsername = null,
                    isSubscribed = false,
                    avatarUrl = null
                )
            }
            return
        }

        val active = FeedBatchOrder.activeUsernames(filteredSubscriptions)
        if (active.isEmpty()) {
            multiFetchJob?.cancel()
            multiFetchJob = null
            lastFeedFingerprint = null
            batchUrls.value = emptyList()
            batchIndex.value = 0
            setState {
                copy(
                    hasSubscriptions = false,
                    selectedInstanceUrl = inputs.instanceUrl,
                    currentUrl = "",
                    batchIndex = 0,
                    batchCount = 0,
                    appendBatchUrls = emptyList(),
                    appendHtmlChunks = emptyList(),
                    multiInstancesUsed = emptyList(),
                    multiChunksLoaded = 0,
                    multiChunksTotal = 0,
                    multiLoadComplete = false,
                    isLoading = false,
                    isError = false,
                    currentUsername = null,
                    isSubscribed = false,
                    avatarUrl = null
                )
            }
            return
        }
        val usernames = FeedBatchOrder.orderForFeed(
            usernames = active,
            chunkSize = inputs.perBatch,
            diversify = inputs.groupId == null
        )
        val fingerprint = feedFingerprint(filteredSubscriptions, inputs)
        val snapshot = state.value
        val keepSession = fingerprint == lastFeedFingerprint &&
            snapshot.currentUrl.isNotBlank() &&
            snapshot.hasSubscriptions &&
            !snapshot.isRefreshing
        if (keepSession) {
            // Avatar/metadata-only subscription emits must not remount Feed All.
            return
        }
        lastFeedFingerprint = fingerprint

        multiFetchJob?.cancel()
        multiFetchJob = null
        val pool = instancePool(inputs.instanceUrl, inputs.customInstances)
        val useMulti = inputs.spread && pool.isNotEmpty()

        if (useMulti) {
            val plan = FeedPrivacyPartition.plan(
                usernames = usernames,
                instances = pool,
                chunkSize = inputs.perBatch,
                seed = inputs.seed,
                frontend = inputs.frontend
            )
            sessionDeadHosts.clear()
            shellFailoverAttempts = 0
            multiPlan = plan.assignments
            multiPool = pool
            multiPrimaryFallback = inputs.instanceUrl
            multiFrontend = inputs.frontend
            batchUrls.value = emptyList()
            batchIndex.value = 0
            setState {
                copy(
                    hasSubscriptions = true,
                    selectedInstanceUrl = inputs.instanceUrl,
                    currentUrl = "",
                    displayedPageUrl = null,
                    batchIndex = 0,
                    batchCount = 1,
                    appendBatchUrls = emptyList(),
                    appendHtmlChunks = emptyList(),
                    multiInstancesUsed = emptyList(),
                    multiChunksLoaded = 0,
                    multiChunksTotal = plan.assignments.size,
                    multiLoadComplete = false,
                    isError = false,
                    errorMessage = null,
                    isLoading = isConnected,
                    isRefreshing = false,
                    currentUsername = null,
                    isSubscribed = false,
                    avatarUrl = null,
                    showUnsubscribeDialog = false,
                    groupAssignment = null
                )
            }
            bootstrapMultiFeed(plan.assignments, pool, inputs.instanceUrl, inputs.frontend)
            return
        }

        val urls = FrontendAdapter.forType(inputs.frontend)
            .timelineUrls(
                inputs.instanceUrl,
                usernames,
                maxUsersPerBatch = inputs.perBatch
            )
        val previousUrls = batchUrls.value
        batchUrls.value = urls
        if (previousUrls != urls) {
            batchIndex.value = batchIndex.value.coerceIn(0, urls.lastIndex)
        }
        applyBatchUrl(inputs.instanceUrl, resetBrowsing = false)
    }

    private fun feedFingerprint(
        filteredSubscriptions: List<Subscription>,
        inputs: FeedPlanInputs
    ): String {
        val users = filteredSubscriptions.joinToString("\u0001") { sub ->
            buildString {
                append(sub.username.lowercase())
                append('\u0002')
                append(sub.groupIds.sorted().joinToString(","))
                append('\u0002')
                append(sub.hideRetweets)
                append('\u0002')
                append(sub.hideReplies)
                append('\u0002')
                append(sub.mutedInFeed)
            }
        }
        return listOf(
            users,
            inputs.instanceUrl.trim().trimEnd('/').lowercase(),
            inputs.groupId.orEmpty(),
            inputs.frontend.name,
            inputs.perBatch.toString(),
            inputs.spread.toString(),
            if (inputs.spread) inputs.seed.toString() else "0",
            inputs.customInstances.map { it.lowercase() }.sorted().joinToString(",")
        ).joinToString("\u0000")
    }

    private fun bootstrapMultiFeed(
        assignments: List<FeedChunkAssignment>,
        pool: List<String>,
        primary: String,
        frontend: FrontendType
    ) {
        multiFetchJob = viewModelScope.launch {
            val keys = preferencesRepository.instanceKeys.first()
            val first = assignments.firstOrNull() ?: return@launch
            val shellOrder = buildList {
                add(primary)
                add(first.instanceUrl)
                addAll(pool)
            }.map { it.trimEnd('/') }.distinctBy { it.lowercase() }

            val shell = FeedChunkFetcher.fetchChunkTryingHosts(
                usernames = first.usernames,
                hosts = shellOrder,
                shellBase = null,
                userAgent = defaultUserAgent,
                instanceKeys = keys,
                frontend = frontend,
                deadHosts = sessionDeadHosts
            )
            if (shell == null) {
                openSingleInstanceFallback(assignments.flatMap { it.usernames }.distinct(), primary, frontend)
                return@launch
            }

            batchUrls.value = listOf(shell.pageUrl)
            batchIndex.value = 0
            setState {
                copy(
                    hasSubscriptions = true,
                    selectedInstanceUrl = shell.instanceUrl,
                    currentUrl = shell.pageUrl,
                    displayedPageUrl = null,
                    batchIndex = 0,
                    batchCount = 1,
                    appendBatchUrls = emptyList(),
                    appendHtmlChunks = emptyList(),
                    multiInstancesUsed = listOf(shell.instanceUrl),
                    multiChunksLoaded = 1,
                    multiChunksTotal = assignments.size,
                    multiLoadComplete = assignments.size <= 1,
                    isError = false,
                    errorMessage = null,
                    isLoading = isConnected
                )
            }

            if (assignments.size <= 1) return@launch
            delay(FeedBatchAppend.INITIAL_DELAY_MS.toLong())
            for ((index, chunk) in assignments.drop(1).withIndex()) {
                if (!state.value.feedSpreadAcrossInstances) return@launch
                val hostOrder = buildList {
                    add(chunk.instanceUrl)
                    addAll(pool.filterNot { it.equals(shell.instanceUrl, ignoreCase = true) })
                    addAll(pool)
                }.map { it.trimEnd('/') }.distinctBy { it.lowercase() }

                val fetched = FeedChunkFetcher.fetchChunkTryingHosts(
                    usernames = chunk.usernames,
                    hosts = hostOrder,
                    shellBase = shell.instanceUrl,
                    userAgent = defaultUserAgent,
                    instanceKeys = keys,
                    frontend = frontend,
                    deadHosts = sessionDeadHosts
                )
                if (fetched != null) {
                    setState {
                        val used = multiInstancesUsed.toMutableList()
                        if (used.none { it.equals(fetched.instanceUrl, ignoreCase = true) }) {
                            used += fetched.instanceUrl
                        }
                        copy(
                            appendHtmlChunks = appendHtmlChunks + fetched.html,
                            multiInstancesUsed = used,
                            multiChunksLoaded = multiChunksLoaded + 1
                        )
                    }
                } else {
                    setState { copy(multiChunksLoaded = multiChunksLoaded + 1) }
                }
                val peerDelay = if (index < FeedBatchAppend.PEER_CONCURRENCY * 4) {
                    FeedBatchAppend.INITIAL_DELAY_MS
                } else {
                    FeedBatchAppend.DEFAULT_DELAY_MS
                }
                delay(peerDelay.toLong())
            }
            setState { copy(multiLoadComplete = true) }
        }
    }

    private fun openSingleInstanceFallback(
        usernames: List<String>,
        primary: String,
        frontend: FrontendType
    ) {
        if (usernames.isEmpty()) {
            setState { copy(isLoading = false, isError = true, errorMessage = null) }
            return
        }
        val urls = FrontendAdapter.forType(frontend)
            .timelineUrls(primary, usernames, maxUsersPerBatch = usersPerBatch.value)
        batchUrls.value = urls
        batchIndex.value = 0
        val append = if (state.value.feedAutoLoadBatches) urls.drop(1) else emptyList()
        setState {
            copy(
                feedSpreadAcrossInstances = false,
                selectedInstanceUrl = primary,
                currentUrl = urls.first(),
                displayedPageUrl = null,
                batchIndex = 0,
                batchCount = urls.size,
                appendBatchUrls = append,
                appendHtmlChunks = emptyList(),
                multiInstancesUsed = listOf(primary),
                multiChunksLoaded = 1,
                multiChunksTotal = urls.size,
                multiLoadComplete = append.isEmpty(),
                isError = false,
                errorMessage = null,
                isLoading = isConnected
            )
        }
    }

    private fun failoverMultiShell() {
        val assignments = multiPlan
        if (assignments.isEmpty() || !state.value.feedSpreadAcrossInstances) return
        val failed = state.value.selectedInstanceUrl.trimEnd('/').lowercase()
        if (failed.isNotBlank()) sessionDeadHosts += failed
        shellFailoverAttempts += 1
        if (shellFailoverAttempts > multiPool.size.coerceAtLeast(1) + 1) {
            openSingleInstanceFallback(
                assignments.flatMap { it.usernames }.distinct(),
                multiPrimaryFallback.ifBlank { state.value.selectedInstanceUrl },
                multiFrontend
            )
            return
        }
        multiFetchJob?.cancel()
        setState {
            copy(
                isError = false,
                errorMessage = null,
                isLoading = true,
                currentUrl = "",
                appendHtmlChunks = emptyList(),
                multiInstancesUsed = emptyList(),
                multiChunksLoaded = 0,
                multiLoadComplete = false
            )
        }
        bootstrapMultiFeed(assignments, multiPool, multiPrimaryFallback, multiFrontend)
    }

    private fun instancePool(primary: String, custom: Set<String>): List<String> {
        val clearnet = (knownInstances + custom + primary)
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .filter {
                !it.contains(".onion", ignoreCase = true) ||
                    com.kaleedtc.nitterium.data.security.SocksProxyController.current().enabled
            }
            .distinctBy { it.lowercase() }
        return clearnet.ifEmpty {
            listOf(primary.trim().trimEnd('/')).filter { it.isNotBlank() }
        }
    }

    private fun applyBatchUrl(instanceUrl: String, resetBrowsing: Boolean) {
        val urls = batchUrls.value
        if (urls.isEmpty()) return
        val index = batchIndex.value.coerceIn(0, urls.lastIndex)
        if (index != batchIndex.value) batchIndex.value = index
        val rootUrl = urls[index]
        val snapshot = state.value
        val changedInstance = snapshot.selectedInstanceUrl != instanceUrl
        val append = appendUrlsFor(index, snapshot.feedAutoLoadBatches)
        if (!resetBrowsing && !changedInstance && isBrowsingAwayFromBatchRoot(snapshot, rootUrl)) {
            if (snapshot.batchIndex != index ||
                snapshot.batchCount != urls.size ||
                snapshot.selectedInstanceUrl != instanceUrl ||
                snapshot.appendBatchUrls != append ||
                !snapshot.hasSubscriptions
            ) {
                setState {
                    copy(
                        hasSubscriptions = true,
                        selectedInstanceUrl = instanceUrl,
                        batchIndex = index,
                        batchCount = urls.size,
                        appendBatchUrls = append,
                        appendHtmlChunks = emptyList()
                    )
                }
            }
            return
        }
        val url = if (changedInstance) {
            UrlPolicy.switchInstance(
                snapshot.selectedInstanceUrl,
                instanceUrl,
                snapshot.displayedPageUrl ?: snapshot.currentUrl
            ) ?: rootUrl
        } else rootUrl
        if (snapshot.currentUrl != url ||
            changedInstance ||
            snapshot.batchIndex != index ||
            snapshot.batchCount != urls.size ||
            snapshot.appendBatchUrls != append
        ) {
            multiFetchJob?.cancel()
            setState {
                copy(
                    hasSubscriptions = true,
                    selectedInstanceUrl = instanceUrl,
                    currentUrl = url,
                    displayedPageUrl = null,
                    batchIndex = index,
                    batchCount = urls.size,
                    appendBatchUrls = append,
                    appendHtmlChunks = emptyList(),
                    isError = false,
                    errorMessage = null,
                    isLoading = isConnected,
                    currentUsername = null,
                    isSubscribed = false,
                    avatarUrl = null,
                    showUnsubscribeDialog = false,
                    groupAssignment = null
                )
            }
        }
    }

    private fun appendUrlsFor(index: Int, autoLoad: Boolean): List<String> {
        if (!autoLoad || spreadAcrossInstances.value) return emptyList()
        val urls = batchUrls.value
        if (index !in urls.indices) return emptyList()
        return urls.drop(index + 1)
    }

    private fun isBrowsingAwayFromBatchRoot(snapshot: FeedState, rootUrl: String): Boolean {
        if (snapshot.currentUsername != null) return true
        val page = snapshot.displayedPageUrl?.takeIf { it.isNotBlank() } ?: return false
        if (!UrlPolicy.sameOrigin(snapshot.selectedInstanceUrl, page)) return false
        return page.trimEnd('/') != rootUrl.trimEnd('/')
    }

    override fun onEvent(event: FeedEvent) {
        when (event) {
            is FeedEvent.FrontendDetected -> viewModelScope.launch {
                preferencesRepository.recordDetectedFrontend(event.url, event.type)
            }
            is FeedEvent.OnPageStarted -> {
                setState {
                    copy(
                        isLoading = true,
                        isError = false,
                        errorMessage = null,
                        displayedPageUrl = event.url,
                        avatarUrl = null
                    )
                }
                checkIfProfile(event.url)
            }
            is FeedEvent.OnPageFinished -> {
                setState { copy(isLoading = false, isRefreshing = false, displayedPageUrl = event.url) }
                checkIfProfile(event.url)
            }
            is FeedEvent.OnPageError -> {
                val snapshot = state.value
                if (snapshot.feedSpreadAcrossInstances &&
                    snapshot.currentUsername == null &&
                    multiPlan.isNotEmpty()
                ) {
                    failoverMultiShell()
                } else {
                    setState {
                        copy(
                            isLoading = false,
                            isRefreshing = false,
                            isError = true,
                            errorMessage = event.description
                        )
                    }
                }
            }
            is FeedEvent.OnAvatarFound -> {
                val snapshot = state.value
                if (snapshot.displayedPageUrl != event.pageUrl ||
                    !UrlPolicy.sameOrigin(snapshot.selectedInstanceUrl, event.pageUrl)
                ) {
                    return
                }
                val username = UrlPolicy.profileUsername(event.pageUrl, snapshot.selectedInstanceUrl)
                    ?: return
                val avatar = UrlPolicy.mediaUrl(event.url, snapshot.isBlockDirectXEnabled) ?: return
                if (!username.equals(snapshot.currentUsername, ignoreCase = true)) return
                setState { copy(avatarUrl = avatar) }
                viewModelScope.launch {
                    subscriptionRepository.updateSubscriptionAvatar(username, avatar)
                }
            }
            is FeedEvent.ConnectivityChanged -> {
                setState { copy(isConnected = event.isConnected) }
                if (!event.isConnected) {
                    setState { copy(isLoading = false, isRefreshing = false) }
                }
                if (event.isConnected && state.value.isError) {
                    setState { copy(isError = false) }
                }
            }
            is FeedEvent.Refresh -> {
                lastFeedFingerprint = null
                shuffleSeed.value = System.nanoTime()
                setState {
                    copy(
                        isRefreshing = state.value.isConnected,
                        isError = false,
                        appendHtmlChunks = emptyList(),
                        multiInstancesUsed = emptyList(),
                        multiChunksLoaded = 0,
                        multiLoadComplete = false
                    )
                }
            }
            is FeedEvent.ClearError -> setState { copy(isError = false) }
            FeedEvent.ShowMultiInstanceStatus -> setState { copy(showMultiInstanceStatus = true) }
            FeedEvent.DismissMultiInstanceStatus -> setState { copy(showMultiInstanceStatus = false) }
            is FeedEvent.NextBatch -> {
                if (spreadAcrossInstances.value) return
                val urls = batchUrls.value
                if (urls.size > 1 && batchIndex.value < urls.lastIndex) {
                    batchIndex.value += 1
                }
            }
            is FeedEvent.PreviousBatch -> {
                if (spreadAcrossInstances.value) return
                if (batchIndex.value > 0) {
                    batchIndex.value -= 1
                }
            }
            FeedEvent.EditGroups -> editGroups()
            FeedEvent.DismissGroups -> if (!state.value.isSavingGroups) {
                setState { copy(groupAssignment = null) }
            }
            is FeedEvent.SaveGroups -> saveGroups(event.options)
            is FeedEvent.ToggleSubscription -> toggleSubscription(event.username)
            FeedEvent.ConfirmUnsubscribe -> unsubscribe()
            FeedEvent.DismissUnsubscribeDialog -> setState { copy(showUnsubscribeDialog = false) }
        }
    }

    private fun checkIfProfile(url: String) {
        viewModelScope.launch {
            val instanceUrl = preferencesRepository.instanceUrl.first()
            val shell = state.value.selectedInstanceUrl.ifBlank { instanceUrl }
            if (state.value.displayedPageUrl != url) return@launch
            if (!UrlPolicy.sameOrigin(shell, url) && !UrlPolicy.sameOrigin(instanceUrl, url)) return@launch

            val username = UrlPolicy.profileUsername(url, shell)
                ?: UrlPolicy.profileUsername(url, instanceUrl)
            if (username != null) {
                val isSubscribed = subscriptionRepository.isSubscribed(username)
                setState {
                    if (currentUsername != username) {
                        copy(
                            currentUsername = username,
                            isSubscribed = isSubscribed,
                            avatarUrl = null
                        )
                    } else {
                        copy(isSubscribed = isSubscribed)
                    }
                }
            } else if (state.value.currentUsername != null) {
                setState {
                    copy(
                        currentUsername = null,
                        isSubscribed = false,
                        avatarUrl = null,
                        showUnsubscribeDialog = false
                    )
                }
            }
        }
    }

    private fun editGroups() {
        val username = state.value.currentUsername ?: return
        if (username.isBlank() || state.value.groupAssignment != null) return
        val avatarUrl = state.value.avatarUrl
        viewModelScope.launch {
            val subscriptions = subscriptionRepository.subscriptions.first()
            val groups = groupRepository.groups.first()
            val subscription = subscriptions.firstOrNull {
                it.username.equals(username, ignoreCase = true)
            } ?: Subscription(username, preferencesRepository.instanceUrl.first(), avatarUrl)
            if (state.value.currentUsername == username) {
                setState {
                    copy(
                        groupAssignment = subscription,
                        groups = groups,
                        groupAssignmentIsSubscribed = subscriptions.any {
                            it.username.equals(username, ignoreCase = true)
                        }
                    )
                }
            }
        }
    }

    private fun saveGroups(options: com.kaleedtc.nitterium.data.model.SubscriptionOptions) {
        val subscription = state.value.groupAssignment ?: return
        if (state.value.isSavingGroups) return
        setState { copy(isSavingGroups = true) }
        viewModelScope.launch {
            val groups = groupRepository.groups.first()
            val validIds = options.groupIds.filter { id -> groups.any { it.id == id } }
            subscriptionRepository.saveSubscriptionOptions(
                subscription = subscription,
                groupIds = validIds,
                hideRetweets = options.hideRetweets,
                hideReplies = options.hideReplies,
                mutedInFeed = options.mutedInFeed
            ).fold(
                onSuccess = {
                    setState { copy(groupAssignment = null, isSavingGroups = false) }
                    setEffect { FeedEffect.ShowMessage(R.string.groups_saved) }
                },
                onFailure = {
                    setState { copy(isSavingGroups = false) }
                    setEffect { FeedEffect.ShowMessage(R.string.groups_save_failed) }
                }
            )
        }
    }

    private fun toggleSubscription(username: String) {
        viewModelScope.launch {
            if (state.value.isSubscribed) {
                setState { copy(showUnsubscribeDialog = true) }
            } else {
                subscribe(username)
            }
        }
    }

    private suspend fun subscribe(username: String) {
        val instanceUrl = preferencesRepository.instanceUrl.first()
        val avatarUrl = state.value.avatarUrl
        subscriptionRepository.addSubscription(Subscription(username, instanceUrl, avatarUrl))
        setEffect { FeedEffect.ShowSnackbar("Subscribed to $username") }
        setState { copy(isSubscribed = true) }
    }

    private fun unsubscribe() {
        viewModelScope.launch {
            state.value.currentUsername?.let { username ->
                subscriptionRepository.removeSubscription(username)
                setEffect { FeedEffect.ShowSnackbar("Unsubscribed from $username") }
                setState { copy(isSubscribed = false, showUnsubscribeDialog = false) }
            }
        }
    }

    private data class FeedPlanBase(
        val subscriptions: List<Subscription>,
        val instanceUrl: String,
        val groupId: String?,
        val frontend: FrontendType,
        val perBatch: Int
    )

    private data class FeedPlanInputs(
        val subscriptions: List<Subscription>,
        val instanceUrl: String,
        val groupId: String?,
        val frontend: FrontendType,
        val perBatch: Int,
        val customInstances: Set<String>,
        val spread: Boolean,
        val seed: Long
    )

    companion object {
        fun defaultUserAgent(context: android.content.Context): String {
            val raw = WebSettings.getDefaultUserAgent(context)
            return raw.replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+\\s+"), "")
        }
    }
}
