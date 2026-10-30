package com.kaleedtc.nitterium.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import com.kaleedtc.nitterium.data.frontend.FrontendAdapter
import com.kaleedtc.nitterium.data.frontend.FrontendType
import com.kaleedtc.nitterium.data.model.AppSettingsBackup
import com.kaleedtc.nitterium.data.model.ReleaseNotesPreferences
import com.kaleedtc.nitterium.data.model.SocksProxySettings
import com.kaleedtc.nitterium.data.model.SubscriptionSortOrder
import com.kaleedtc.nitterium.data.security.FeedFilterRules
import java.net.URI

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "user_preferences",
    produceMigrations = { listOf(ReleaseDefaultsMigration()) }
)

class UserPreferencesRepository(
    private val store: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.applicationContext.dataStore)

    internal object PreferencesKeys {
        val INSTANCE_URL = stringPreferencesKey("instance_url")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val TRUE_BLACK = booleanPreferencesKey("true_black")
        val ENABLE_SITE_HEADER = booleanPreferencesKey("enable_site_header")
        val SHOW_NAV_LABELS = booleanPreferencesKey("show_nav_labels")
        val DEFAULT_TAB = stringPreferencesKey("default_tab")
        val BLOCK_DIRECT_X = booleanPreferencesKey("block_direct_x")
        val CUSTOM_INSTANCES = androidx.datastore.preferences.core.stringSetPreferencesKey("custom_instances")
        val SHOW_RELEASE_NOTES = booleanPreferencesKey("show_release_notes")
        val LAST_SEEN_VERSION_CODE = longPreferencesKey("last_seen_version_code")
        val SUBSCRIPTION_SORT_ORDER = stringPreferencesKey("subscription_sort_order")
        val SOCKS_PROXY_ENABLED = booleanPreferencesKey("socks_proxy_enabled")
        val SOCKS_PROXY_HOST = stringPreferencesKey("socks_proxy_host")
        val SOCKS_PROXY_PORT = intPreferencesKey("socks_proxy_port")
        val SOCKS_PROXY_IMAGES = booleanPreferencesKey("socks_proxy_images")
        val INSTANCE_KEYS = stringPreferencesKey("instance_keys")
        val FEED_USERS_PER_BATCH = intPreferencesKey("feed_users_per_batch")
        val FEED_AUTO_LOAD_BATCHES = booleanPreferencesKey("feed_auto_load_batches")
        val FEED_SPREAD_ACROSS_INSTANCES = booleanPreferencesKey("feed_spread_across_instances")
        val MUTED_WORDS = stringPreferencesKey("muted_words")
    }

    val releaseNotesPreferences: Flow<ReleaseNotesPreferences> = store.data.map { preferences ->
        ReleaseNotesPreferences(
            enabled = preferences[PreferencesKeys.SHOW_RELEASE_NOTES] ?: true,
            lastSeenVersionCode = preferences[PreferencesKeys.LAST_SEEN_VERSION_CODE] ?: 0
        )
    }.distinctUntilChanged()

    suspend fun dismissReleaseNotes(versionCode: Long, hideFutureUpdates: Boolean) {
        store.edit { preferences ->
            preferences[PreferencesKeys.LAST_SEEN_VERSION_CODE] = maxOf(
                preferences[PreferencesKeys.LAST_SEEN_VERSION_CODE] ?: 0,
                versionCode
            )
            preferences[PreferencesKeys.SHOW_RELEASE_NOTES] = !hideFutureUpdates
        }
    }

    suspend fun setShowReleaseNotes(enabled: Boolean) {
        store.edit { it[PreferencesKeys.SHOW_RELEASE_NOTES] = enabled }
    }

    val instanceUrl: Flow<String> = store.data
        .map { preferences ->
            preferences[PreferencesKeys.INSTANCE_URL] ?: "https://xitter.cf"
        }.distinctUntilChanged()

    private fun frontendKey(url: String, detected: Boolean = false) = stringPreferencesKey(
        "${if (detected) "detected_frontend" else "frontend"}:${url.trimEnd('/')}"
    )

    val frontendMode: Flow<FrontendType> = store.data.map { preferences ->
        val url = preferences[PreferencesKeys.INSTANCE_URL] ?: "https://xitter.cf"
        runCatching { FrontendType.valueOf(preferences[frontendKey(url)] ?: "AUTO") }.getOrDefault(FrontendType.AUTO)
    }

    val frontend: Flow<FrontendType> = store.data.map { preferences ->
        val url = preferences[PreferencesKeys.INSTANCE_URL] ?: "https://xitter.cf"
        val mode = preferences[frontendKey(url)] ?: "AUTO"
        val type = if (mode == "AUTO") preferences[frontendKey(url, true)] ?: "NITTER" else mode
        runCatching { FrontendType.valueOf(type) }.getOrDefault(FrontendType.NITTER)
    }

    suspend fun setFrontend(type: FrontendType) {
        store.edit { preferences ->
            val url = preferences[PreferencesKeys.INSTANCE_URL] ?: "https://xitter.cf"
            preferences[frontendKey(url)] = type.name
        }
    }

    suspend fun recordDetectedFrontend(url: String, type: FrontendType) {
        store.edit { preferences ->
            val selected = preferences[PreferencesKeys.INSTANCE_URL] ?: "https://xitter.cf"
            if (com.kaleedtc.nitterium.data.frontend.FrontendAdapter.sameOrigin(selected, url)) {
                val key = frontendKey(selected, true)
                if (preferences[key] != type.name) preferences[key] = type.name
            }
        }
    }

    val dynamicColor: Flow<Boolean> = store.data
        .map { preferences ->
            preferences[PreferencesKeys.DYNAMIC_COLOR] ?: true
        }

    val trueBlack: Flow<Boolean> = store.data
        .map { preferences ->
            preferences[PreferencesKeys.TRUE_BLACK] ?: false
        }

    // null means system default, true = dark, false = light
    val darkTheme: Flow<Boolean?> = store.data
        .map { preferences ->
            preferences[PreferencesKeys.DARK_THEME]
        }

    val enableSiteHeader: Flow<Boolean> = store.data
        .map { preferences ->
            preferences[PreferencesKeys.ENABLE_SITE_HEADER] ?: false
        }

    val showNavLabels: Flow<Boolean> = store.data
        .map { preferences ->
            preferences[PreferencesKeys.SHOW_NAV_LABELS] ?: true
        }

    val defaultTab: Flow<String> = store.data
        .map { preferences ->
            preferences[PreferencesKeys.DEFAULT_TAB] ?: "Search"
        }

    val blockDirectX: Flow<Boolean> = store.data
        .map { preferences ->
            preferences[PreferencesKeys.BLOCK_DIRECT_X] ?: false
        }

    val customInstances: Flow<Set<String>> = store.data
        .map { preferences ->
            preferences[PreferencesKeys.CUSTOM_INSTANCES] ?: emptySet()
        }

    val subscriptionSortOrder: Flow<SubscriptionSortOrder> = store.data.map { preferences ->
        SubscriptionSortOrder.fromPreference(preferences[PreferencesKeys.SUBSCRIPTION_SORT_ORDER])
    }.distinctUntilChanged()

    val socksProxy: Flow<SocksProxySettings> = store.data.map { preferences ->
        SocksProxySettings(
            enabled = preferences[PreferencesKeys.SOCKS_PROXY_ENABLED] ?: false,
            host = preferences[PreferencesKeys.SOCKS_PROXY_HOST] ?: SocksProxySettings.DEFAULT_HOST,
            port = preferences[PreferencesKeys.SOCKS_PROXY_PORT] ?: SocksProxySettings.DEFAULT_PORT,
            proxyImages = preferences[PreferencesKeys.SOCKS_PROXY_IMAGES] ?: true
        )
    }.distinctUntilChanged()

    val feedUsersPerBatch: Flow<Int> = store.data.map { preferences ->
        FrontendAdapter.clampUsersPerBatch(
            preferences[PreferencesKeys.FEED_USERS_PER_BATCH]
                ?: FrontendAdapter.DEFAULT_MAX_USERS_PER_BATCH
        )
    }.distinctUntilChanged()

    val feedAutoLoadBatches: Flow<Boolean> = store.data.map { preferences ->
        preferences[PreferencesKeys.FEED_AUTO_LOAD_BATCHES] ?: true
    }.distinctUntilChanged()

    val feedSpreadAcrossInstances: Flow<Boolean> = store.data.map { preferences ->
        preferences[PreferencesKeys.FEED_SPREAD_ACROSS_INSTANCES] ?: false
    }.distinctUntilChanged()

    suspend fun setSubscriptionSortOrder(order: SubscriptionSortOrder) {
        store.edit { it[PreferencesKeys.SUBSCRIPTION_SORT_ORDER] = order.name }
    }

    suspend fun setFeedUsersPerBatch(size: Int) {
        store.edit {
            it[PreferencesKeys.FEED_USERS_PER_BATCH] = FrontendAdapter.clampUsersPerBatch(size)
        }
    }

    suspend fun setFeedAutoLoadBatches(enabled: Boolean) {
        store.edit { it[PreferencesKeys.FEED_AUTO_LOAD_BATCHES] = enabled }
    }

    suspend fun setFeedSpreadAcrossInstances(enabled: Boolean) {
        store.edit { it[PreferencesKeys.FEED_SPREAD_ACROSS_INSTANCES] = enabled }
    }

    val mutedWords: Flow<List<String>> = store.data.map { preferences ->
        readMutedWords(preferences[PreferencesKeys.MUTED_WORDS])
    }.distinctUntilChanged()

    suspend fun setMutedWords(words: Collection<String>) {
        val normalized = FeedFilterRules.normalizeMutedWords(words)
        store.edit { preferences ->
            preferences[PreferencesKeys.MUTED_WORDS] = Json.encodeToString(normalized)
        }
    }

    suspend fun addMutedWord(word: String) {
        val next = FeedFilterRules.normalizeMutedWords(mutedWords.first() + word)
        setMutedWords(next)
    }

    suspend fun removeMutedWord(word: String) {
        val needle = word.trim().lowercase()
        setMutedWords(mutedWords.first().filterNot { it.equals(needle, ignoreCase = true) })
    }

    suspend fun setSocksProxy(settings: SocksProxySettings) {
        require(settings.isValid() || !settings.enabled) { "Invalid SOCKS proxy settings" }
        store.edit { preferences ->
            preferences[PreferencesKeys.SOCKS_PROXY_ENABLED] = settings.enabled
            preferences[PreferencesKeys.SOCKS_PROXY_HOST] = settings.host.ifBlank {
                SocksProxySettings.DEFAULT_HOST
            }
            preferences[PreferencesKeys.SOCKS_PROXY_PORT] = settings.port
            preferences[PreferencesKeys.SOCKS_PROXY_IMAGES] = settings.proxyImages
        }
    }

    /**
     * Optional access keys per instance host, sent only to that host via the WebView
     * User-Agent (` Nitterium/<key>`). Used by self-hosted reverse proxies.
     */
    val instanceKeys: Flow<Map<String, String>> = store.data
        .map { preferences -> readInstanceKeys(preferences[PreferencesKeys.INSTANCE_KEYS]) }
        .distinctUntilChanged()

    fun instanceKeyFor(url: String): Flow<String> =
        instanceKeys.map { it[hostOf(url)].orEmpty() }

    suspend fun setInstanceKey(url: String, key: String) {
        val host = hostOf(url)
        if (host.isBlank()) return
        val sanitized = sanitizeInstanceKey(key)
        store.edit { preferences ->
            val map = readInstanceKeys(preferences[PreferencesKeys.INSTANCE_KEYS]).toMutableMap()
            if (sanitized.isEmpty()) map.remove(host) else map[host] = sanitized
            preferences[PreferencesKeys.INSTANCE_KEYS] = Json.encodeToString(map)
        }
    }

    companion object {
        private fun readMutedWords(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching { Json.decodeFromString<List<String>>(raw) }
                .getOrDefault(emptyList())
                .let(FeedFilterRules::normalizeMutedWords)
        }

        private fun readInstanceKeys(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            return runCatching { Json.decodeFromString<Map<String, String>>(raw) }
                .getOrDefault(emptyMap())
                .mapKeys { hostOf(it.key) }
                .mapValues { sanitizeInstanceKey(it.value) }
                .filterKeys { it.isNotBlank() }
                .filterValues { it.isNotEmpty() }
        }

        fun hostOf(url: String): String = runCatching {
            URI(url.trim()).host?.lowercase().orEmpty()
        }.getOrDefault("").ifBlank {
            url.trim().lowercase().substringBefore('/').substringBefore(':')
        }

        fun sanitizeInstanceKey(key: String): String =
            key.trim().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(128)

        fun withAccessKey(userAgent: String, key: String): String {
            val base = userAgent.substringBefore(" Nitterium/")
            val sanitized = sanitizeInstanceKey(key)
            return if (sanitized.isEmpty()) base else "$base Nitterium/$sanitized"
        }
    }

    suspend fun addCustomInstance(url: String) {
        store.edit { preferences ->
            val current = preferences[PreferencesKeys.CUSTOM_INSTANCES] ?: emptySet()
            preferences[PreferencesKeys.CUSTOM_INSTANCES] = current + url
        }
    }

    suspend fun removeCustomInstance(url: String) {
        store.edit { preferences ->
            val current = preferences[PreferencesKeys.CUSTOM_INSTANCES] ?: emptySet()
            preferences[PreferencesKeys.CUSTOM_INSTANCES] = current - url
        }
    }

    suspend fun setInstanceUrl(url: String) {
        store.edit { preferences ->
            preferences[PreferencesKeys.INSTANCE_URL] = url
        }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        store.edit { preferences ->
            preferences[PreferencesKeys.DYNAMIC_COLOR] = enabled
        }
    }

    suspend fun setTrueBlack(enabled: Boolean) {
        store.edit { preferences ->
            preferences[PreferencesKeys.TRUE_BLACK] = enabled
        }
    }

    suspend fun setDarkTheme(isDark: Boolean?) {
        store.edit { preferences ->
            if (isDark == null) {
                preferences.remove(PreferencesKeys.DARK_THEME)
            } else {
                preferences[PreferencesKeys.DARK_THEME] = isDark
            }
        }
    }

    suspend fun setEnableSiteHeader(enabled: Boolean) {
        store.edit { preferences ->
            preferences[PreferencesKeys.ENABLE_SITE_HEADER] = enabled
        }
    }

    suspend fun setShowNavLabels(enabled: Boolean) {
        store.edit { preferences ->
            preferences[PreferencesKeys.SHOW_NAV_LABELS] = enabled
        }
    }

    suspend fun setDefaultTab(tab: String) {
        store.edit { preferences ->
            preferences[PreferencesKeys.DEFAULT_TAB] = tab
        }
    }

    suspend fun setBlockDirectX(enabled: Boolean) {
        store.edit { preferences ->
            preferences[PreferencesKeys.BLOCK_DIRECT_X] = enabled
        }
    }

    suspend fun backupSettings(): AppSettingsBackup {
        val preferences = store.data.first()
        fun frontends(prefix: String) = preferences.asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith(prefix)) null
            else runCatching { key.name.removePrefix(prefix) to FrontendType.valueOf(value as String) }.getOrNull()
        }.toMap()
        return AppSettingsBackup(
            instanceUrl = preferences[PreferencesKeys.INSTANCE_URL] ?: "https://xitter.cf",
            dynamicColor = preferences[PreferencesKeys.DYNAMIC_COLOR] ?: true,
            darkTheme = preferences[PreferencesKeys.DARK_THEME],
            trueBlack = preferences[PreferencesKeys.TRUE_BLACK] ?: false,
            siteHeader = preferences[PreferencesKeys.ENABLE_SITE_HEADER] ?: false,
            navLabels = preferences[PreferencesKeys.SHOW_NAV_LABELS] ?: true,
            defaultTab = preferences[PreferencesKeys.DEFAULT_TAB] ?: "Search",
            blockDirectX = preferences[PreferencesKeys.BLOCK_DIRECT_X] ?: false,
            customInstances = preferences[PreferencesKeys.CUSTOM_INSTANCES] ?: emptySet(),
            frontendModes = frontends("frontend:"),
            detectedFrontends = frontends("detected_frontend:"),
            showReleaseNotes = preferences[PreferencesKeys.SHOW_RELEASE_NOTES] ?: true,
            subscriptionSortOrder = SubscriptionSortOrder.fromPreference(
                preferences[PreferencesKeys.SUBSCRIPTION_SORT_ORDER]
            ),
            socksProxyEnabled = preferences[PreferencesKeys.SOCKS_PROXY_ENABLED] ?: false,
            socksProxyHost = preferences[PreferencesKeys.SOCKS_PROXY_HOST]
                ?: SocksProxySettings.DEFAULT_HOST,
            socksProxyPort = preferences[PreferencesKeys.SOCKS_PROXY_PORT]
                ?: SocksProxySettings.DEFAULT_PORT,
            socksProxyImages = preferences[PreferencesKeys.SOCKS_PROXY_IMAGES] ?: true,
            instanceKeys = readInstanceKeys(preferences[PreferencesKeys.INSTANCE_KEYS]),
            feedUsersPerBatch = preferences[PreferencesKeys.FEED_USERS_PER_BATCH]
                ?: FrontendAdapter.DEFAULT_MAX_USERS_PER_BATCH,
            feedAutoLoadBatches = preferences[PreferencesKeys.FEED_AUTO_LOAD_BATCHES] ?: true,
            feedSpreadAcrossInstances = preferences[PreferencesKeys.FEED_SPREAD_ACROSS_INSTANCES] ?: false,
            mutedWords = readMutedWords(preferences[PreferencesKeys.MUTED_WORDS])
        )
    }

    suspend fun restoreSettings(settings: AppSettingsBackup) {
        store.edit { preferences ->
            preferences[PreferencesKeys.INSTANCE_URL] = settings.instanceUrl
            preferences[PreferencesKeys.DYNAMIC_COLOR] = settings.dynamicColor
            if (settings.darkTheme == null) preferences.remove(PreferencesKeys.DARK_THEME)
            else preferences[PreferencesKeys.DARK_THEME] = settings.darkTheme
            preferences[PreferencesKeys.TRUE_BLACK] = settings.trueBlack
            preferences[PreferencesKeys.ENABLE_SITE_HEADER] = settings.siteHeader
            preferences[PreferencesKeys.SHOW_NAV_LABELS] = settings.navLabels
            preferences[PreferencesKeys.DEFAULT_TAB] = settings.defaultTab
            preferences[PreferencesKeys.BLOCK_DIRECT_X] = settings.blockDirectX
            preferences[PreferencesKeys.CUSTOM_INSTANCES] = settings.customInstances
            preferences[PreferencesKeys.SHOW_RELEASE_NOTES] = settings.showReleaseNotes
            preferences[PreferencesKeys.SUBSCRIPTION_SORT_ORDER] = settings.subscriptionSortOrder.name
            preferences[PreferencesKeys.SOCKS_PROXY_ENABLED] = settings.socksProxyEnabled
            preferences[PreferencesKeys.SOCKS_PROXY_HOST] = settings.socksProxyHost
            preferences[PreferencesKeys.SOCKS_PROXY_PORT] = settings.socksProxyPort
            preferences[PreferencesKeys.SOCKS_PROXY_IMAGES] = settings.socksProxyImages
            preferences[PreferencesKeys.FEED_USERS_PER_BATCH] =
                FrontendAdapter.clampUsersPerBatch(settings.feedUsersPerBatch)
            preferences[PreferencesKeys.FEED_AUTO_LOAD_BATCHES] = settings.feedAutoLoadBatches
            preferences[PreferencesKeys.FEED_SPREAD_ACROSS_INSTANCES] = settings.feedSpreadAcrossInstances
            preferences[PreferencesKeys.MUTED_WORDS] = Json.encodeToString(
                FeedFilterRules.normalizeMutedWords(settings.mutedWords)
            )
            preferences[PreferencesKeys.INSTANCE_KEYS] = Json.encodeToString(
                settings.instanceKeys.mapKeys { hostOf(it.key) }
                    .mapValues { sanitizeInstanceKey(it.value) }
                    .filterKeys { it.isNotBlank() }
                    .filterValues { it.isNotEmpty() }
            )
            preferences.asMap().keys.filter {
                it.name.startsWith("frontend:") || it.name.startsWith("detected_frontend:")
            }.forEach { preferences.remove(stringPreferencesKey(it.name)) }
            settings.frontendModes.forEach { (url, type) -> preferences[frontendKey(url)] = type.name }
            settings.detectedFrontends.forEach { (url, type) -> preferences[frontendKey(url, true)] = type.name }
        }
    }
}
