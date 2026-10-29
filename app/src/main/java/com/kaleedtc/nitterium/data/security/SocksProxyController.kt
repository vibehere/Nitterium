package com.kaleedtc.nitterium.data.security

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.kaleedtc.nitterium.data.model.SocksProxySettings
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/** Applies SOCKS settings to WebView (ProxyController) and native HTTP (ProxySelector). */
object SocksProxyController {
    private const val TAG = "SocksProxyController"
    private val settingsRef = AtomicReference(SocksProxySettings())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command ->
        if (Looper.myLooper() == Looper.getMainLooper()) {
            command.run()
        } else {
            mainHandler.post(command)
        }
    }

    fun current(): SocksProxySettings = settingsRef.get()

    fun apply(@Suppress("UNUSED_PARAMETER") context: Context, settings: SocksProxySettings) {
        val normalized = if (settings.enabled && settings.isValid()) settings
        else settings.copy(enabled = false)
        settingsRef.set(normalized)
        applyWebViewProxy(normalized)
    }

    fun okHttpProxySelector(): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val settings = settingsRef.get()
            if (!settings.enabled || !settings.proxyImages || !settings.isValid() || uri == null) {
                return listOf(Proxy.NO_PROXY)
            }
            val address = InetSocketAddress.createUnresolved(settings.host, settings.port)
            return listOf(Proxy(Proxy.Type.SOCKS, address))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {
            Log.w(TAG, "SOCKS connect failed for $uri via $sa: $ioe")
        }
    }

    private fun applyWebViewProxy(settings: SocksProxySettings) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Log.w(TAG, "WebView PROXY_OVERRIDE is unavailable on this device")
            return
        }
        val controller = ProxyController.getInstance()
        if (!settings.enabled || !settings.isValid()) {
            controller.clearProxyOverride(mainExecutor) {}
            return
        }
        val config = ProxyConfig.Builder()
            .addProxyRule("socks://${settings.host}:${settings.port}")
            .addBypassRule("<-loopback>")
            .build()
        controller.setProxyOverride(config, mainExecutor) {}
    }
}
