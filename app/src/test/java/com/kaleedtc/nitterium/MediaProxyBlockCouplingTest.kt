package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.frontend.FrontendAdapter
import com.kaleedtc.nitterium.data.frontend.FrontendType
import com.kaleedtc.nitterium.data.frontend.InstanceSettingsCookies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProxyBlockCouplingTest {
    @Test fun bothFrontendsExposeAProxyCookieForMedia() {
        assertEquals("proxyVideos", FrontendAdapter.Nitter.proxyCookie)
        assertEquals("proxyMedia", FrontendAdapter.Teapot.proxyCookie)
        assertEquals("proxyVideos", InstanceSettingsCookies.cookieKey("proxyVideos", FrontendType.NITTER))
        assertEquals("proxyMedia", InstanceSettingsCookies.cookieKey("proxyVideos", FrontendType.TEAPOT))
    }

    @Test fun enablingProxyUpdatesSettingsModel() {
        val off = InstanceSettingsCookies.read("proxyVideos=off", FrontendType.NITTER)
        assertTrue(!off.proxyVideos)
        val on = InstanceSettingsCookies.update(off, "proxyVideos", true)
        assertTrue(on!!.proxyVideos)
        assertEquals(true, InstanceSettingsCookies.values(on, FrontendType.NITTER)["proxyVideos"])
    }
}
