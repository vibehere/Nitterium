package com.kaleedtc.nitterium

import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebSettings
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.kaleedtc.nitterium.data.ConnectivityMonitor
import com.kaleedtc.nitterium.data.ConnectivityMonitorImpl
import com.kaleedtc.nitterium.data.repository.BackupRepository
import com.kaleedtc.nitterium.data.repository.FeedGroupRepository
import com.kaleedtc.nitterium.data.repository.SubscriptionRepository
import com.kaleedtc.nitterium.data.repository.UserPreferencesRepository
import com.kaleedtc.nitterium.data.security.FeedTimelineCache
import com.kaleedtc.nitterium.data.security.ImageRequestInterceptor
import com.kaleedtc.nitterium.data.security.SocksProxyController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath

class NitteriumApplication : Application(), SingletonImageLoader.Factory {

    lateinit var subscriptionRepository: SubscriptionRepository
    lateinit var userPreferencesRepository: UserPreferencesRepository
    lateinit var feedGroupRepository: FeedGroupRepository
    lateinit var connectivityMonitor: ConnectivityMonitor
    lateinit var backupRepository: BackupRepository
    lateinit var feedTimelineCache: FeedTimelineCache

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        subscriptionRepository = SubscriptionRepository(this)
        userPreferencesRepository = UserPreferencesRepository(this)
        feedGroupRepository = FeedGroupRepository(this)
        backupRepository = BackupRepository(
            this, subscriptionRepository, feedGroupRepository, userPreferencesRepository
        )
        connectivityMonitor = ConnectivityMonitorImpl(this)
        feedTimelineCache = FeedTimelineCache(this)

        applicationScope.launch {
            userPreferencesRepository.socksProxy.collect { settings ->
                SocksProxyController.apply(this@NitteriumApplication, settings)
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val cookieManager = CookieManager.getInstance()
        val strippedUserAgent =
            UserPreferencesRepository.browserUserAgent(WebSettings.getDefaultUserAgent(context))

        val okHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .proxySelector(SocksProxyController.okHttpProxySelector())
            .addInterceptor(ImageRequestInterceptor(
                blockDirectX = { runBlocking { userPreferencesRepository.blockDirectX.first() } },
                cookiesForUrl = cookieManager::getCookie,
                userAgent = strippedUserAgent
            ))
            .build()

        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").absolutePath.toPath())
                    .maxSizePercent(0.02)
                    .build()
            }
            .components {
                add(OkHttpNetworkFetcherFactory(okHttpClient))
            }
            .build()
    }
}
