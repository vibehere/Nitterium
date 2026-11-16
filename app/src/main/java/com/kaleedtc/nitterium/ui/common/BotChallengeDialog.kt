package com.kaleedtc.nitterium.ui.common

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kaleedtc.nitterium.R
import com.kaleedtc.nitterium.data.repository.UserPreferencesRepository
import com.kaleedtc.nitterium.data.security.PrivacyHeaders
import com.kaleedtc.nitterium.data.security.UrlPolicy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shows a Nitter's bot check (Anubis, Gandalf, Cloudflare) in a real browser so the
 * user can pass it. Cookies land in the shared WebView store, which OkHttp feed
 * fetches read, so the blocked accounts can load afterwards.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BotChallengeDialog(
    host: String,
    url: String,
    onPassed: () -> Unit,
    onUnsupported: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember { UserPreferencesRepository(context) }
    val instanceKeys by preferences.instanceKeys.collectAsState(initial = emptyMap())
    val instanceKey = instanceKeys[UserPreferencesRepository.hostOf(host)].orEmpty()
    val latestOnPassed by rememberUpdatedState(onPassed)
    val latestOnUnsupported by rememberUpdatedState(onUnsupported)
    val reported = remember(url) { AtomicBoolean(false) }

    fun report(outcome: () -> Unit) {
        if (!reported.compareAndSet(false, true)) return
        runCatching { CookieManager.getInstance().flush() }
        outcome()
    }

    fun reportPassed() = report { latestOnPassed() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.feed_challenge_title, UrlPolicy.displayHost(host)),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.feed_challenge_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AndroidView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.setSupportMultipleWindows(false)
                            settings.userAgentString = UserPreferencesRepository.withAccessKey(
                                UserPreferencesRepository.browserUserAgent(settings.userAgentString),
                                instanceKey
                            )
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): Boolean = UrlPolicy.instanceUrl(request.url.toString()) == null

                                override fun onPageFinished(view: WebView, pageUrl: String?) {
                                    view.evaluateJavascript(PAGE_OUTCOME_SCRIPT) { outcome ->
                                        when (outcome) {
                                            "\"timeline\"" -> reportPassed()
                                            "\"unsupported\"" -> report { latestOnUnsupported() }
                                        }
                                    }
                                }
                            }
                            loadUrl(url, PrivacyHeaders.navigationHeaders())
                        }
                    },
                    onRelease = { webView ->
                        webView.stopLoading()
                        webView.destroy()
                    }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onSkip) {
                        Text(stringResource(R.string.feed_challenge_skip))
                    }
                    TextButton(onClick = ::reportPassed) {
                        Text(stringResource(R.string.feed_challenge_done))
                    }
                }
            }
        }
    }
}

/**
 * A Nitter page behind the check is either a timeline or Nitter's own refusal
 * (multi-account timelines disabled, or an error) that no check can fix.
 */
private const val PAGE_OUTCOME_SCRIPT = """
    (function() {
        if (document.querySelector('.timeline-item, .profile-card, .tweet-content')) return 'timeline';
        if (document.querySelector('.disabled-feature, .error-panel')) return 'unsupported';
        return '';
    })()
"""
