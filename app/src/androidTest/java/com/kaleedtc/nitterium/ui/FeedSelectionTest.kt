package com.kaleedtc.nitterium.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.kaleedtc.nitterium.data.ConnectivityMonitor
import com.kaleedtc.nitterium.data.model.Subscription
import com.kaleedtc.nitterium.data.repository.FeedGroupRepository
import com.kaleedtc.nitterium.data.repository.SubscriptionRepository
import com.kaleedtc.nitterium.data.repository.UserPreferencesRepository
import com.kaleedtc.nitterium.ui.feature.feed.FeedEvent
import com.kaleedtc.nitterium.ui.feature.feed.FeedViewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedSelectionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val directory = File(target.cacheDir, "feed-selection-${UUID.randomUUID()}").apply { mkdirs() }
    private val store = ViewModelStore()
    private val context = object : ContextWrapper(target) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
    }

    @After fun cleanUp() {
        instrumentation.runOnMainSync { store.clear() }
        directory.deleteRecursively()
    }

    @Test fun selectingAGroupRebuildsTheUrlAfterSubscriptionsHaveAlreadyLoaded() = runBlocking {
        val repository = SubscriptionRepository(context)
        repository.subscriptions.first()
        repository.addSubscription(Subscription("alice", "https://example.com", groupIds = listOf("news")))
        repository.addSubscription(Subscription("bob", "https://example.com", groupIds = listOf("tech")))
        lateinit var viewModel: FeedViewModel
        instrumentation.runOnMainSync {
            viewModel = FeedViewModel(
                UserPreferencesRepository(target),
                repository,
                object : ConnectivityMonitor {
                    override val isConnected = MutableStateFlow(true)
                },
                FeedGroupRepository(context)
            )
            store.put("feed", viewModel)
        }
        await { viewModel.state.value.currentUrl.endsWith("/alice,bob") }
        instrumentation.runOnMainSync { viewModel.setGroup("news", "News") }
        await { viewModel.state.value.currentUrl.endsWith("/alice") }
        assertEquals("news", viewModel.state.value.groupId)
        instrumentation.runOnMainSync { viewModel.setGroup("tech", "Tech") }
        await { viewModel.state.value.currentUrl.endsWith("/bob") }
        instrumentation.runOnMainSync { viewModel.setGroup("empty", "Empty") }
        await { !viewModel.state.value.hasSubscriptions }
        assertEquals("", viewModel.state.value.currentUrl)
    }

    @Test fun openingAProfileFromTheFeedExposesSubscribeState() = runBlocking {
        val repository = SubscriptionRepository(context)
        repository.subscriptions.first()
        repository.addSubscription(Subscription("alice", "https://example.com"))
        lateinit var viewModel: FeedViewModel
        instrumentation.runOnMainSync {
            viewModel = FeedViewModel(
                UserPreferencesRepository(target),
                repository,
                object : ConnectivityMonitor {
                    override val isConnected = MutableStateFlow(true)
                },
                FeedGroupRepository(context)
            )
            store.put("feed-profile", viewModel)
        }
        await { viewModel.state.value.currentUrl.endsWith("/alice") }
        val instance = viewModel.state.value.selectedInstanceUrl.trimEnd('/')
        instrumentation.runOnMainSync {
            viewModel.onEvent(FeedEvent.OnPageFinished("$instance/bob"))
        }
        await { viewModel.state.value.currentUsername == "bob" }
        assertEquals(false, viewModel.state.value.isSubscribed)
        instrumentation.runOnMainSync {
            viewModel.onEvent(FeedEvent.OnPageFinished("$instance/alice,bob"))
        }
        await { viewModel.state.value.currentUsername == null }
        assertNull(viewModel.state.value.currentUsername)
        assertTrue(!viewModel.state.value.isSubscribed)
    }

    @Test fun subscribingFromANestedProfileDoesNotReloadTheAllFeed() = runBlocking {
        val repository = SubscriptionRepository(context)
        repository.subscriptions.first()
        repository.addSubscription(Subscription("alice", "https://example.com"))
        lateinit var viewModel: FeedViewModel
        instrumentation.runOnMainSync {
            viewModel = FeedViewModel(
                UserPreferencesRepository(target),
                repository,
                object : ConnectivityMonitor {
                    override val isConnected = MutableStateFlow(true)
                },
                FeedGroupRepository(context)
            )
            store.put("feed-subscribe-nested", viewModel)
        }
        await { viewModel.state.value.currentUrl.endsWith("/alice") }
        val instance = viewModel.state.value.selectedInstanceUrl.trimEnd('/')
        val feedRoot = viewModel.state.value.currentUrl
        instrumentation.runOnMainSync {
            viewModel.onEvent(FeedEvent.OnPageStarted("$instance/alice/followers"))
            viewModel.onEvent(FeedEvent.OnPageFinished("$instance/alice/followers"))
        }
        await { viewModel.state.value.currentUsername == "alice" }
        instrumentation.runOnMainSync {
            viewModel.onEvent(FeedEvent.OnPageStarted("$instance/bob"))
            viewModel.onEvent(FeedEvent.OnPageFinished("$instance/bob"))
        }
        await { viewModel.state.value.currentUsername == "bob" && !viewModel.state.value.isSubscribed }
        instrumentation.runOnMainSync {
            viewModel.onEvent(FeedEvent.ToggleSubscription("bob"))
        }
        await { viewModel.state.value.isSubscribed }
        assertEquals("bob", viewModel.state.value.currentUsername)
        assertEquals(feedRoot, viewModel.state.value.currentUrl)
        assertEquals("$instance/bob", viewModel.state.value.displayedPageUrl)
        assertTrue(!viewModel.state.value.isLoading)
        assertTrue(repository.subscriptions.first().any { it.username.equals("bob", true) })
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Feed URL did not update after group selection" }
            Thread.sleep(20)
        }
    }
}
