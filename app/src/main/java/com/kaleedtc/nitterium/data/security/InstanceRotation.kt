package com.kaleedtc.nitterium.data.security

import com.kaleedtc.nitterium.data.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.first
import kotlin.random.Random

/**
 * Picks a host for one-shot page loads when Feed All “spread” (private rotation)
 * is enabled — profiles/search rotate across the active pool without rewriting
 * the user’s preferred Settings instance.
 */
object InstanceRotation {
    suspend fun pickHost(
        preferences: UserPreferencesRepository,
        knownInstances: Collection<String> = emptyList(),
        seed: Long = System.nanoTime()
    ): String {
        val spread = preferences.feedSpreadAcrossInstances.first()
        val preferred = preferences.instanceUrl.first().trim().trimEnd('/')
        if (!spread) return preferred

        val socks = preferences.socksProxy.first()
        val preferOnion = preferences.socksPreferOnionInstances.first()
        val onionOnly = preferOnion &&
            (socks.enabled || TorOnionPreference.isOnionInstance(preferred))
        val disabled = preferences.disabledInstanceUrls.first()
            .map { it.trimEnd('/').lowercase() }.toSet()
        val presets = preferences.instancePoolPresets.first()
        val activeId = preferences.activeInstancePoolPresetId.first()
        val presetUrls = presets.firstOrNull { it.id == activeId }?.urls.orEmpty()
        val discovered = preferences.discoveredInstances.first()
        val custom = preferences.customInstances.first()

        val base = if (presetUrls.isNotEmpty()) {
            presetUrls
        } else {
            knownInstances + discovered + custom + preferred
        }
        val pool = base
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            // Keep onions for system Tor VPN (SOCKS off).
            .filter { url ->
                val key = url.lowercase()
                key !in disabled || key == preferred.lowercase()
            }
            .distinctBy { it.lowercase() }
            .let { list ->
                if (onionOnly) TorOnionPreference.restrictPool(list, onionOnly = true) else list
            }
            .ifEmpty { listOfNotNull(preferred.takeIf { it.isNotBlank() }) }

        val ranked = FeedHostScoreboard.rank(pool)
        if (ranked.isEmpty()) return preferred
        val index = Random(seed).nextInt(ranked.size)
        return ranked[index]
    }
}
