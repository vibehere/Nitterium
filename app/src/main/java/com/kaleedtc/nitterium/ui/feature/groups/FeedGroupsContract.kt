package com.kaleedtc.nitterium.ui.feature.groups

import android.net.Uri
import com.kaleedtc.nitterium.data.model.FeedGroup
import com.kaleedtc.nitterium.data.model.Subscription

data class FeedGroupsState(
    val groups: List<FeedGroup> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val subscriptionCounts: Map<String, Int> = emptyMap(),
    val totalSubscriptionsCount: Int = 0,
    val isLoading: Boolean = false,
    val isSavingMembers: Boolean = false,
    val membersGroup: FeedGroup? = null,
    val selectionMode: Boolean = false,
    val selectedGroupIds: Set<String> = emptySet(),
    val isBusy: Boolean = false,
    val showDeleteSelectedConfirm: Boolean = false,
    val showExportFormatDialog: Boolean = false
)

sealed interface FeedGroupsEvent {
    data class AddGroup(val name: String, val icon: String? = null, val color: String? = null) : FeedGroupsEvent
    data class UpdateGroup(val id: String, val name: String, val icon: String? = null, val color: String? = null) : FeedGroupsEvent
    data class DeleteGroup(val groupId: String) : FeedGroupsEvent
    data class OpenGroup(val groupId: String, val groupName: String) : FeedGroupsEvent
    data class EditMembers(val groupId: String) : FeedGroupsEvent
    data object DismissMembers : FeedGroupsEvent
    data class SaveMembers(val groupId: String, val usernames: Set<String>) : FeedGroupsEvent
    data object OpenAllFeed : FeedGroupsEvent

    data object EnterSelectionMode : FeedGroupsEvent
    data class StartSelection(val groupId: String) : FeedGroupsEvent
    data object ExitSelectionMode : FeedGroupsEvent
    data class ToggleGroupSelection(val groupId: String) : FeedGroupsEvent
    data object SelectAllGroups : FeedGroupsEvent
    data object RequestDeleteSelected : FeedGroupsEvent
    data object ConfirmDeleteSelected : FeedGroupsEvent
    data object CancelDeleteSelected : FeedGroupsEvent
    data object RequestExportSelected : FeedGroupsEvent
    data object DismissExportFormat : FeedGroupsEvent
    data class ExportSelected(val asUsernameList: Boolean) : FeedGroupsEvent
    data class ExportDestinationChosen(val uri: Uri?, val asUsernameList: Boolean) : FeedGroupsEvent
    data object ImportIntoSelected : FeedGroupsEvent
    data class ImportFileChosen(val uri: Uri?) : FeedGroupsEvent
}

sealed interface FeedGroupsEffect {
    data class NavigateToGroupFeed(val groupId: String, val groupName: String) : FeedGroupsEffect
    data object NavigateToAllFeed : FeedGroupsEffect
    data class ShowMessage(val messageRes: Int) : FeedGroupsEffect
    data class LaunchGroupExport(val fileName: String, val mimeType: String, val asUsernameList: Boolean) : FeedGroupsEffect
    data object LaunchGroupImport : FeedGroupsEffect
}
