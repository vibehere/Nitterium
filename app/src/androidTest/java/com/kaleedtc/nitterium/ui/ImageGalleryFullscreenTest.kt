package com.kaleedtc.nitterium.ui

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.kaleedtc.nitterium.ui.common.LocalFullScreenMode
import com.kaleedtc.nitterium.ui.common.NitterWebView
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ImageGalleryFullscreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openingTheGalleryEntersFullscreenAndLeavingRestoresChrome() {
        val origin = "https://gallery-fullscreen.test"
        val fullScreen = mutableStateOf(false)
        val webView = AtomicReference<WebView?>(null)
        val finished = AtomicReference(false)

        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalFullScreenMode provides fullScreen) {
                    NitterWebView(
                        url = "$origin/alice",
                        selectedInstanceUrl = origin,
                        isTrueBlack = false,
                        isSiteHeaderEnabled = false,
                        isBlockDirectXEnabled = false,
                        darkTheme = false,
                        onPageStarted = {},
                        onPageFinished = { _, _ -> finished.set(true) },
                        onPageError = { _, _ -> },
                        onWebViewCreated = { view ->
                            webView.set(view)
                            val delegate = view.webViewClient
                            view.webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    if (request?.url?.host != "gallery-fullscreen.test") {
                                        return delegate.shouldInterceptRequest(view, request)
                                    }
                                    val html = "<html><body><div class='timeline'>ok</div></body></html>"
                                    return WebResourceResponse(
                                        "text/html", "UTF-8", 200, "OK", emptyMap(),
                                        ByteArrayInputStream(html.toByteArray())
                                    )
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) =
                                    delegate.onPageStarted(view, url, favicon)

                                override fun onPageFinished(view: WebView?, url: String?) =
                                    delegate.onPageFinished(view, url)
                            }
                        }
                    )
                }
            }
        }

        compose.waitUntil(15_000) { finished.get() && webView.get() != null }
        assertFalse(fullScreen.value)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.get()!!.evaluateJavascript(
                """NitterAndroid.openGallery(JSON.stringify(["https://pbs.twimg.com/media/test.jpg:orig"]), 0);""",
                null
            )
        }
        compose.waitUntil(5_000) { fullScreen.value }
        assertTrue(fullScreen.value)
        compose.onNodeWithContentDescription("Close").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { !fullScreen.value }
        assertFalse(fullScreen.value)
    }
}
