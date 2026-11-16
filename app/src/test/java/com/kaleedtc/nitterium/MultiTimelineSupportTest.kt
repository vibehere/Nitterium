package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.security.MultiTimelineSupport
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiTimelineSupportTest {
    @After fun reset() = MultiTimelineSupport.clear()

    @Test fun recognisesSearchDisabledPage() {
        assertTrue(
            MultiTimelineSupport.looksDisabled(
                """<div class="error-panel"><span>Search disabled</span></div>"""
            )
        )
        assertFalse(MultiTimelineSupport.looksDisabled("<div class=\"error-panel\">Rate limited</div>"))
    }

    @Test fun hostIsSetAsideUntilTheWindowPasses() {
        MultiTimelineSupport.setAside("https://Nitter.Example/", nowMs = 1_000)
        assertTrue(MultiTimelineSupport.isSetAside("https://nitter.example", nowMs = 2_000))
        assertFalse(MultiTimelineSupport.isSetAside("https://other.example", nowMs = 2_000))
        assertFalse(
            MultiTimelineSupport.isSetAside(
                "https://nitter.example",
                nowMs = 1_000 + MultiTimelineSupport.SET_ASIDE_MS
            )
        )
    }
}
