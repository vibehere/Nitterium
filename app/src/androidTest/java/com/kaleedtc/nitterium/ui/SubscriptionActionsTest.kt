package com.kaleedtc.nitterium.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kaleedtc.nitterium.data.model.FeedGroup
import com.kaleedtc.nitterium.data.model.Subscription
import com.kaleedtc.nitterium.data.model.SubscriptionOptions
import com.kaleedtc.nitterium.ui.common.GroupAssignmentDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionActionsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun existingMembershipIsPreselectedAndOnlySavedOnConfirmation() {
        var saved: SubscriptionOptions? = null
        compose.setContent {
            MaterialTheme {
                GroupAssignmentDialog(
                    Subscription("alice", "https://example.com", groupIds = listOf("news")),
                    listOf(FeedGroup("news", "News"), FeedGroup("tech", "Tech")),
                    onDismiss = {},
                    onConfirm = { saved = it }
                )
            }
        }
        compose.onNodeWithText("News").assertIsOn()
        compose.onNodeWithText("Tech").performClick()
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(listOf("news", "tech"), saved?.groupIds)
            assertNull(saved?.hideRetweets)
            assertNull(saved?.hideReplies)
            assertEquals(false, saved?.mutedInFeed)
        }
    }

    @Test
    fun cancelDoesNotSaveDraft() {
        var saved: SubscriptionOptions? = null
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                GroupAssignmentDialog(
                    Subscription("alice", "https://example.com"),
                    listOf(FeedGroup("news", "News")),
                    onDismiss = { dismissed = true },
                    onConfirm = { saved = it },
                    isSubscribed = false
                )
            }
        }
        compose.onNodeWithText("News").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertNull(saved)
            assertEquals(true, dismissed)
        }
    }

    @Test
    fun emptyGroupsStillAllowSavingFilters() {
        var saved: SubscriptionOptions? = null
        compose.setContent {
            MaterialTheme {
                GroupAssignmentDialog(
                    Subscription("alice", "https://example.com", mutedInFeed = true),
                    emptyList(),
                    onDismiss = {},
                    onConfirm = { saved = it },
                    isSubscribed = false
                )
            }
        }
        compose.onNodeWithText("Subscribe and Save").assertIsEnabled()
        compose.onNodeWithText("No groups created yet. Go to Feed to create one!").assertExists()
        compose.onNodeWithText("Subscribe and Save").performClick()
        compose.runOnIdle {
            assertTrue(saved?.groupIds?.isEmpty() == true)
            assertEquals(true, saved?.mutedInFeed)
        }
    }
}
