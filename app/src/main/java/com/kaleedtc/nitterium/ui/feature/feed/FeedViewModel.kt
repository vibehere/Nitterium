package com.kaleedtc.nitterium.ui.feature.feed

import android.webkit.WebSettings
import androidx.lifecycle.viewModelScope
import com.kaleedtc.nitterium.R
import com.kaleedtc.nitterium.data.ConnectivityMonitor
import com.kaleedtc.nitterium.data.frontend.FrontendAdapter
import com.kaleedtc.nitterium.data.frontend.FrontendType
import com.kaleedtc.nitterium.data.model.Subscription
import com.kaleedtc.nitterium.data.repository.CookieInstanceOptionsStore
import com.kaleedtc.nitterium.data.repository.FeedGroupRepository
import com.kaleedtc.nitterium.data.repository.SubscriptionRepository
import com.kaleedtc.nitterium.data.repository.UserPreferencesRepository
import com.kaleedtc.nitterium.data.security.FeedBatchAppend
import com.kaleedtc.nitterium.data.security.FeedBatchOrder
import com.kaleedtc.nitterium.data.security.FeedChallengeQueue
import com.kaleedtc.nitterium.data.security.FeedChunkAssignment
import com.kaleedtc.nitterium.data.security.FeedChunkFetchResult
import com.kaleedtc.nitterium.data.security.FeedChunkFetcher
import com.kaleedtc.nitterium.data.security.FeedHostScoreboard
import com.kaleedtc.nitterium.data.security.FeedPrivacyPartition
import com.kaleedtc.nitterium.data.security.FeedTimelineCache
import com.kaleedtc.nitterium.data.security.HostLimitation
import com.kaleedtc.nitterium.data.security.HostLimitations
import com.kaleedtc.nitterium.data.security.NitterRss
import com.kaleedtc.nitterium.data.security.SocksProxyController
import com.kaleedtc.nitterium.data.security.TimelineHtml
import com.kaleedtc.nitterium.data.security.TimelineUrlUsers
import com.kaleedtc.nitterium.data.security.TorOnionPreference
import com.kaleedtc.nitterium.data.security.UrlPolicy
import com.kaleedtc.nitterium.ui.common.MviViewModel
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class FeedViewModel(
    private val preferencesRepository: UserPreferencesRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val connectivityMonitor: ConnectivityMonitor,
    private val groupRepository: FeedGroupRepository,
    private val feedTimelineCache: FeedTimelineCache? = null,
    private val knownInstances: List<String> = emptyList(),
    private val torFriendlyInstances: List<String> = emptyList(),
    private val defaultUserAgent: String = "Mozilla/5.0"
) : MviViewModel<FeedState, FeedEvent, FeedEffect>(FeedState()) {
    private val selectedGroupId = MutableStateFlow<String?>(null)
    private val batchIndex = MutableStateFlow(0)
    private val batchUrls = MutableStateFlow<List<String>>(emptyList())
    private val usersPerBatch = MutableStateFlow(FrontendAdapter.DEFAULT_MAX_USERS_PER_BATCH)
    private val spreadAcrossInstances = MutableStateFlow(false)
    private val preferRss = MutableStateFlow(false)
    private val preferOnionWhenTor = MutableStateFlow(true)
    private val socksEnabled = MutableStateFlow(false)
    private val disabledInstanceUrls = MutableStateFlow<Set<String>>(emptySet())
    private val discoveredInstances = MutableStateFlow<Set<String>>(emptySet())
    private val activePoolPresetUrls = MutableStateFlow<List<String>?>(null)
    private val shuffleSeed = MutableStateFlow(System.nanoTime())
    private var multiFetchJob: Job? = null
    private val sessionDeadHosts = mutableSetOf<String>()
    private var multiPlan: List<FeedChunkAssignment> = emptyList()
    private var multiPool: List<String> = emptyList()
    private var multiPrimaryFallback: String = ""
    private var multiFrontend: FrontendType = FrontendType.NITTER
    private var shellFailoverAttempts = 0
    private var lastFeedFingerprint: String? = null
    private var bypassTimelineCache = false
    private val instanceOptions = CookieInstanceOptionsStore()
    private val challengeQueue = FeedChallengeQueue()
    private var challengeRetry: ChallengeRetryContext? = null
    private var challengeGeneration = 0

    /** Shared by all parallel chunks of one feed load so a failing Nitter is hit once. */
    private var runDeadHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var runChallengedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var poolCandidates: List<String> = emptyList()
    private var runUsedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Next "Load more" page per chunk, always on the Nitter that issued its cursor. */
    private val olderPages = ConcurrentHashMap<String, OlderPage>()

    init {
        viewModelScope.launch {
            preferencesRepository.frontend.collect { frontend -> setState { copy(frontend = frontend) } }
        }
        viewModelScope.launch {
            preferencesRepository.feedUsersPerBatch.collect { usersPerBatch.value = it }
        }
        viewModelScope.launch {
            preferencesRepository.feedPreferRss.collect { preferRss.value = it }
        }
        viewModelScope.launch {
            preferencesRepository.socksPreferOnionInstances.collect { preferOnionWhenTor.value = it }
        }
        viewModelScope.launch {
            preferencesRepository.socksProxy.collect { socksEnabled.value = it.enabled }
        }
        viewModelScope.launch {
            preferencesRepository.disableWebJavascript.collect { disabled ->
                val wasDisabled = state.value.disableWebJavascript
                setState { copy(disableWebJavascript = disabled) }
                if (disabled && !wasDisabled) {
                    val snapshot = state.value
                    if (snapshot.feedShellHtml.isNullOrBlank() &&
                        snapshot.currentUrl.isNotBlank() &&
                        snapshot.currentUsername == null
                    ) {
                        prefetchShellHtml(snapshot.currentUrl, snapshot.selectedInstanceUrl)
                    }
                }
                if (!disabled && wasDisabled) {
                    setState { copy(feedShellHtml = null, feedShellStyleAssets = null) }
                }
            }
        }
        viewModelScope.launch {
            preferencesRepository.disabledInstanceUrls.collect { urls ->
                disabledInstanceUrls.value = urls
            }
        }
        viewModelScope.launch {
            preferencesRepository.discoveredInstances.collect { urls ->
                discoveredInstances.value = urls
            }
        }
        viewModelScope.launch {
            combine(
                preferencesRepository.instancePoolPresets,
                preferencesRepository.activeInstancePoolPresetId
            ) { presets, activeId ->
                presets.firstOrNull { it.id == activeId }?.urls
            }.collect { urls ->
                activePoolPresetUrls.value = urls
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
            val planned = combine(
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
            }
            combine(
                planned,
                disabledInstanceUrls,
                preferOnionWhenTor,
                socksEnabled,
                preferRss
            ) { inputs, disabled, preferOnion, socksOn, rss ->
                inputs.copy(
                    disabledInstances = disabled,
                    // Tor VPN: SOCKS off but selected .onion still enables onion-only.
                    onionOnly = preferOnion &&
                        (socksOn || TorOnionPreference.isOnionInstance(inputs.instanceUrl)),
                    preferRss = rss
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
        // Re-applying the same group (config changes / recomposition) must not wipe a
        // live Feed All session — that made post→back look like a full reload.
        if (selectedGroupId.value == id &&
            state.value.groupId == id &&
            state.value.groupName == name &&
            lastFeedFingerprint != null
        ) {
            return
        }
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
                    feedShellHtml = null,
                    feedShellStyleAssets = null,
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
                    feedShellHtml = null,
                    feedShellStyleAssets = null,
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
        // NewPipe-like: reshuffle account order every plan; Fast (RSS) and regular
        // both request one subscription at a time unless Spread batches across hosts.
        val usernames = FeedBatchOrder.orderForFeed(
            usernames = active,
            chunkSize = if (inputs.spread) inputs.perBatch else 1,
            diversify = inputs.groupId == null && inputs.spread,
            shuffleSeed = inputs.seed
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
        val instanceUrl = if (inputs.onionOnly) {
            TorOnionPreference.resolveOnionInstance(inputs.instanceUrl, pool)
                ?: run {
                    showOnionUnavailable(inputs.instanceUrl)
                    return
                }
        } else {
            inputs.instanceUrl.trim().trimEnd('/').ifBlank {
                pool.firstOrNull().orEmpty()
            }
        }
        if (instanceUrl.isBlank()) {
            showOnionUnavailable(inputs.instanceUrl)
            return
        }
        // Always chunked fetch (progress X/Y). Spread uses many hosts + batch size;
        // otherwise one host and one account per request (RSS fast or HTML regular).
        // Onion Nitters often return empty multi-user timelines — keep one account
        // per request (`/user,user`) so Tor VPN / SOCKS feeds still show posts.
        val feedHosts = if (inputs.spread && pool.isNotEmpty()) pool else listOf(instanceUrl)
        val onionFeed = inputs.onionOnly ||
            TorOnionPreference.isOnionInstance(instanceUrl) ||
            feedHosts.any { TorOnionPreference.isOnionInstance(it) }
        val chunkSize = when {
            onionFeed -> 1
            inputs.spread -> inputs.perBatch.coerceAtLeast(1)
            else -> 1
        }
        val plan = FeedPrivacyPartition.plan(
            usernames = usernames,
            instances = feedHosts,
            chunkSize = chunkSize,
            seed = inputs.seed,
            frontend = inputs.frontend
        )
        sessionDeadHosts.clear()
        shellFailoverAttempts = 0
        multiPlan = plan.assignments
        multiPool = feedHosts
        multiPrimaryFallback = instanceUrl
        multiFrontend = inputs.frontend
        batchUrls.value = emptyList()
        batchIndex.value = 0
        val plannedAccounts = plan.assignments.sumOf { it.usernames.size }
        setState {
            copy(
                hasSubscriptions = true,
                selectedInstanceUrl = instanceUrl,
                currentUrl = "",
                displayedPageUrl = null,
                batchIndex = 0,
                batchCount = 1,
                appendBatchUrls = emptyList(),
                appendHtmlChunks = emptyList(),
                feedShellHtml = null,
                feedShellStyleAssets = null,
                accountsChecked = 0,
                accountsTotal = plannedAccounts,
                accountsFromCache = 0,
                accountsLoadComplete = false,
                multiInstancesUsed = emptyList(),
                multiPoolSize = feedHosts.size,
                multiChunksLoaded = 0,
                multiChunksFailed = 0,
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
        bootstrapMultiFeed(plan.assignments, feedHosts, instanceUrl, inputs.frontend)
    }

    private fun showOnionUnavailable(selected: String) {
        lastFeedFingerprint = null
        batchUrls.value = emptyList()
        batchIndex.value = 0
        setState {
            copy(
                hasSubscriptions = true,
                selectedInstanceUrl = selected,
                currentUrl = "",
                displayedPageUrl = null,
                appendBatchUrls = emptyList(),
                appendHtmlChunks = emptyList(),
                feedShellHtml = null,
                    feedShellStyleAssets = null,
                multiInstancesUsed = emptyList(),
                multiChunksLoaded = 0,
                multiChunksTotal = 0,
                multiLoadComplete = true,
                accountsChecked = 0,
                accountsTotal = 0,
                accountsLoadComplete = true,
                isLoading = false,
                isRefreshing = false,
                isError = true,
                errorMessage = ONION_UNAVAILABLE_MESSAGE
            )
        }
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
            inputs.customInstances.map { it.lowercase() }.sorted().joinToString(","),
            inputs.disabledInstances.map { it.trimEnd('/').lowercase() }.sorted().joinToString(","),
            inputs.onionOnly.toString(),
            inputs.preferRss.toString()
        ).joinToString("\u0000")
    }

    private fun bootstrapMultiFeed(
        assignments: List<FeedChunkAssignment>,
        pool: List<String>,
        primary: String,
        frontend: FrontendType
    ) {
        multiFetchJob?.cancel()
        resetFeedRun()
        multiFetchJob = viewModelScope.launch {
            val self = coroutineContext.job
            try {
                val keys = preferencesRepository.instanceKeys.first()
                if (assignments.isEmpty()) return@launch
                // Shell probing must not poison peer hosts via sessionDeadHosts.
                val shellProbeDead = mutableSetOf<String>()
                val shellChallenged = linkedSetOf<String>()
                val opened = openMultiShell(
                    assignments = assignments,
                    pool = pool,
                    primary = primary,
                    keys = keys,
                    frontend = frontend,
                    shellProbeDead = shellProbeDead,
                    onChallenge = { if (!recordChallenge(it)) shellChallenged += it }
                )
                refreshSkippedHosts()
                if (opened == null) {
                    val users = assignments.flatMap { it.usernames }.distinct()
                    // Tor VPN (.onion, SOCKS off): OkHttp often cannot resolve onions while
                    // WebView can — fall back to a single-host WebView shell instead of
                    // failing the whole Feed All run.
                    val onionWebViewFallback =
                        TorOnionPreference.isOnionInstance(primary) ||
                            assignments.any {
                                TorOnionPreference.isOnionInstance(it.instanceUrl)
                            }
                    val jsOffNeedsBrowser = state.value.disableWebJavascript &&
                        HostLimitations.isSetAside(primary, HostLimitation.NEEDS_JAVASCRIPT)
                    if ((spreadAcrossInstances.value && !onionWebViewFallback) || jsOffNeedsBrowser) {
                        setState {
                            copy(
                                isLoading = false,
                                isError = true,
                                errorMessage = if (jsOffNeedsBrowser) {
                                    JS_REQUIRED_MESSAGE
                                } else {
                                    SPREAD_UNAVAILABLE_MESSAGE
                                },
                                multiLoadComplete = true,
                                accountsLoadComplete = true
                            )
                        }
                        if (!jsOffNeedsBrowser) {
                            queueShellChallenges(shellChallenged, assignments)
                        }
                        refreshSkippedHosts()
                    } else {
                        openSingleInstanceFallback(
                            usernames = users,
                            primary = primary,
                            frontend = frontend
                        )
                    }
                    return@launch
                }
                val (shellAssignment, shell) = opened
                val shellIndex = assignments.indexOfFirst { it === shellAssignment }
                    .takeIf { it >= 0 }
                    ?: assignments.indexOfFirst {
                        it.usernames == shellAssignment.usernames &&
                            it.instanceUrl.equals(shellAssignment.instanceUrl, ignoreCase = true)
                    }
                val peerChunks = assignments.filterIndexed { index, _ -> index != shellIndex }

                batchUrls.value = listOf(shell.pageUrl)
                batchIndex.value = 0
                runUsedHosts += shell.instanceUrl.trimEnd('/').lowercase()
                registerOlderPage(shellAssignment.usernames, shell.instanceUrl, shell.pageUrl, shell.html)
                challengeRetry = ChallengeRetryContext(
                    shellInstance = shell.instanceUrl,
                    pool = pool,
                    keys = keys,
                    frontend = frontend
                )
                val totalAccounts = assignments.sumOf { it.usernames.size }
                val shellAccounts = shellAssignment.usernames.size
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
                        feedShellHtml = shell.html,
                        feedShellStyleAssets = shell.styleAssets.ifBlank { null },
                        accountsChecked = shellAccounts,
                        accountsTotal = totalAccounts,
                        accountsFromCache = 0,
                        accountsLoadComplete = peerChunks.isEmpty(),
                        multiInstancesUsed = listOf(shell.instanceUrl),
                        multiPoolSize = pool.size,
                        multiChunksLoaded = 1,
                        multiChunksFailed = 0,
                        multiChunksTotal = assignments.size,
                        multiLoadComplete = peerChunks.isEmpty(),
                        isError = false,
                        errorMessage = null,
                        isLoading = isConnected
                    )
                }

                refreshSkippedHosts()
                if (peerChunks.isEmpty()) return@launch
                delay(FeedBatchAppend.INITIAL_DELAY_MS.toLong())
                val concurrency = peerConcurrency()
                val gate = Semaphore(concurrency)
                // Always fetch remaining accounts (NewPipe-style 1-by-1 without Spread,
                // or multi-host when Spread is on). Skipping peers left Feed All stuck
                // on the shell account and froze "Checked X of Y".
                coroutineScope {
                    peerChunks.map { chunk ->
                        async {
                            gate.withPermit {
                                val result = fetchMultiPeerChunk(
                                    chunk = chunk,
                                    pool = pool,
                                    shellInstance = shell.instanceUrl,
                                    keys = keys,
                                    frontend = frontend
                                )
                                applyMultiPeerResult(result)
                            }
                        }
                    }.awaitAll()
                }
                bypassTimelineCache = false
            } finally {
                if (multiFetchJob === self) {
                    setState { copy(multiLoadComplete = true, accountsLoadComplete = true) }
                }
            }
        }
    }

    /** Seed proxyMedia cookies so OkHttp HTML/RSS fetches bake in /pic/ URLs, not X CDN. */
    private suspend fun prepareFeedFetchCookies(hosts: Collection<String>) {
        val blockX = preferencesRepository.blockDirectX.first()
        for (raw in hosts) {
            val host = raw.trim().trimEnd('/')
            if (host.isBlank() || UrlPolicy.instanceUrl(host) == null) continue
            instanceOptions.ensureProxyDefaults(host)
            if (blockX) instanceOptions.ensureMediaProxyEnabled(host)
        }
    }

    /**
     * Opens the WebView shell. Prefer each chunk's assigned host first so spread
     * does not immediately collapse onto one Nitter; shell-only failovers use a
     * local dead set and never poison peer assignments.
     */
    private suspend fun openMultiShell(
        assignments: List<FeedChunkAssignment>,
        pool: List<String>,
        primary: String,
        keys: Map<String, String>,
        frontend: FrontendType,
        shellProbeDead: MutableSet<String>,
        onChallenge: (String) -> Unit
    ): Pair<FeedChunkAssignment, FeedChunkFetchResult>? {
        prepareFeedFetchCookies(
            assignments.map { it.instanceUrl } + pool + listOf(primary)
        )
        suspend fun tryShell(
            candidate: FeedChunkAssignment,
            hosts: List<String>,
            maxAttempts: Int
        ): FeedChunkFetchResult? {
            val preferred = candidate.instanceUrl.trim().trimEnd('/')
            return if (preferRss.value) {
                fetchRssChunkTryingHosts(
                    usernames = candidate.usernames,
                    hosts = hosts,
                    shellBase = preferred.ifBlank { primary },
                    keys = keys,
                    frontend = frontend,
                    maxHostAttempts = maxAttempts,
                    preferredHost = preferred,
                    deadHosts = shellProbeDead,
                    onChallenge = onChallenge
                )
            } else {
                FeedChunkFetcher.fetchChunkTryingHosts(
                    usernames = candidate.usernames,
                    hosts = hosts,
                    shellBase = null,
                    userAgent = defaultUserAgent,
                    instanceKeys = keys,
                    frontend = frontend,
                    deadHosts = shellProbeDead,
                    maxHostAttempts = maxAttempts,
                    retryPreferredDespiteDead = preferred,
                    onChallenge = onChallenge
                )
            }
        }

        val primaryHost = primary.trim().trimEnd('/')
        // Tor/.onion budgets are long — never walk every assignment. Try the
        // selected host first, then each unique pool host once.
        fun candidateForHost(host: String): FeedChunkAssignment? {
            val key = host.lowercase()
            return assignments.firstOrNull {
                it.instanceUrl.trim().trimEnd('/').equals(key, ignoreCase = true)
            } ?: assignments.firstOrNull()
        }

        if (primaryHost.isNotBlank()) {
            val primaryCandidate = candidateForHost(primaryHost)
            if (primaryCandidate != null) {
                val shell = tryShell(primaryCandidate, listOf(primaryHost), maxAttempts = 2)
                if (shell != null) return primaryCandidate to shell
            }
        }

        val uniqueHosts = linkedSetOf<String>().apply {
            for (a in assignments) {
                val h = a.instanceUrl.trim().trimEnd('/')
                if (h.isNotBlank()) add(h)
            }
            for (h in pool) {
                val t = h.trim().trimEnd('/')
                if (t.isNotBlank()) add(t)
            }
        }.filterNot { it.equals(primaryHost, ignoreCase = true) }

        for (host in uniqueHosts) {
            val candidate = candidateForHost(host) ?: continue
            val shell = tryShell(candidate, listOf(host), maxAttempts = 1)
            if (shell != null) return candidate to shell
        }

        // Last resort: ranked pool for the first assignment only.
        val fallbackCandidate = assignments.firstOrNull() ?: return null
        val shellOrder = FeedHostScoreboard.rank(
            (listOf(primaryHost) + pool.map { it.trim().trimEnd('/') })
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
        )
        val shell = tryShell(
            fallbackCandidate,
            shellOrder,
            maxAttempts = FeedBatchAppend.SHELL_HOST_ATTEMPTS
        )
        return if (shell != null) fallbackCandidate to shell else null
    }

    private fun peerConcurrency(): Int {
        // System Tor VPN (SOCKS off) still needs low parallelism for onion circuits.
        val tor = socksEnabled.value ||
            SocksProxyController.current().enabled ||
            multiPool.any { TorOnionPreference.isOnionInstance(it) } ||
            TorOnionPreference.isOnionInstance(state.value.selectedInstanceUrl)
        val base = if (tor) FeedBatchAppend.PEER_CONCURRENCY_TOR else FeedBatchAppend.PEER_CONCURRENCY
        return base.coerceIn(1, 6)
    }

    private data class MultiPeerResult(
        val chunk: FeedChunkAssignment,
        val html: String?,
        val host: String,
        val fromCache: Boolean,
        val challengedHosts: Set<String> = emptySet(),
        val pageUrl: String = chunk.url
    ) {
        val usernames: List<String> get() = chunk.usernames
    }

    private data class OlderPage(val host: String, val url: String)

    private data class ChallengeRetryContext(
        val shellInstance: String,
        val pool: List<String>,
        val keys: Map<String, String>,
        val frontend: FrontendType
    )

    private suspend fun fetchMultiPeerChunk(
        chunk: FeedChunkAssignment,
        pool: List<String>,
        shellInstance: String,
        keys: Map<String, String>,
        frontend: FrontendType
    ): MultiPeerResult {
        val preferred = chunk.instanceUrl.trim().trimEnd('/')
        // Spread: only the assigned Nitter — never scoreboard/failover onto one winner.
        val hostOrder = FeedPrivacyPartition.peerHostTryOrder(
            preferred = preferred,
            pool = pool,
            shellInstance = shellInstance,
            usedHosts = runUsedHosts,
            unavailable = runDeadHosts + runChallengedHosts
        )
        prepareFeedFetchCookies(hostOrder)
        val maxAttempts = (1 + FeedBatchAppend.PEER_FAILOVER_HOSTS).coerceAtLeast(1)
        val peerDead = runDeadHosts
        val runChallenged = runChallengedHosts

        val modeTag = if (preferRss.value) ":rss" else ":html"
        val cacheKey = FeedTimelineCache.key(preferred + modeTag, chunk.usernames)
        val cached = if (!bypassTimelineCache) feedTimelineCache?.get(cacheKey) else null
        if (cached != null) {
            runUsedHosts += preferred.lowercase()
            return MultiPeerResult(
                chunk = chunk,
                html = cached,
                host = preferred,
                fromCache = true
            )
        }
        val challenged = mutableSetOf<String>()
        val preferredKey = preferred.lowercase()
        if (preferredKey in runChallenged) challenged += preferred
        val onChallenge: (String) -> Unit = {
            recordChallenge(it)
            challenged += it
            runChallenged += it.trim().trimEnd('/').lowercase()
        }
        val fetched = if (preferRss.value) {
            fetchRssChunkTryingHosts(
                usernames = chunk.usernames,
                hosts = hostOrder,
                shellBase = shellInstance,
                keys = keys,
                frontend = frontend,
                maxHostAttempts = maxAttempts,
                deadHosts = peerDead,
                onChallenge = onChallenge
            )
        } else {
            FeedChunkFetcher.fetchChunkTryingHosts(
                usernames = chunk.usernames,
                hosts = hostOrder,
                shellBase = shellInstance,
                userAgent = defaultUserAgent,
                instanceKeys = keys,
                frontend = frontend,
                deadHosts = peerDead,
                maxHostAttempts = maxAttempts,
                onChallenge = onChallenge
            )
        }
        if (fetched != null) {
            cacheChunk(fetched, chunk.usernames)
            runUsedHosts += fetched.instanceUrl.trimEnd('/').lowercase()
        }
        return MultiPeerResult(
            chunk = chunk,
            html = fetched?.html,
            host = fetched?.instanceUrl ?: preferred,
            pageUrl = fetched?.pageUrl ?: chunk.url,
            fromCache = false,
            challengedHosts = challenged
        )
    }

    private fun cacheChunk(fetched: FeedChunkFetchResult, usernames: List<String>) {
        val modeTag = if (preferRss.value) ":rss" else ":html"
        feedTimelineCache?.put(
            FeedTimelineCache.key(fetched.instanceUrl + modeTag, usernames),
            fetched.html,
            fetched.instanceUrl,
            usernames
        )
    }

    private suspend fun fetchRssChunkTryingHosts(
        usernames: List<String>,
        hosts: List<String>,
        shellBase: String,
        keys: Map<String, String>,
        frontend: FrontendType,
        maxHostAttempts: Int = Int.MAX_VALUE,
        preferredHost: String? = null,
        deadHosts: MutableSet<String> = sessionDeadHosts,
        onChallenge: (String) -> Unit = {}
    ): FeedChunkFetchResult? {
        if (usernames.isEmpty()) return null
        val shell = shellBase.trimEnd('/')
        val adapter = FrontendAdapter.forType(frontend)
        val tried = linkedSetOf<String>()
        val preferredKey = preferredHost?.trim()?.trimEnd('/')?.lowercase()?.takeIf { it.isNotBlank() }
        val attemptCap = maxHostAttempts.coerceAtLeast(1)
        var attempts = 0
        for (rawHost in hosts) {
            if (attempts >= attemptCap) break
            val host = rawHost.trim().trimEnd('/')
            if (host.isBlank()) continue
            val key = host.lowercase()
            if (key in tried) continue
            val allowDeadRetry = preferredKey != null && key == preferredKey
            if (key in deadHosts && !allowDeadRetry) continue
            tried += key
            attempts += 1
            val instanceKey = keys[UserPreferencesRepository.hostOf(host)].orEmpty()
            val started = System.nanoTime()
            val html = NitterRss.fetchUsersTimelineHtml(
                instanceUrl = host,
                usernames = usernames,
                userAgent = defaultUserAgent,
                instanceKey = instanceKey
            )
            val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
            if (!html.isNullOrBlank()) {
                FeedHostScoreboard.recordSuccess(host, elapsedMs)
                deadHosts.remove(key)
                val rewritten = FeedChunkFetcher.rewriteOrigins(html, host, shell)
                val pageUrl = adapter
                    .timelineUrls(host, usernames, maxUsersPerBatch = usernames.size.coerceAtLeast(1))
                    .firstOrNull()
                    ?: "$host/${usernames.first()}"
                return FeedChunkFetchResult(
                    instanceUrl = host,
                    pageUrl = pageUrl,
                    html = rewritten
                )
            }
            FeedHostScoreboard.recordFailure(host, elapsedMs)
            // Leave hosts alive for HTML fallback below—RSS alone is not a hard fail.
        }
        return FeedChunkFetcher.fetchChunkTryingHosts(
            usernames = usernames,
            hosts = hosts,
            shellBase = shellBase,
            userAgent = defaultUserAgent,
            instanceKeys = keys,
            frontend = frontend,
            deadHosts = deadHosts,
            maxHostAttempts = maxHostAttempts,
            retryPreferredDespiteDead = preferredHost,
            onChallenge = onChallenge
        )
    }

    private fun applyMultiPeerResult(result: MultiPeerResult) {
        result.html?.let { registerOlderPage(result.usernames, result.host, result.pageUrl, it) }
        if (result.html == null && !state.value.disableWebJavascript) {
            FeedChallengeQueue.hostFor(result.chunk, result.challengedHosts)?.let { host ->
                challengeQueue.add(host, result.chunk)
                setState { copy(challengedHosts = challengeQueue.hosts) }
            }
        }
        setState {
            val used = multiInstancesUsed.toMutableList()
            val ok = result.html != null
            if (ok && used.none { it.equals(result.host, ignoreCase = true) }) {
                used += result.host
            }
            val html = result.html
            copy(
                appendHtmlChunks = if (html != null) {
                    appendHtmlChunks + html
                } else {
                    appendHtmlChunks
                },
                multiInstancesUsed = used,
                multiChunksLoaded = multiChunksLoaded + 1,
                multiChunksFailed = multiChunksFailed + if (ok) 0 else 1,
                // Count every finished account (success or fail) like NewPipe "X of Y".
                accountsChecked = (accountsChecked + result.usernames.size)
                    .coerceAtMost(accountsTotal.coerceAtLeast(1)),
                accountsFromCache = accountsFromCache +
                    if (ok && result.fromCache) result.usernames.size else 0
            )
        }
        refreshSkippedHosts()
    }

    private fun resetFeedRun() {
        challengeQueue.clear()
        challengeRetry = null
        challengeGeneration += 1
        runDeadHosts = ConcurrentHashMap.newKeySet()
        runChallengedHosts = ConcurrentHashMap.newKeySet()
        runUsedHosts = ConcurrentHashMap.newKeySet()
        olderPages.clear()
        setState {
            copy(
                challengedHosts = emptyList(),
                challengeVerification = null,
                canLoadOlder = false,
                isLoadingOlder = false
            )
        }
    }

    private fun verificationFor(host: String): ChallengeVerification? {
        val chunk = challengeQueue.firstChunk(host) ?: return null
        val frontend = challengeRetry?.frontend ?: multiFrontend
        val url = runCatching {
            FrontendAdapter.forType(frontend).timelineUrls(
                host,
                chunk.usernames,
                maxUsersPerBatch = chunk.usernames.size.coerceAtLeast(1)
            ).single()
        }.getOrNull() ?: return null
        return ChallengeVerification(host = host, url = url)
    }

    /** With no page open, passing a check reloads the whole feed instead of one chunk. */
    private fun queueShellChallenges(hosts: Collection<String>, assignments: List<FeedChunkAssignment>) {
        if (hosts.isEmpty()) return
        for (host in hosts) {
            val chunk = assignments.firstOrNull { it.instanceUrl.equals(host, ignoreCase = true) }
                ?: assignments.firstOrNull()
                ?: continue
            challengeQueue.add(host, chunk)
        }
        setState { copy(challengedHosts = challengeQueue.hosts) }
    }

    private fun showNextChallenge() {
        val next = challengeQueue.hosts.firstNotNullOfOrNull { verificationFor(it) }
        setState { copy(challengedHosts = challengeQueue.hosts, challengeVerification = next) }
    }

    /** Refetches accounts a bot check blocked, on the same Nitter now that it is passed. */
    private fun retryChallengedHost(host: String) {
        val context = challengeRetry ?: return
        val chunks = challengeQueue.take(host)
        if (chunks.isEmpty()) return
        val generation = challengeGeneration
        viewModelScope.launch {
            prepareFeedFetchCookies(listOf(host))
            for (chunk in chunks) {
                var challenged = false
                val fetched = FeedChunkFetcher.fetchChunkTryingHosts(
                    usernames = chunk.usernames,
                    hosts = listOf(host),
                    shellBase = context.shellInstance,
                    userAgent = defaultUserAgent,
                    instanceKeys = context.keys,
                    frontend = context.frontend,
                    onChallenge = { challenged = true }
                )
                if (generation != challengeGeneration) return@launch
                when {
                    fetched != null -> {
                        cacheChunk(fetched, chunk.usernames)
                        applyRecoveredChunk(fetched)
                    }
                    challenged -> challengeQueue.add(host, chunk)
                }
            }
            setState { copy(challengedHosts = challengeQueue.hosts) }
        }
    }

    /** Past its bot check but refusing multi-account timelines: load its accounts elsewhere. */
    private fun moveChunksOffHost(host: String) {
        HostLimitations.setAside(host, HostLimitation.MULTI_TIMELINE_DISABLED)
        refreshSkippedHosts()
        val context = challengeRetry ?: return
        val chunks = challengeQueue.take(host)
        if (chunks.isEmpty()) return
        val generation = challengeGeneration
        viewModelScope.launch {
            val hosts = FeedHostScoreboard.rank(
                context.pool.filterNot {
                    it.equals(host, ignoreCase = true) ||
                        it.trimEnd('/').lowercase() in runChallengedHosts
                }
            )
            prepareFeedFetchCookies(hosts)
            for (chunk in chunks) {
                val fetched = FeedChunkFetcher.fetchChunkTryingHosts(
                    usernames = chunk.usernames,
                    hosts = hosts,
                    shellBase = context.shellInstance,
                    userAgent = defaultUserAgent,
                    instanceKeys = context.keys,
                    frontend = context.frontend,
                    deadHosts = runDeadHosts,
                    maxHostAttempts = 1 + FeedBatchAppend.PEER_FAILOVER_HOSTS
                )
                if (generation != challengeGeneration) return@launch
                if (fetched != null) {
                    cacheChunk(fetched, chunk.usernames)
                    registerOlderPage(chunk.usernames, fetched.instanceUrl, fetched.pageUrl, fetched.html)
                    applyRecoveredChunk(fetched)
                }
            }
            setState { copy(challengedHosts = challengeQueue.hosts) }
        }
    }

    private fun registerOlderPage(usernames: List<String>, host: String, pageUrl: String, html: String) {
        val key = usernames.map { it.lowercase() }.sorted().joinToString(",")
        val query = TimelineHtml.nextPageQuery(html)
        if (query == null) {
            olderPages.remove(key)
        } else {
            olderPages[key] = OlderPage(host.trimEnd('/'), pageUrl.substringBefore('?') + query)
        }
        setState { copy(canLoadOlder = olderPages.isNotEmpty()) }
    }

    /** Fetches every chunk's next page from its own Nitter and merges the older posts in. */
    private fun loadOlderPages() {
        val shellInstance = state.value.selectedInstanceUrl
        if (state.value.isLoadingOlder || olderPages.isEmpty() || shellInstance.isBlank()) return
        val pages = olderPages.toMap()
        val generation = challengeGeneration
        setState { copy(isLoadingOlder = true) }
        viewModelScope.launch {
            try {
                val keys = preferencesRepository.instanceKeys.first()
                val gate = Semaphore(peerConcurrency())
                coroutineScope {
                    pages.map { (key, page) ->
                        async {
                            gate.withPermit {
                                val fetched = FeedChunkFetcher.fetchTimelineHtml(
                                    url = page.url,
                                    fetchBase = page.host,
                                    shellBase = shellInstance,
                                    userAgent = defaultUserAgent,
                                    instanceKey = keys[UserPreferencesRepository.hostOf(page.host)].orEmpty()
                                )
                                if (generation != challengeGeneration) return@withPermit
                                if (fetched == null) {
                                    olderPages.remove(key)
                                    return@withPermit
                                }
                                registerOlderPage(key.split(','), page.host, page.url, fetched.html)
                                setState { copy(appendHtmlChunks = appendHtmlChunks + fetched.html) }
                            }
                        }
                    }.awaitAll()
                }
            } finally {
                if (generation == challengeGeneration) {
                    setState { copy(isLoadingOlder = false, canLoadOlder = olderPages.isNotEmpty()) }
                }
            }
        }
    }

    private fun applyRecoveredChunk(fetched: FeedChunkFetchResult) {
        setState {
            val used = if (multiInstancesUsed.any { it.equals(fetched.instanceUrl, ignoreCase = true) }) {
                multiInstancesUsed
            } else {
                multiInstancesUsed + fetched.instanceUrl
            }
            copy(
                appendHtmlChunks = appendHtmlChunks + fetched.html,
                multiInstancesUsed = used,
                multiChunksFailed = (multiChunksFailed - 1).coerceAtLeast(0)
            )
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
        val onionOnly = onionOnlyActive()
        val safePrimary = if (onionOnly) {
            TorOnionPreference.resolveOnionInstance(
                preferred = primary,
                pool = multiPool + primary + knownInstances
            ) ?: run {
                showOnionUnavailable(primary)
                return
            }
        } else {
            primary.trim().trimEnd('/')
        }
        val onionShell = TorOnionPreference.isOnionInstance(safePrimary)
        val urls = FrontendAdapter.forType(frontend)
            .timelineUrls(
                safePrimary,
                usernames,
                maxUsersPerBatch = if (onionShell) 1 else usersPerBatch.value
            )
        batchUrls.value = urls
        batchIndex.value = 0
        val peerUrls = urls.drop(1)
        val totalAccounts = TimelineUrlUsers.countInUrls(urls)
        val shellAccounts = TimelineUrlUsers.parse(urls.first()).size
        setState {
            copy(
                // Keep preference-driven spread flag so the Nitters status stays visible
                // even when the multi-host shell falls back to a single working instance.
                feedSpreadAcrossInstances = spreadAcrossInstances.value,
                selectedInstanceUrl = safePrimary,
                currentUrl = urls.first(),
                displayedPageUrl = null,
                batchIndex = 0,
                batchCount = urls.size,
                appendBatchUrls = emptyList(),
                appendHtmlChunks = emptyList(),
                feedShellHtml = null,
                    feedShellStyleAssets = null,
                accountsChecked = shellAccounts,
                accountsTotal = totalAccounts,
                accountsFromCache = 0,
                accountsLoadComplete = peerUrls.isEmpty(),
                multiInstancesUsed = listOf(safePrimary),
                multiChunksLoaded = 1,
                multiChunksTotal = urls.size,
                multiLoadComplete = peerUrls.isEmpty(),
                isError = false,
                errorMessage = null,
                isLoading = isConnected
            )
        }
        if (state.value.disableWebJavascript) {
            prefetchShellHtml(urls.first(), safePrimary)
        }
        if (peerUrls.isNotEmpty()) {
            startPeerAppend(peerUrls, safePrimary, frontend)
        }
    }

    private fun prefetchShellHtml(pageUrl: String, instanceUrl: String) {
        viewModelScope.launch {
            val keys = preferencesRepository.instanceKeys.first()
            val fetched = FeedChunkFetcher.fetchTimelineHtml(
                url = pageUrl,
                fetchBase = instanceUrl,
                shellBase = instanceUrl,
                userAgent = defaultUserAgent,
                instanceKey = keys[UserPreferencesRepository.hostOf(instanceUrl)].orEmpty()
            )
            if (fetched != null &&
                fetched.html.isNotBlank() &&
                state.value.currentUrl.trimEnd('/') == pageUrl.trimEnd('/')
            ) {
                setState {
                    copy(
                        feedShellHtml = fetched.html,
                        feedShellStyleAssets = fetched.styleAssets.ifBlank { null }
                    )
                }
            }
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
                feedShellHtml = null,
                    feedShellStyleAssets = null,
                multiInstancesUsed = emptyList(),
                multiChunksLoaded = 0,
                multiLoadComplete = false
            )
        }
        bootstrapMultiFeed(assignments, multiPool, multiPrimaryFallback, multiFrontend)
    }

    /**
     * Onion-only applies with in-app SOCKS **or** when the selected host is already
     * `.onion` (system Tor VPN: SOCKS stays off, but onions must still be usable).
     */
    private fun onionOnlyActive(): Boolean {
        if (!preferOnionWhenTor.value) return false
        val socksOn = socksEnabled.value || SocksProxyController.current().enabled
        return socksOn || TorOnionPreference.isOnionInstance(state.value.selectedInstanceUrl)
    }

    /** Limitations that make a Nitter useless in the current feed mode. */
    private fun relevantLimitations(): Set<HostLimitation> = buildSet {
        add(HostLimitation.MULTI_TIMELINE_DISABLED)
        if (preferRss.value) add(HostLimitation.NO_RSS)
        if (state.value.disableWebJavascript) add(HostLimitation.NEEDS_JAVASCRIPT)
    }

    private fun refreshSkippedHosts() {
        val limited = HostLimitations.limitedHosts(
            listOf(state.value.selectedInstanceUrl) + poolCandidates,
            relevantLimitations()
        )
        setState { copy(skippedHosts = limited) }
    }

    /** Bot checks need JavaScript, so with it off the Nitter is recorded instead of verified. */
    private fun recordChallenge(host: String): Boolean {
        if (!state.value.disableWebJavascript) return false
        HostLimitations.setAside(host, HostLimitation.NEEDS_JAVASCRIPT)
        return true
    }

    private fun instancePool(primary: String, custom: Set<String>): List<String> {
        val socksOn = socksEnabled.value || SocksProxyController.current().enabled
        val onionOnly = onionOnlyActive()
        val disabled = disabledInstanceUrls.value.map { it.trimEnd('/').lowercase() }.toSet()
        val primaryHost = primary.trim().trimEnd('/')
        val onionReachable = TorOnionPreference.onionReachable(socksOn, primaryHost)
        val preset = activePoolPresetUrls.value
        val basePool = if (!preset.isNullOrEmpty()) {
            preset + primaryHost
        } else {
            knownInstances + discoveredInstances.value + custom + primaryHost
        }
        val candidates = basePool
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .filter { onionReachable || !TorOnionPreference.isOnionInstance(it) }
            .filter { url ->
                val key = url.lowercase()
                key !in disabled || key == primaryHost.lowercase()
            }
            .distinctBy { it.lowercase() }
        poolCandidates = candidates
        val limited = HostLimitations.limitedHosts(candidates, relevantLimitations())
        refreshSkippedHosts()
        val limitedKeys = limited.map { it.host.lowercase() }.toSet()
        val pool = candidates.filter {
            it.equals(primaryHost, ignoreCase = true) || it.lowercase() !in limitedKeys
        }
        val resolved = pool.ifEmpty {
            listOf(primaryHost).filter { it.isNotBlank() }
        }
        // Onion-only: never keep clearnet hosts in the live pool (spread or single).
        if (onionOnly) return TorOnionPreference.restrictPool(resolved, onionOnly = true)
        // SOCKS without onion-only: most public Nitters block Tor exits. Spreading
        // across them collapses onto the one that works — keep Tor-capable hosts.
        if (socksOn && spreadAcrossInstances.value) {
            val torCapable = resolved.filter { url ->
                TorOnionPreference.isOnionInstance(url) ||
                    UrlPolicy.isTorFriendlyInstance(url, torFriendlyInstances) ||
                    custom.any { it.equals(url, ignoreCase = true) } ||
                    url.equals(primaryHost, ignoreCase = true)
            }
            if (torCapable.size >= 2) return torCapable.distinctBy { it.lowercase() }
            if (torCapable.isNotEmpty()) return torCapable.distinctBy { it.lowercase() }
        }
        return resolved
    }

    private fun applyBatchUrl(instanceUrl: String, resetBrowsing: Boolean) {
        val urls = batchUrls.value
        if (urls.isEmpty()) return
        val index = batchIndex.value.coerceIn(0, urls.lastIndex)
        if (index != batchIndex.value) batchIndex.value = index
        val rootUrl = urls[index]
        val snapshot = state.value
        val changedInstance = snapshot.selectedInstanceUrl != instanceUrl
        val peerUrls = appendUrlsFor(index)
        val totalAccounts = TimelineUrlUsers.countInUrls(urls)
        val shellAccounts = TimelineUrlUsers.parse(rootUrl).size
        if (!resetBrowsing && !changedInstance && isBrowsingAwayFromBatchRoot(snapshot, rootUrl)) {
            if (snapshot.batchIndex != index ||
                snapshot.batchCount != urls.size ||
                snapshot.selectedInstanceUrl != instanceUrl ||
                !snapshot.hasSubscriptions
            ) {
                setState {
                    copy(
                        hasSubscriptions = true,
                        selectedInstanceUrl = instanceUrl,
                        batchIndex = index,
                        batchCount = urls.size
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
            !snapshot.accountsLoadComplete ||
            snapshot.accountsTotal != totalAccounts
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
                    appendBatchUrls = emptyList(),
                    appendHtmlChunks = emptyList(),
                    feedShellHtml = null,
                    feedShellStyleAssets = null,
                    accountsChecked = shellAccounts,
                    accountsTotal = totalAccounts,
                    accountsFromCache = 0,
                    accountsLoadComplete = peerUrls.isEmpty(),
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
            if (state.value.disableWebJavascript) {
                prefetchShellHtml(url, instanceUrl)
            }
            if (peerUrls.isNotEmpty()) {
                startPeerAppend(peerUrls, instanceUrl, state.value.frontend)
            } else {
                bypassTimelineCache = false
            }
        }
    }

    /** Remaining account chunks append into one timeline (no left/right batch paging). */
    private fun appendUrlsFor(index: Int): List<String> {
        if (spreadAcrossInstances.value) return emptyList()
        val urls = batchUrls.value
        if (index !in urls.indices) return emptyList()
        return urls.drop(index + 1)
    }

    private fun startPeerAppend(
        urls: List<String>,
        instanceUrl: String,
        frontend: FrontendType
    ) {
        multiFetchJob?.cancel()
        multiFetchJob = viewModelScope.launch {
            val self = coroutineContext.job
            try {
                val keys = preferencesRepository.instanceKeys.first()
                val shell = instanceUrl.trimEnd('/')
                delay(FeedBatchAppend.INITIAL_DELAY_MS.toLong())
                val gate = Semaphore(peerConcurrency())
                coroutineScope {
                    urls.map { pageUrl ->
                        async {
                            gate.withPermit {
                                val result = fetchSingleInstancePeer(pageUrl, shell, keys)
                                if (result.usernames.isEmpty()) return@withPermit
                                setState {
                                    copy(
                                        appendHtmlChunks = if (result.html != null) {
                                            appendHtmlChunks + result.html
                                        } else {
                                            appendHtmlChunks
                                        },
                                        accountsChecked = (accountsChecked + result.usernames.size)
                                            .coerceAtMost(accountsTotal.coerceAtLeast(1)),
                                        accountsFromCache = accountsFromCache +
                                            if (result.fromCache) result.usernames.size else 0
                                    )
                                }
                                refreshSkippedHosts()
                            }
                        }
                    }.awaitAll()
                }
                bypassTimelineCache = false
            } finally {
                if (multiFetchJob === self) {
                    setState { copy(accountsLoadComplete = true) }
                }
            }
        }
    }

    private suspend fun fetchSingleInstancePeer(
        pageUrl: String,
        shell: String,
        keys: Map<String, String>
    ): MultiPeerResult {
        val users = TimelineUrlUsers.parse(pageUrl)
        val chunk = FeedChunkAssignment(shell, users, pageUrl)
        if (users.isEmpty()) {
            return MultiPeerResult(chunk, null, shell, fromCache = false)
        }
        val cacheKey = FeedTimelineCache.key(
            shell + if (preferRss.value) ":rss" else ":html",
            users
        )
        val cached = if (!bypassTimelineCache) feedTimelineCache?.get(cacheKey) else null
        if (cached != null) {
            return MultiPeerResult(chunk, cached, shell, fromCache = true)
        }
        val instanceKey = keys[UserPreferencesRepository.hostOf(shell)].orEmpty()
        prepareFeedFetchCookies(listOf(shell))
        val challengedHosts = mutableSetOf<String>()
        val html = if (preferRss.value) {
            val rssHtml = NitterRss.fetchUsersTimelineHtml(
                instanceUrl = shell,
                usernames = users,
                userAgent = defaultUserAgent,
                instanceKey = instanceKey
            )?.let { FeedChunkFetcher.rewriteOrigins(it, shell, shell) }
            rssHtml ?: FeedChunkFetcher.fetchTimelineHtml(
                url = pageUrl,
                fetchBase = shell,
                shellBase = shell,
                userAgent = defaultUserAgent,
                instanceKey = instanceKey,
                onChallenge = {
                    recordChallenge(it)
                    challengedHosts += it
                }
            )?.html
        } else {
            FeedChunkFetcher.fetchTimelineHtml(
                url = pageUrl,
                fetchBase = shell,
                shellBase = shell,
                userAgent = defaultUserAgent,
                instanceKey = instanceKey,
                onChallenge = {
                    recordChallenge(it)
                    challengedHosts += it
                }
            )?.html
        }
        if (!html.isNullOrBlank()) {
            feedTimelineCache?.put(cacheKey, html, shell, users)
        }
        return MultiPeerResult(chunk, html, shell, fromCache = false, challengedHosts = challengedHosts)
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
                // Transient history waypoint — do not touch feed session / profile chrome.
                if (event.url.equals("about:blank", ignoreCase = true)) return
                val localDoc = UrlPolicy.isWebViewLocalDocumentUrl(event.url)
                val feedShellDoc = event.url.startsWith("data:", ignoreCase = true)
                val shellLive = !state.value.feedShellHtml.isNullOrBlank()
                setState {
                    copy(
                        // Live Feed All session: deep post/profile navigations must not
                        // look like a full reload. Only pull-to-refresh rebuilds the feed.
                        isLoading = when {
                            localDoc || feedShellDoc -> false
                            shellLive -> false
                            else -> true
                        },
                        isError = false,
                        errorMessage = null,
                        displayedPageUrl = event.url,
                        currentUsername = if (feedShellDoc) null else currentUsername,
                        isSubscribed = if (feedShellDoc) false else isSubscribed,
                        avatarUrl = if (feedShellDoc) null else avatarUrl,
                        showUnsubscribeDialog = if (feedShellDoc) false else showUnsubscribeDialog
                    )
                }
                if (!localDoc) checkIfProfile(event.url)
            }
            is FeedEvent.OnPageFinished -> {
                if (event.url.equals("about:blank", ignoreCase = true)) return
                val localDoc = UrlPolicy.isWebViewLocalDocumentUrl(event.url)
                val feedShellDoc = event.url.startsWith("data:", ignoreCase = true)
                setState {
                    copy(
                        isLoading = false,
                        isRefreshing = false,
                        displayedPageUrl = event.url,
                        currentUsername = if (feedShellDoc) null else currentUsername,
                        isSubscribed = if (feedShellDoc) false else isSubscribed,
                        avatarUrl = if (feedShellDoc) null else avatarUrl,
                        showUnsubscribeDialog = if (feedShellDoc) false else showUnsubscribeDialog
                    )
                }
                if (!localDoc) checkIfProfile(event.url)
            }
            is FeedEvent.OnPageError -> {
                val snapshot = state.value
                // Once the OkHttp shell exists, never failover / ErrorScreen — that wiped
                // WebView history and forced a full Feed All fetch. Pull-to-refresh only.
                if (!snapshot.feedShellHtml.isNullOrBlank() ||
                    UrlPolicy.isWebViewLocalDocumentUrl(snapshot.displayedPageUrl)
                ) {
                    setState { copy(isLoading = false, isRefreshing = false) }
                    return
                }
                if (snapshot.feedSpreadAcrossInstances &&
                    snapshot.currentUsername == null &&
                    multiPlan.isNotEmpty() &&
                    snapshot.feedShellHtml.isNullOrBlank()
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
                val wasConnected = state.value.isConnected
                setState { copy(isConnected = event.isConnected) }
                if (!event.isConnected) {
                    setState { copy(isLoading = false, isRefreshing = false) }
                } else if (!wasConnected) {
                    val snap = state.value
                    if (snap.isError) {
                        setState { copy(isError = false) }
                    }
                    // Mid-bootstrap disconnect left an empty URL with no WebView to pull-refresh.
                    if (snap.hasSubscriptions && snap.currentUrl.isBlank()) {
                        onEvent(FeedEvent.Refresh)
                    }
                }
            }
            is FeedEvent.Refresh -> {
                lastFeedFingerprint = null
                bypassTimelineCache = true
                feedTimelineCache?.evictAll()
                sessionDeadHosts.clear()
                shellFailoverAttempts = 0
                shuffleSeed.value = System.nanoTime()
                setState {
                    copy(
                        isRefreshing = state.value.isConnected,
                        isLoading = state.value.isConnected,
                        isError = false,
                        // Leave posts/profiles so applyBatchUrl rebuilds the Feed All root.
                        displayedPageUrl = null,
                        currentUsername = null,
                        isSubscribed = false,
                        avatarUrl = null,
                        appendHtmlChunks = emptyList(),
                        feedShellHtml = null,
                    feedShellStyleAssets = null,
                        accountsChecked = 0,
                        accountsFromCache = 0,
                        accountsLoadComplete = false,
                        multiInstancesUsed = emptyList(),
                        multiChunksLoaded = 0,
                        multiLoadComplete = false,
                        loadGeneration = loadGeneration + 1
                    )
                }
            }
            is FeedEvent.ClearError -> setState { copy(isError = false) }
            FeedEvent.ShowMultiInstanceStatus -> setState { copy(showMultiInstanceStatus = true) }
            FeedEvent.DismissMultiInstanceStatus -> setState { copy(showMultiInstanceStatus = false) }
            FeedEvent.VerifyChallenges -> showNextChallenge()
            is FeedEvent.ChallengePassed -> {
                if (challengeRetry == null) {
                    setState { copy(challengeVerification = null) }
                    onEvent(FeedEvent.Refresh)
                } else {
                    retryChallengedHost(event.host)
                    showNextChallenge()
                }
            }
            is FeedEvent.ChallengeUnsupported -> {
                if (challengeRetry == null) {
                    HostLimitations.setAside(event.host, HostLimitation.MULTI_TIMELINE_DISABLED)
                    setState { copy(challengeVerification = null) }
                    onEvent(FeedEvent.Refresh)
                } else {
                    moveChunksOffHost(event.host)
                    showNextChallenge()
                }
            }
            is FeedEvent.SkipChallenge -> {
                challengeQueue.take(event.host)
                showNextChallenge()
            }
            FeedEvent.DismissChallenge -> setState { copy(challengeVerification = null) }
            FeedEvent.LoadOlder -> loadOlderPages()
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
            if (UrlPolicy.isWebViewLocalDocumentUrl(url)) {
                if (state.value.displayedPageUrl != url) return@launch
                if (state.value.currentUsername != null) {
                    setState {
                        copy(
                            currentUsername = null,
                            isSubscribed = false,
                            avatarUrl = null,
                            showUnsubscribeDialog = false
                        )
                    }
                }
                return@launch
            }
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
        val seed: Long,
        val disabledInstances: Set<String> = emptySet(),
        val onionOnly: Boolean = false,
        val preferRss: Boolean = false
    )

    companion object {
        private const val ONION_UNAVAILABLE_MESSAGE =
            "Onion-only mode needs a working .onion Nitter. Add one or disable onion-only."
        private const val SPREAD_UNAVAILABLE_MESSAGE =
            "Could not open any Nitter for this feed. With Tor VPN, pick a working .onion (leave SOCKS off) and retry."
        private const val JS_REQUIRED_MESSAGE =
            "This Nitter only works with JavaScript. Turn Disable JavaScript off, or pick another instance."

        fun defaultUserAgent(context: android.content.Context): String =
            UserPreferencesRepository.browserUserAgent(WebSettings.getDefaultUserAgent(context))
    }
}
