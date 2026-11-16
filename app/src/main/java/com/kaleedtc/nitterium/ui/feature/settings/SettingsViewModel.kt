package com.kaleedtc.nitterium.ui.feature.settings

import android.app.Application
import com.kaleedtc.nitterium.data.frontend.FrontendType
import com.kaleedtc.nitterium.data.frontend.InstanceSettingsCookies
import com.kaleedtc.nitterium.data.repository.CookieInstanceOptionsStore
import androidx.lifecycle.viewModelScope
import com.kaleedtc.nitterium.R
import com.kaleedtc.nitterium.data.model.InstanceDirectoryEntry
import com.kaleedtc.nitterium.data.model.NitterInstanceSettings
import com.kaleedtc.nitterium.data.model.ReleaseNotesPreferences
import com.kaleedtc.nitterium.data.model.SocksProxySettings
import com.kaleedtc.nitterium.data.repository.UserPreferencesRepository
import com.kaleedtc.nitterium.data.repository.BackupRepository
import com.kaleedtc.nitterium.data.security.InstanceHealthProbe
import com.kaleedtc.nitterium.data.security.PublicInstanceDirectory
import com.kaleedtc.nitterium.data.security.TorOnionPreference
import com.kaleedtc.nitterium.ui.common.MviViewModel
import com.kaleedtc.nitterium.NitteriumApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val preferencesRepository: UserPreferencesRepository,
    private val application: Application,
    private val backupRepository: BackupRepository
) : MviViewModel<SettingsState, SettingsEvent, SettingsEffect>(SettingsState()) {

    private val availableInstances = application.resources.getStringArray(R.array.default_instance_urls).toList()

    private val instanceOptions = CookieInstanceOptionsStore()
    private val _instanceSettings = MutableStateFlow(NitterInstanceSettings())
    private val _appVersion = try {
        val packageInfo = application.packageManager.getPackageInfo(application.packageName, 0)
        packageInfo.versionName ?: "Unknown"
    } catch (_: Exception) {
        "Unknown"
    }

    init {
        viewModelScope.launch {
            combine(
                preferencesRepository.instanceUrl,
                preferencesRepository.dynamicColor,
                preferencesRepository.trueBlack,
                preferencesRepository.enableSiteHeader,
                preferencesRepository.showNavLabels,
                preferencesRepository.defaultTab,
                preferencesRepository.darkTheme,
                preferencesRepository.blockDirectX,
                preferencesRepository.customInstances,
                _instanceSettings,
                preferencesRepository.frontendMode,
                preferencesRepository.frontend,
                preferencesRepository.releaseNotesPreferences,
                preferencesRepository.socksProxy,
                preferencesRepository.instanceKeys,
                preferencesRepository.feedUsersPerBatch,
                preferencesRepository.feedSpreadAcrossInstances,
                preferencesRepository.feedPreferRss,
                preferencesRepository.mutedWords,
                preferencesRepository.discoveredInstances
            ) { args ->
                val url = args[0] as String
                val dynamic = args[1] as Boolean
                val trueBlack = args[2] as Boolean
                val siteHeader = args[3] as Boolean
                val showLabels = args[4] as Boolean
                val defaultTab = args[5] as String
                val dark = args[6] as Boolean?
                val blockDirectX = args[7] as Boolean
                @Suppress("UNCHECKED_CAST")
                val customInstances = (args[8] as Set<String>).toList()
                val instanceSettings = args[9] as NitterInstanceSettings

                @Suppress("UNCHECKED_CAST")
                val instanceKeys = args[14] as Map<String, String>
                @Suppress("UNCHECKED_CAST")
                val discovered = (args[19] as Set<String>).toList()
                val allInstances = (availableInstances + discovered + customInstances)
                    .distinctBy { it.trimEnd('/').lowercase() }

                SettingsState(
                    frontendMode = args[10] as FrontendType,
                    frontend = args[11] as FrontendType,
                    isLoading = false,
                    instanceUrl = url,
                    isDynamicColor = dynamic,
                    isTrueBlack = trueBlack,
                    isSiteHeaderEnabled = siteHeader,
                    isNavLabelsEnabled = showLabels,
                    isBlockDirectXEnabled = blockDirectX,
                    defaultTab = defaultTab,
                    isDarkTheme = dark,
                    availableInstances = allInstances,
                    customInstances = customInstances,
                    discoveredInstances = discovered,
                    instanceSettings = instanceSettings,
                    appVersion = _appVersion,
                    showReleaseNotesAfterUpdates = (args[12] as ReleaseNotesPreferences).enabled,
                    socksProxy = args[13] as SocksProxySettings,
                    instanceKey = instanceKeys[UserPreferencesRepository.hostOf(url)].orEmpty(),
                    feedUsersPerBatch = args[15] as Int,
                    feedSpreadAcrossInstances = args[16] as Boolean,
                    feedPreferRss = args[17] as Boolean,
                    mutedWords = @Suppress("UNCHECKED_CAST") (args[18] as List<String>)
                )
            }.collect { newState ->
                setState {
                    // Preserve fields owned by separate collectors—this combine used to
                    // reset onion-only / no-JS / disabled-instance toggles on every emit.
                    newState.copy(
                        isBackupBusy = isBackupBusy,
                        isScanningInstances = isScanningInstances,
                        isProbingInstances = isProbingInstances,
                        instanceProbeChecked = instanceProbeChecked,
                        instanceProbeTotal = instanceProbeTotal,
                        backupToRestore = backupToRestore,
                        socksPreferOnionInstances = socksPreferOnionInstances,
                        disableWebJavascript = disableWebJavascript,
                        disabledInstanceUrls = disabledInstanceUrls,
                        instanceCatalog = instanceCatalog,
                        instancePoolPresets = instancePoolPresets,
                        activeInstancePoolPresetId = activeInstancePoolPresetId
                    )
                }
            }
        }

        viewModelScope.launch {
            preferencesRepository.socksPreferOnionInstances.collect { prefer ->
                setState { copy(socksPreferOnionInstances = prefer) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.disableWebJavascript.collect { disabled ->
                setState { copy(disableWebJavascript = disabled) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.disabledInstanceUrls.collect { urls ->
                setState { copy(disabledInstanceUrls = urls) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.discoveredInstanceCatalog.collect { catalog ->
                setState { copy(instanceCatalog = catalog) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.instancePoolPresets.collect { presets ->
                setState { copy(instancePoolPresets = presets) }
            }
        }
        viewModelScope.launch {
            preferencesRepository.activeInstancePoolPresetId.collect { id ->
                setState { copy(activeInstancePoolPresetId = id) }
            }
        }

        // Load settings when URL changes
        viewModelScope.launch {
            combine(preferencesRepository.instanceUrl, preferencesRepository.frontend) { url, frontend -> url to frontend }
                .collect { (url, frontend) -> loadSettingsFromCookies(url, frontend) }
        }
    }

    private fun loadSettingsFromCookies(url: String, frontend: FrontendType) {
        instanceOptions.ensureDefaults(url, frontend)
        _instanceSettings.value = instanceOptions.read(url, frontend)
    }

    private fun isOnionOnlyActive(): Boolean {
        val s = state.value
        return s.socksProxy.enabled && s.socksPreferOnionInstances
    }

    private suspend fun applyOnionInstancePreference(socksEnabled: Boolean, preferOnion: Boolean) {
        val onionOnly = socksEnabled && preferOnion
        val current = preferencesRepository.instanceUrl.first()
        if (onionOnly) {
            if (TorOnionPreference.isOnionInstance(current)) return
            val candidates = availableInstances +
                preferencesRepository.customInstances.first() +
                preferencesRepository.discoveredInstances.first()
            val onion = TorOnionPreference.firstOnion(candidates) ?: return
            val backup = preferencesRepository.instanceUrlBeforeOnion.first()
            if (backup.isNullOrBlank()) {
                preferencesRepository.setInstanceUrlBeforeOnion(current)
            }
            preferencesRepository.setInstanceUrl(onion)
            setEffect {
                SettingsEffect.ShowSnackbar(application.getString(R.string.socks_switched_to_onion))
            }
            return
        }
        val backup = preferencesRepository.instanceUrlBeforeOnion.first() ?: return
        if (TorOnionPreference.isOnionInstance(current)) {
            preferencesRepository.setInstanceUrl(backup)
            setEffect {
                SettingsEffect.ShowSnackbar(application.getString(R.string.socks_restored_clearnet))
            }
        }
        preferencesRepository.setInstanceUrlBeforeOnion(null)
    }

    override fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.ExportBackup -> if (!state.value.isBackupBusy) {
                setEffect { SettingsEffect.LaunchBackupExport(event.scope) }
            }
            is SettingsEvent.ImportBackup -> if (!state.value.isBackupBusy) {
                setEffect { SettingsEffect.LaunchBackupImport }
            }
            is SettingsEvent.BackupDestinationChosen -> event.uri?.let { uri ->
                performBackupOperation(R.string.backup_export_failed) {
                    backupRepository.export(uri, event.scope, availableInstances).getOrThrow()
                    setEffect { SettingsEffect.ShowSnackbar(application.getString(R.string.backup_export_success)) }
                }
            }
            is SettingsEvent.BackupFileChosen -> event.uri?.let { uri ->
                performBackupOperation(R.string.backup_read_failed) {
                    val backup = backupRepository.read(uri).getOrThrow()
                    setState { copy(backupToRestore = backup) }
                }
            }
            is SettingsEvent.CancelRestoreBackup -> if (!state.value.isBackupBusy) {
                setState { copy(backupToRestore = null) }
            }
            is SettingsEvent.ConfirmRestoreBackup -> state.value.backupToRestore?.let { backup ->
                performBackupOperation(R.string.backup_restore_failed) {
                    backupRepository.restore(backup).getOrThrow()
                    setState { copy(backupToRestore = null) }
                    loadSettingsFromCookies(preferencesRepository.instanceUrl.first(), preferencesRepository.frontend.first())
                    setEffect { SettingsEffect.ShowSnackbar(application.getString(R.string.backup_restore_success)) }
                }
            }
            is SettingsEvent.UpdateFrontend -> viewModelScope.launch { preferencesRepository.setFrontend(event.type) }
            is SettingsEvent.OpenUrl -> {
                setEffect { SettingsEffect.NavigateToUrl(event.url) }
            }
            is SettingsEvent.UpdateInstanceUrl -> {
                if (com.kaleedtc.nitterium.data.security.UrlPolicy.instanceUrl(event.url) == null) {
                    setEffect { SettingsEffect.ShowSnackbar(application.getString(R.string.instance_url_invalid)) }
                    return
                }
                if (isOnionOnlyActive() && !TorOnionPreference.isOnionInstance(event.url)) {
                    setEffect {
                        SettingsEffect.ShowSnackbar(application.getString(R.string.socks_onion_only_required))
                    }
                    return
                }
                viewModelScope.launch {
                    preferencesRepository.setInstanceUrl(event.url)
                }
            }
            is SettingsEvent.AddCustomInstance -> {
                if (com.kaleedtc.nitterium.data.security.UrlPolicy.instanceUrl(event.url) == null) {
                    setEffect { SettingsEffect.ShowSnackbar(application.getString(R.string.instance_url_invalid)) }
                    return
                }
                viewModelScope.launch {
                    preferencesRepository.addCustomInstance(event.url)
                    if (isOnionOnlyActive() && !TorOnionPreference.isOnionInstance(event.url)) {
                        setEffect {
                            SettingsEffect.ShowSnackbar(application.getString(R.string.socks_onion_only_required))
                        }
                        return@launch
                    }
                    preferencesRepository.setInstanceUrl(event.url)
                }
            }
            is SettingsEvent.RemoveCustomInstance -> {
                viewModelScope.launch {
                    preferencesRepository.removeCustomInstance(event.url)
                    // If the current URL is removed, fall back to the default instance.
                    if (state.value.instanceUrl == event.url) {
                        val fallback = if (isOnionOnlyActive()) {
                            TorOnionPreference.firstOnion(
                                availableInstances +
                                    preferencesRepository.customInstances.first() +
                                    preferencesRepository.discoveredInstances.first()
                            ) ?: run {
                                setEffect {
                                    SettingsEffect.ShowSnackbar(
                                        application.getString(R.string.socks_onion_unavailable)
                                    )
                                }
                                return@launch
                            }
                        } else {
                            "https://shitter.thepixora.com"
                        }
                        preferencesRepository.setInstanceUrl(fallback)
                    }
                }
            }
            is SettingsEvent.UpdateDynamicColor -> {
                viewModelScope.launch {
                    preferencesRepository.setDynamicColor(event.enabled)
                }
            }
            is SettingsEvent.UpdateTrueBlack -> {
                viewModelScope.launch {
                    preferencesRepository.setTrueBlack(event.enabled)
                }
            }
            is SettingsEvent.UpdateSiteHeader -> {
                viewModelScope.launch {
                    preferencesRepository.setEnableSiteHeader(event.enabled)
                }
            }
            is SettingsEvent.UpdateNavLabels -> {
                viewModelScope.launch {
                    preferencesRepository.setShowNavLabels(event.enabled)
                }
            }
            is SettingsEvent.UpdateDefaultTab -> {
                viewModelScope.launch {
                    preferencesRepository.setDefaultTab(event.tab)
                }
            }
            is SettingsEvent.UpdateBlockDirectX -> {
                viewModelScope.launch {
                    preferencesRepository.setBlockDirectX(event.enabled)
                    if (event.enabled) {
                        instanceOptions.ensureMediaProxyEnabled(state.value.instanceUrl)
                        if (instanceOptions.setOption(
                                state.value.instanceUrl,
                                state.value.frontend,
                                "proxyVideos",
                                true
                            )
                        ) {
                            _instanceSettings.value = _instanceSettings.value.copy(proxyVideos = true)
                        }
                    }
                }
            }
            is SettingsEvent.UpdateDisableWebJavascript -> {
                setState { copy(disableWebJavascript = event.enabled) }
                viewModelScope.launch {
                    preferencesRepository.setDisableWebJavascript(event.enabled)
                }
            }
            is SettingsEvent.SetInstanceDisabled -> {
                viewModelScope.launch {
                    preferencesRepository.setInstanceDisabled(event.url, event.disabled)
                }
            }
            is SettingsEvent.UpdateDarkTheme -> {
                viewModelScope.launch {
                    preferencesRepository.setDarkTheme(event.isDark)
                }
            }
            is SettingsEvent.UpdateShowReleaseNotes -> {
                viewModelScope.launch {
                    preferencesRepository.setShowReleaseNotes(event.enabled)
                }
            }
            is SettingsEvent.UpdateSocksProxy -> {
                if (event.settings.enabled && !event.settings.isValid()) {
                    setEffect {
                        SettingsEffect.ShowSnackbar(application.getString(R.string.socks_proxy_invalid))
                    }
                    return
                }
                val wasEnabled = state.value.socksProxy.enabled
                viewModelScope.launch {
                    preferencesRepository.setSocksProxy(event.settings)
                    // Only switch instances when Tor itself is toggled—not on host/port saves.
                    if (wasEnabled != event.settings.enabled) {
                        applyOnionInstancePreference(
                            socksEnabled = event.settings.enabled,
                            preferOnion = preferencesRepository.socksPreferOnionInstances.first()
                        )
                    }
                }
            }
            is SettingsEvent.UpdateSocksPreferOnion -> {
                setState { copy(socksPreferOnionInstances = event.enabled) }
                viewModelScope.launch {
                    preferencesRepository.setSocksPreferOnionInstances(event.enabled)
                    applyOnionInstancePreference(
                        socksEnabled = preferencesRepository.socksProxy.first().enabled,
                        preferOnion = event.enabled
                    )
                }
            }
            is SettingsEvent.UpdateInstanceKey -> {
                viewModelScope.launch {
                    preferencesRepository.setInstanceKey(state.value.instanceUrl, event.key)
                }
            }
            is SettingsEvent.UpdateInstanceSetting -> {
                if (event.key == "proxyVideos" && !event.value && state.value.isBlockDirectXEnabled) {
                    setEffect {
                        SettingsEffect.ShowSnackbar(
                            application.getString(R.string.proxy_required_when_blocking_x)
                        )
                    }
                    return
                }
                val updated = InstanceSettingsCookies.update(_instanceSettings.value, event.key, event.value) ?: return
                if (instanceOptions.setOption(state.value.instanceUrl, state.value.frontend, event.key, event.value)) {
                    _instanceSettings.value = updated
                }
            }
            SettingsEvent.ClearInstanceCookies -> {
                val reset = instanceOptions.clearAndReset(state.value.instanceUrl, state.value.frontend)
                _instanceSettings.value = reset
                setEffect {
                    SettingsEffect.ShowSnackbar(application.getString(R.string.instance_cookies_cleared))
                }
            }
            is SettingsEvent.UpdateFeedUsersPerBatch -> {
                viewModelScope.launch {
                    preferencesRepository.setFeedUsersPerBatch(event.size)
                }
            }
            is SettingsEvent.UpdateFeedSpreadAcrossInstances -> {
                viewModelScope.launch {
                    preferencesRepository.setFeedSpreadAcrossInstances(event.enabled)
                }
            }
            is SettingsEvent.UpdateFeedPreferRss -> {
                viewModelScope.launch {
                    preferencesRepository.setFeedPreferRss(event.enabled)
                }
            }
            is SettingsEvent.AddMutedWord -> {
                viewModelScope.launch {
                    preferencesRepository.addMutedWord(event.word)
                }
            }
            is SettingsEvent.RemoveMutedWord -> {
                viewModelScope.launch {
                    preferencesRepository.removeMutedWord(event.word)
                }
            }
            SettingsEvent.ScanPublicInstances -> {
                if (state.value.isScanningInstances) return
                setState { copy(isScanningInstances = true) }
                viewModelScope.launch {
                    val onionOnly = isOnionOnlyActive()
                    val socksOn = preferencesRepository.socksProxy.first().enabled
                    val preferOnion = preferencesRepository.socksPreferOnionInstances.first()
                    // Keep onions when SOCKS is on or onion-only is preferred (Tor VPN path).
                    val result = runCatching {
                        PublicInstanceDirectory.scanHealthy(
                            includeOnion = socksOn || preferOnion || onionOnly
                        )
                    }.getOrNull()
                    if (result == null || result.sourcesOk == 0) {
                        setState { copy(isScanningInstances = false) }
                        setEffect {
                            SettingsEffect.ShowSnackbar(
                                application.getString(
                                    if (onionOnly) R.string.instance_scan_failed_tor
                                    else R.string.instance_scan_failed
                                )
                            )
                        }
                        return@launch
                    }
                    val previous = preferencesRepository.discoveredInstanceCatalog.first()
                        .associateBy { it.normalizedUrl().lowercase() }
                    val merged = result.entries.map { entry ->
                        val old = previous[entry.normalizedUrl().lowercase()]
                        if (old == null) entry else entry.copy(
                            worksHtml = old.worksHtml,
                            worksRss = old.worksRss,
                            worksWithoutJs = old.worksWithoutJs,
                            ddosProtection = old.ddosProtection ?: entry.ddosProtection,
                            lastProbedEpochMs = old.lastProbedEpochMs
                        )
                    }
                    preferencesRepository.setDiscoveredInstanceCatalog(merged)
                    setState { copy(isScanningInstances = false) }
                    setEffect {
                        SettingsEffect.ShowSnackbar(
                            application.getString(
                                if (onionOnly) R.string.instance_scan_onion_note
                                else R.string.instance_scan_found,
                                result.healthy.size
                            )
                        )
                    }
                }
            }
            SettingsEvent.ProbeInstanceAvailability -> {
                if (state.value.isProbingInstances) return
                viewModelScope.launch {
                    val catalog = preferencesRepository.discoveredInstanceCatalog.first()
                    val custom = preferencesRepository.customInstances.first()
                    val builtIn = availableInstances
                    val seeds = (catalog.map { it.normalizedUrl() } + custom + builtIn)
                        .map { it.trim().trimEnd('/') }
                        .filter { it.isNotBlank() }
                        .distinctBy { it.lowercase() }
                    if (seeds.isEmpty()) {
                        setEffect {
                            SettingsEffect.ShowSnackbar(
                                application.getString(R.string.instance_probe_empty)
                            )
                        }
                        return@launch
                    }
                    val byUrl = catalog.associateBy { it.normalizedUrl().lowercase() }.toMutableMap()
                    val toProbe = seeds.map { url ->
                        byUrl[url.lowercase()] ?: InstanceDirectoryEntry(url = url)
                    }
                    setState {
                        copy(
                            isProbingInstances = true,
                            instanceProbeChecked = 0,
                            instanceProbeTotal = toProbe.size
                        )
                    }
                    val probed = runCatching {
                        InstanceHealthProbe.probeAll(toProbe) { progress ->
                            setState {
                                copy(
                                    instanceProbeChecked = progress.checked,
                                    instanceProbeTotal = progress.total
                                )
                            }
                        }
                    }.getOrElse {
                        setState {
                            copy(
                                isProbingInstances = false,
                                instanceProbeChecked = 0,
                                instanceProbeTotal = 0
                            )
                        }
                        setEffect {
                            SettingsEffect.ShowSnackbar(
                                application.getString(R.string.instance_probe_failed)
                            )
                        }
                        return@launch
                    }
                    preferencesRepository.setDiscoveredInstanceCatalog(probed)
                    val broken = probed.count { it.worksHtml == false }
                    val working = probed.count { it.worksHtml == true }
                    for (entry in probed) {
                        when (entry.worksHtml) {
                            false -> preferencesRepository.setInstanceDisabled(
                                entry.normalizedUrl(),
                                true
                            )
                            true -> preferencesRepository.setInstanceDisabled(
                                entry.normalizedUrl(),
                                false
                            )
                            null -> Unit
                        }
                    }
                    setState {
                        copy(
                            isProbingInstances = false,
                            instanceProbeChecked = probed.size,
                            instanceProbeTotal = probed.size
                        )
                    }
                    setEffect {
                        SettingsEffect.ShowSnackbar(
                            application.getString(
                                R.string.instance_probe_done,
                                probed.size,
                                working,
                                broken
                            )
                        )
                    }
                }
            }
            is SettingsEvent.SaveInstancePoolPreset -> {
                viewModelScope.launch {
                    preferencesRepository.upsertInstancePoolPreset(
                        name = event.name,
                        urls = event.urls,
                        id = event.id
                    )
                }
            }
            is SettingsEvent.RemoveInstancePoolPreset -> {
                viewModelScope.launch {
                    preferencesRepository.removeInstancePoolPreset(event.id)
                }
            }
            is SettingsEvent.SetActiveInstancePoolPreset -> {
                viewModelScope.launch {
                    preferencesRepository.setActiveInstancePoolPresetId(event.id)
                }
            }
            SettingsEvent.ClearFeedCache -> {
                (application as? NitteriumApplication)?.feedTimelineCache?.evictAll()
                setEffect {
                    SettingsEffect.ShowSnackbar(
                        application.getString(R.string.feed_cache_cleared)
                    )
                }
            }
        }
    }

    private fun performBackupOperation(failureMessage: Int, action: suspend () -> Unit) {
        if (state.value.isBackupBusy) return
        setState { copy(isBackupBusy = true) }
        viewModelScope.launch {
            try {
                action()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                setEffect { SettingsEffect.ShowSnackbar(application.getString(failureMessage)) }
            } finally {
                setState { copy(isBackupBusy = false) }
            }
        }
    }
}
