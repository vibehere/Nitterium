package com.kaleedtc.nitterium.data.model

import kotlinx.serialization.Serializable

/** Catalog row for a Nitter/Teapot host (status APIs + local probes). */
@Serializable
data class InstanceDirectoryEntry(
    val url: String,
    val version: String? = null,
    val rssReported: Boolean? = null,
    val healthyReported: Boolean = true,
    val connectivity: String? = null,
    val country: String? = null,
    val points: Int? = null,
    val worksHtml: Boolean? = null,
    val worksRss: Boolean? = null,
    val worksWithoutJs: Boolean? = null,
    /** Detected edge protection, e.g. Cloudflare, or null if unknown. */
    val ddosProtection: String? = null,
    val lastProbedEpochMs: Long? = null
) {
    fun normalizedUrl(): String = url.trim().trimEnd('/')
}

@Serializable
data class InstancePoolPreset(
    val id: String,
    val name: String,
    val urls: List<String> = emptyList()
)
