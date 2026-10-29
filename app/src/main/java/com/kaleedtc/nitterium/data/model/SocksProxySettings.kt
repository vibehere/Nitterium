package com.kaleedtc.nitterium.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SocksProxySettings(
    val enabled: Boolean = false,
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val proxyImages: Boolean = true
) {
    fun isValid(): Boolean =
        host.isNotBlank() &&
            !host.any { it.isISOControl() || it.isWhitespace() } &&
            port in 1..65535

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 9050
    }
}
