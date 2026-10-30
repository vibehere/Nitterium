package com.kaleedtc.nitterium.data.security

object PrivacyHeaders {
    const val ACCEPT_LANGUAGE = "en-US,en;q=0.9"
    const val NAVIGATOR_LANGUAGE = "en-US"

    fun navigationHeaders(): Map<String, String> = mapOf(
        "Accept-Language" to ACCEPT_LANGUAGE
    )

    fun navigatorLocaleScript(): String = """
        (function() {
            try {
                Object.defineProperty(Navigator.prototype, 'language', {
                    get: function() { return '$NAVIGATOR_LANGUAGE'; },
                    configurable: true
                });
                Object.defineProperty(Navigator.prototype, 'languages', {
                    get: function() { return Object.freeze(['$NAVIGATOR_LANGUAGE', 'en']); },
                    configurable: true
                });
            } catch (e) {}
        })();
    """.trimIndent()
}
