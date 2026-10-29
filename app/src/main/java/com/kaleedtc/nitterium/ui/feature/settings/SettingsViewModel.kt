package com.kaleedtc.nitterium.ui.feature.settings

import android.app.Application
import com.kaleedtc.nitterium.data.frontend.FrontendType
import com.kaleedtc.nitterium.data.frontend.InstanceSettingsCookies
import com.kaleedtc.nitterium.data.repository.CookieInstanceOptionsStore
import androidx.lifecycle.viewModelScope
import com.kaleedtc.nitterium.R
import com.kaleedtc.nitterium.data.model.NitterInstanceSettings
import com.kaleedtc.nitterium.data.model.ReleaseNotesPreferences
import com.kaleedtc.nitterium.data.model.SocksProxySettings
import com.kaleedtc.nitterium.data.repository.UserPreferencesRepository
import com.kaleedtc.nitterium.data.repository.BackupRepository
import com.kaleedtc.nitterium.ui.common.MviViewModel
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
                preferencesRepository.feedAutoLoadBatches,
                preferencesRepository.feedSpreadAcrossInstances,
                preferencesRepository.mutedWords
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

                val allInstances = availableInstances + customInstances
                @Suppress("UNCHECKED_CAST")
                val instanceKeys = args[14] as Map<String, String>

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
                    instanceSettings = instanceSettings,
                    appVersion = _appVersion,
                    showReleaseNotesAfterUpdates = (args[12] as ReleaseNotesPreferences).enabled,
                    socksProxy = args[13] as SocksProxySettings,
                    instanceKey = instanceKeys[UserPreferencesRepository.hostOf(url)].orEmpty(),
                    feedUsersPerBatch = args[15] as Int,
                    feedAutoLoadBatches = args[16] as Boolean,
                    feedSpreadAcrossInstances = args[17] as Boolean,
                    mutedWords = @Suppress("UNCHECKED_CAST") (args[18] as List<String>)
                )
            }.collect { newState ->
                setState { newState.copy(isBackupBusy = isBackupBusy, backupToRestore = backupToRestore) }
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
                    // Optionally set it as current
                    preferencesRepository.setInstanceUrl(event.url)
                }
            }
            is SettingsEvent.RemoveCustomInstance -> {
                viewModelScope.launch {
                    preferencesRepository.removeCustomInstance(event.url)
                    // If the current URL is removed, fall back to the default instance.
                    if (state.value.instanceUrl == event.url) {
                        preferencesRepository.setInstanceUrl("https://xitter.cf")
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
                viewModelScope.launch {
                    preferencesRepository.setSocksProxy(event.settings)
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
            is SettingsEvent.UpdateFeedAutoLoadBatches -> {
                viewModelScope.launch {
                    preferencesRepository.setFeedAutoLoadBatches(event.enabled)
                }
            }
            is SettingsEvent.UpdateFeedSpreadAcrossInstances -> {
                viewModelScope.launch {
                    preferencesRepository.setFeedSpreadAcrossInstances(event.enabled)
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
