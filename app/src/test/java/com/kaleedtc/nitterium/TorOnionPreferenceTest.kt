package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.security.TorOnionPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorOnionPreferenceTest {
    private val onion =
        "http://nitter.lesboswza6waq3itqo46rvf5a7dibxih32f2umwg6fpssxj66spmclyd.onion"
    private val onion2 =
        "http://dhgjnhkxtgesvcddfwzqm4d4zmyz2x2jud6huweu5iywuvoo7qaqpjid.onion"

    @Test
    fun onionsAreReachableOnlyThroughSocksOrAnOnionSelection() {
        assertFalse(TorOnionPreference.onionReachable(false, "https://nitter.example"))
        assertTrue(TorOnionPreference.onionReachable(true, "https://nitter.example"))
        assertTrue(TorOnionPreference.onionReachable(false, onion))
    }

    @Test
    fun detectsOnionInstancesAndPicksFirstFromMixedList() {
        assertTrue(TorOnionPreference.isOnionInstance(onion))
        assertFalse(TorOnionPreference.isOnionInstance("https://nitter.example"))
        assertEquals(
            onion,
            TorOnionPreference.firstOnion(
                listOf("https://nitter.example", onion, onion2)
            )
        )
        assertNull(TorOnionPreference.firstOnion(listOf("https://nitter.example")))
        assertEquals(
            listOf(onion, onion2),
            TorOnionPreference.onionsOnly(listOf("https://a.example", onion, onion2, onion + "/"))
        )
    }

    @Test
    fun restrictPoolDropsClearnetWhenOnionOnly() {
        val mixed = listOf("https://nitter.example", onion, onion2)
        assertEquals(
            listOf(onion, onion2),
            TorOnionPreference.restrictPool(mixed, onionOnly = true)
        )
        assertEquals(
            listOf("https://nitter.example", onion, onion2),
            TorOnionPreference.restrictPool(mixed, onionOnly = false)
        )
        assertTrue(TorOnionPreference.restrictPool(listOf("https://only.clearnet"), true).isEmpty())
    }

    @Test
    fun resolveOnionInstanceNeverReturnsClearnet() {
        assertEquals(
            onion,
            TorOnionPreference.resolveOnionInstance(
                preferred = "https://shitter.thepixora.com",
                pool = listOf("https://shitter.thepixora.com", onion, onion2)
            )
        )
        assertEquals(
            onion2,
            TorOnionPreference.resolveOnionInstance(
                preferred = onion2,
                pool = listOf(onion, onion2)
            )
        )
        assertNull(
            TorOnionPreference.resolveOnionInstance(
                preferred = "https://nitter.example",
                pool = listOf("https://nitter.example")
            )
        )
    }
}
