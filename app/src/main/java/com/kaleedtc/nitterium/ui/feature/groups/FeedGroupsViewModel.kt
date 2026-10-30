package com.kaleedtc.nitterium.ui.feature.groups

import androidx.lifecycle.viewModelScope
import com.kaleedtc.nitterium.R
import com.kaleedtc.nitterium.data.model.FeedGroup
import com.kaleedtc.nitterium.data.repository.BackupRepository
import com.kaleedtc.nitterium.data.repository.FeedGroupRepository
import com.kaleedtc.nitterium.data.repository.SubscriptionRepository
import com.kaleedtc.nitterium.ui.common.MviViewModel
import java.time.LocalDate
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class FeedGroupsViewModel(
    private val groupRepository: FeedGroupRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val backupRepository: BackupRepository
) : MviViewModel<FeedGroupsState, FeedGroupsEvent, FeedGroupsEffect>(FeedGroupsState()) {

    init {
        viewModelScope.launch {
            combine(
                groupRepository.groups,
                subscriptionRepository.subscriptions
            ) { groups, subscriptions ->
                groups to subscriptions
            }.collect { (groups, subscriptions) ->
                val counts = groups.associate { group ->
                    group.id to subscriptions.count { it.groupIds.contains(group.id) }
                }
                setState {
                    val selected = selectedGroupIds.filter { id -> groups.any { it.id == id } }.toSet()
                    copy(
                        groups = groups,
                        subscriptions = subscriptions,
                        subscriptionCounts = counts,
                        totalSubscriptionsCount = subscriptions.size,
                        isLoading = false,
                        membersGroup = membersGroup?.let { editing ->
                            groups.firstOrNull { it.id == editing.id }
                        },
                        selectedGroupIds = selected,
                        selectionMode = selectionMode && (groups.isNotEmpty() || selected.isNotEmpty())
                    )
                }
            }
        }
    }

    override fun onEvent(event: FeedGroupsEvent) {
        when (event) {
            is FeedGroupsEvent.AddGroup -> {
                viewModelScope.launch {
                    groupRepository.addGroup(event.name, icon = event.icon, color = event.color)
                }
            }
            is FeedGroupsEvent.UpdateGroup -> {
                viewModelScope.launch {
                    groupRepository.updateGroup(
                        FeedGroup(event.id, event.name, event.icon, event.color)
                    )
                }
            }
            is FeedGroupsEvent.DeleteGroup -> {
                viewModelScope.launch {
                    subscriptionRepository.removeGroupMembership(event.groupId)
                    groupRepository.removeGroup(event.groupId)
                    setState {
                        copy(
                            membersGroup = membersGroup?.takeUnless { it.id == event.groupId },
                            isSavingMembers = if (membersGroup?.id == event.groupId) false else isSavingMembers,
                            selectedGroupIds = selectedGroupIds - event.groupId
                        )
                    }
                }
            }
            is FeedGroupsEvent.OpenGroup -> {
                if (state.value.selectionMode) {
                    onEvent(FeedGroupsEvent.ToggleGroupSelection(event.groupId))
                } else {
                    setEffect { FeedGroupsEffect.NavigateToGroupFeed(event.groupId, event.groupName) }
                }
            }
            is FeedGroupsEvent.EditMembers -> {
                val group = state.value.groups.firstOrNull { it.id == event.groupId } ?: return
                setState { copy(membersGroup = group) }
            }
            is FeedGroupsEvent.DismissMembers -> {
                if (!state.value.isSavingMembers) {
                    setState { copy(membersGroup = null) }
                }
            }
            is FeedGroupsEvent.SaveMembers -> {
                if (state.value.isSavingMembers) return
                setState { copy(isSavingMembers = true) }
                viewModelScope.launch {
                    subscriptionRepository.setGroupMembers(event.groupId, event.usernames).fold(
                        onSuccess = {
                            setState { copy(membersGroup = null, isSavingMembers = false) }
                            setEffect { FeedGroupsEffect.ShowMessage(R.string.group_members_saved) }
                        },
                        onFailure = {
                            setState { copy(isSavingMembers = false) }
                            setEffect { FeedGroupsEffect.ShowMessage(R.string.group_members_save_failed) }
                        }
                    )
                }
            }
            is FeedGroupsEvent.OpenAllFeed -> {
                if (!state.value.selectionMode) {
                    setEffect { FeedGroupsEffect.NavigateToAllFeed }
                }
            }
            FeedGroupsEvent.EnterSelectionMode -> {
                setState { copy(selectionMode = true, selectedGroupIds = emptySet()) }
            }
            is FeedGroupsEvent.StartSelection -> {
                setState {
                    copy(selectionMode = true, selectedGroupIds = setOf(event.groupId))
                }
            }
            FeedGroupsEvent.ExitSelectionMode -> {
                setState {
                    copy(
                        selectionMode = false,
                        selectedGroupIds = emptySet(),
                        showDeleteSelectedConfirm = false,
                        showExportFormatDialog = false
                    )
                }
            }
            is FeedGroupsEvent.ToggleGroupSelection -> {
                setState {
                    val next = if (event.groupId in selectedGroupIds) {
                        selectedGroupIds - event.groupId
                    } else {
                        selectedGroupIds + event.groupId
                    }
                    copy(
                        selectionMode = true,
                        selectedGroupIds = next,
                        showDeleteSelectedConfirm = false
                    )
                }
            }
            FeedGroupsEvent.SelectAllGroups -> {
                setState {
                    copy(
                        selectionMode = true,
                        selectedGroupIds = groups.map { it.id }.toSet()
                    )
                }
            }
            FeedGroupsEvent.RequestDeleteSelected -> {
                if (state.value.selectedGroupIds.isEmpty() || state.value.isBusy) return
                setState { copy(showDeleteSelectedConfirm = true) }
            }
            FeedGroupsEvent.CancelDeleteSelected -> {
                setState { copy(showDeleteSelectedConfirm = false) }
            }
            FeedGroupsEvent.ConfirmDeleteSelected -> {
                val ids = state.value.selectedGroupIds
                if (ids.isEmpty() || state.value.isBusy) return
                setState { copy(isBusy = true, showDeleteSelectedConfirm = false) }
                viewModelScope.launch {
                    ids.forEach { id ->
                        subscriptionRepository.removeGroupMembership(id)
                        groupRepository.removeGroup(id)
                    }
                    setState {
                        copy(
                            isBusy = false,
                            selectionMode = false,
                            selectedGroupIds = emptySet(),
                            membersGroup = membersGroup?.takeUnless { it.id in ids },
                            isSavingMembers = if (membersGroup?.id in ids) false else isSavingMembers
                        )
                    }
                    setEffect { FeedGroupsEffect.ShowMessage(R.string.groups_deleted) }
                }
            }
            FeedGroupsEvent.RequestExportSelected -> {
                if (state.value.selectedGroupIds.isEmpty() || state.value.isBusy) return
                setState { copy(showExportFormatDialog = true) }
            }
            FeedGroupsEvent.DismissExportFormat -> {
                setState { copy(showExportFormatDialog = false) }
            }
            is FeedGroupsEvent.ExportSelected -> {
                if (state.value.selectedGroupIds.isEmpty() || state.value.isBusy) return
                setState { copy(showExportFormatDialog = false) }
                val date = LocalDate.now()
                val (name, mime) = if (event.asUsernameList) {
                    "Nitterium-groups-$date.txt" to "text/plain"
                } else {
                    "Nitterium-groups-$date.json" to "application/json"
                }
                setEffect {
                    FeedGroupsEffect.LaunchGroupExport(name, mime, event.asUsernameList)
                }
            }
            is FeedGroupsEvent.ExportDestinationChosen -> {
                val uri = event.uri ?: return
                val ids = state.value.selectedGroupIds
                if (ids.isEmpty() || state.value.isBusy) return
                setState { copy(isBusy = true) }
                viewModelScope.launch {
                    backupRepository.exportGroups(uri, ids, event.asUsernameList).fold(
                        onSuccess = {
                            setState { copy(isBusy = false) }
                            setEffect { FeedGroupsEffect.ShowMessage(R.string.backup_export_success) }
                        },
                        onFailure = {
                            setState { copy(isBusy = false) }
                            setEffect { FeedGroupsEffect.ShowMessage(R.string.backup_export_failed) }
                        }
                    )
                }
            }
            FeedGroupsEvent.ImportIntoSelected -> {
                if (state.value.selectedGroupIds.isEmpty() || state.value.isBusy) return
                setEffect { FeedGroupsEffect.LaunchGroupImport }
            }
            is FeedGroupsEvent.ImportFileChosen -> {
                val uri = event.uri ?: return
                val ids = state.value.selectedGroupIds
                if (ids.isEmpty() || state.value.isBusy) return
                setState { copy(isBusy = true) }
                viewModelScope.launch {
                    backupRepository.importIntoGroups(uri, ids).fold(
                        onSuccess = {
                            setState { copy(isBusy = false) }
                            setEffect { FeedGroupsEffect.ShowMessage(R.string.groups_import_success) }
                        },
                        onFailure = {
                            setState { copy(isBusy = false) }
                            setEffect { FeedGroupsEffect.ShowMessage(R.string.groups_import_failed) }
                        }
                    )
                }
            }
        }
    }
}
