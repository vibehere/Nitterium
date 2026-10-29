package com.kaleedtc.nitterium.data.repository

import android.webkit.CookieManager
import com.kaleedtc.nitterium.data.frontend.FrontendAdapter
import com.kaleedtc.nitterium.data.frontend.FrontendType
import com.kaleedtc.nitterium.data.frontend.InstanceSettingsCookies
import com.kaleedtc.nitterium.data.model.NitterInstanceSettings

interface InstanceOptionsStore {
    fun read(url: String, frontend: FrontendType): NitterInstanceSettings
    fun write(url: String, frontend: FrontendType, settings: NitterInstanceSettings)
}

/** Supported instance-option cookies, including legacy backup fields. */
class CookieInstanceOptionsStore : InstanceOptionsStore {
    override fun read(url: String, frontend: FrontendType): NitterInstanceSettings =
        InstanceSettingsCookies.read(CookieManager.getInstance().getCookie(url).orEmpty(), frontend)

    fun ensureDefaults(url: String, frontend: FrontendType) {
        writeCookies(url, InstanceSettingsCookies.missingDefaults(CookieManager.getInstance().getCookie(url).orEmpty(), frontend))
    }

    fun ensureProxyDefaults(url: String) {
        writeCookies(url, InstanceSettingsCookies.missingProxyDefaults(CookieManager.getInstance().getCookie(url).orEmpty()))
    }

    /**
     * Like browser Nitter: when the client blocks direct X/CDN hosts, the instance must
     * emit proxied /pic and video URLs. Force both frontend proxy cookies on.
     */
    fun ensureMediaProxyEnabled(url: String) {
        writeCookies(
            url,
            mapOf(
                FrontendAdapter.Nitter.proxyCookie to true,
                FrontendAdapter.Teapot.proxyCookie to true
            )
        )
    }

    fun setOption(url: String, frontend: FrontendType, key: String, value: Boolean): Boolean {
        val cookie = InstanceSettingsCookies.cookieKey(key, frontend) ?: return false
        writeCookies(url, mapOf(cookie to value))
        return true
    }

    /** Expire all cookies for [url], then re-seed playback/proxy defaults for [frontend]. */
    fun clearAndReset(url: String, frontend: FrontendType): NitterInstanceSettings {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        val existing = manager.getCookie(url).orEmpty()
        existing.split(';').mapNotNull { part ->
            part.trim().substringBefore('=').takeIf { it.isNotEmpty() }
        }.distinct().forEach { name ->
            manager.setCookie(url, "$name=; Max-Age=0; Path=/; SameSite=Lax")
        }
        manager.flush()
        ensureDefaults(url, frontend)
        ensureProxyDefaults(url)
        return read(url, frontend)
    }

    override fun write(url: String, frontend: FrontendType, settings: NitterInstanceSettings) {
        writeCookies(url, InstanceSettingsCookies.values(settings, frontend))
    }

    private fun writeCookies(url: String, values: Map<String, Boolean>) {
        if (values.isEmpty()) return
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        values.forEach { (key, value) ->
            manager.setCookie(url, cookieValue(key, value))
        }
        manager.flush()
    }

    private fun cookieValue(key: String, value: Boolean) =
        "$key=${if (value) "on" else "off"}; Path=/; SameSite=Lax"
}
