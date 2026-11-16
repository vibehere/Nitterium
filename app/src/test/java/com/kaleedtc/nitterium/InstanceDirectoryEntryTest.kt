package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.model.InstanceDirectoryEntry
import com.kaleedtc.nitterium.data.model.InstancePoolPreset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstanceDirectoryEntryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun catalogRoundTripsWithProbeFields() {
        val entry = InstanceDirectoryEntry(
            url = "https://nitter.example",
            version = "2026.10.20",
            rssReported = true,
            worksHtml = true,
            worksRss = true,
            worksWithoutJs = true,
            ddosProtection = "Cloudflare"
        )
        val encoded = json.encodeToString(listOf(entry))
        val decoded = json.decodeFromString<List<InstanceDirectoryEntry>>(encoded)
        assertEquals(1, decoded.size)
        assertEquals("https://nitter.example", decoded[0].normalizedUrl())
        assertEquals("2026.10.20", decoded[0].version)
        assertTrue(decoded[0].worksWithoutJs == true)
        assertEquals("Cloudflare", decoded[0].ddosProtection)
    }

    @Test
    fun poolPresetRoundTrips() {
        val preset = InstancePoolPreset(
            id = "p1",
            name = "Tor set",
            urls = listOf("https://a.example", "http://b.onion")
        )
        val encoded = json.encodeToString(listOf(preset))
        val decoded = json.decodeFromString<List<InstancePoolPreset>>(encoded)
        assertEquals("Tor set", decoded.single().name)
        assertEquals(2, decoded.single().urls.size)
    }
}
