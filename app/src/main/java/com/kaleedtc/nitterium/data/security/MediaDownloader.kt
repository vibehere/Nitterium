package com.kaleedtc.nitterium.data.security

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Saves Nitter "Download video" links. WebView ignores `download` anchors, and the
 * system DownloadManager would bypass the app's SOCKS proxy and instance cookies.
 */
object MediaDownloader {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(WebViewCookieJar)
            .proxySelector(FeedChunkFetcher.feedProxySelector())
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val unsafeFileChars = Regex("[^A-Za-z0-9._-]")

    /** Name from the proxied X URL's last segment so repeat saves of a clip are recognisable. */
    fun videoFileName(url: String, fallbackId: Long): String {
        val decoded = runCatching { URLDecoder.decode(url, StandardCharsets.UTF_8.name()) }
            .getOrDefault(url)
        val segment = decoded.substringBefore('?').substringAfterLast('/')
            .replace(unsafeFileChars, "_")
            .trim('_', '.')
        val base = segment.removeSuffix(".mp4").takeIf { it.isNotBlank() } ?: "video_$fallbackId"
        return "Nitterium_$base.mp4"
    }

    suspend fun saveVideo(
        context: Context,
        url: String,
        userAgent: String,
        fileName: String
    ): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept-Language", PrivacyHeaders.ACCEPT_LANGUAGE)
            .get()
            .build()
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Nitterium")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext false
        val saved = runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) return@use false
                resolver.openOutputStream(target)?.use { out -> body.byteStream().copyTo(out) } != null
            }
        }.getOrDefault(false)
        if (saved) {
            resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } else {
            resolver.delete(target, null, null)
        }
        saved
    }
}
