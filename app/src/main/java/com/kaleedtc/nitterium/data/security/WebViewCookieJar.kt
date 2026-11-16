package com.kaleedtc.nitterium.data.security

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Shares the WebView cookie store with OkHttp so a bot check passed in the browser
 * (Anubis, Gandalf, Cloudflare) also unlocks feed fetches, including across the
 * redirects some Nitters use to reach their protected host.
 */
object WebViewCookieJar : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = runCatching { CookieManager.getInstance().getCookie(url.toString()) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return parseCookieHeader(url, header)
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        for (cookie in cookies) {
            manager.setCookie(url.toString(), cookie.toString())
        }
    }

    fun parseCookieHeader(url: HttpUrl, header: String): List<Cookie> =
        header.split(';').mapNotNull { pair ->
            pair.trim().takeIf { it.isNotEmpty() }?.let { Cookie.parse(url, it) }
        }
}
