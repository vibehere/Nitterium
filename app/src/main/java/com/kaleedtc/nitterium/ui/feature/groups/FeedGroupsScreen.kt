package com.kaleedtc.nitterium.ui.feature.groups

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaleedtc.nitterium.R
import com.kaleedtc.nitterium.data.model.FeedGroup
import com.kaleedtc.nitterium.data.model.Subscription

@Composable
fun FeedGroupsScreen(
    viewModel: FeedGroupsViewModel,
    onNavigateToGroup: (String, String) -> Unit,
    onNavigateToAll: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var groupToEdit by remember { mutableStateOf<FeedGroup?>(null) }
    var groupToDelete by remember { mutableStateOf<FeedGroup?>(null) }
    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        viewModel.onEvent(FeedGroupsEvent.ExportDestinationChosen(uri, asUsernameList = false))
    }
    val exportTextLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        viewModel.onEvent(FeedGroupsEvent.ExportDestinationChosen(uri, asUsernameList = true))
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        viewModel.onEvent(FeedGroupsEvent.ImportFileChosen(uri))
    }

    LaunchedEffect(viewModel.effect) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is FeedGroupsEffect.NavigateToGroupFeed -> onNavigateToGroup(effect.groupId, effect.groupName)
                FeedGroupsEffect.NavigateToAllFeed -> onNavigateToAll()
                is FeedGroupsEffect.ShowMessage -> {
                    Toast.makeText(context, context.getString(effect.messageRes), Toast.LENGTH_SHORT).show()
                }
                is FeedGroupsEffect.LaunchGroupExport -> {
                    if (effect.asUsernameList) exportTextLauncher.launch(effect.fileName)
                    else exportJsonLauncher.launch(effect.fileName)
                }
                FeedGroupsEffect.LaunchGroupImport -> {
                    importLauncher.launch(
                        arrayOf("application/json", "text/plain", "text/*", "application/octet-stream")
                    )
                }
            }
        }
    }

    BackHandler(enabled = state.selectionMode) {
        viewModel.onEvent(FeedGroupsEvent.ExitSelectionMode)
    }

    Scaffold(
        topBar = {
            if (state.selectionMode) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            pluralStringResource(
                                R.plurals.groups_selected_count,
                                state.selectedGroupIds.size,
                                state.selectedGroupIds.size
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.onEvent(FeedGroupsEvent.ExitSelectionMode) }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { viewModel.onEvent(FeedGroupsEvent.SelectAllGroups) },
                            enabled = !state.isBusy && state.groups.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.SelectAll,
                                contentDescription = stringResource(R.string.groups_select_all)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.onEvent(FeedGroupsEvent.ImportIntoSelected) },
                            enabled = !state.isBusy && state.selectedGroupIds.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.FileDownload,
                                contentDescription = stringResource(R.string.import_label)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.onEvent(FeedGroupsEvent.RequestExportSelected) },
                            enabled = !state.isBusy && state.selectedGroupIds.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.FileUpload,
                                contentDescription = stringResource(R.string.export_label)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.onEvent(FeedGroupsEvent.RequestDeleteSelected) },
                            enabled = !state.isBusy && state.selectedGroupIds.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.delete)
                            )
                        }
                    }
                )
            } else {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.groups)) },
                    actions = {
                        if (state.groups.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onEvent(FeedGroupsEvent.EnterSelectionMode) }) {
                                Icon(
                                    Icons.Default.Checklist,
                                    contentDescription = stringResource(R.string.groups_select)
                                )
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!state.selectionMode) {
                FloatingActionButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_group))
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (!state.selectionMode) {
                    item {
                        GroupCard(
                            name = stringResource(R.string.all),
                            count = state.totalSubscriptionsCount,
                            icon = Icons.Default.RssFeed,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            onClick = { viewModel.onEvent(FeedGroupsEvent.OpenAllFeed) }
                        )
                    }
                }

                items(state.groups, key = { it.id }) { group ->
                    val count = state.subscriptionCounts[group.id] ?: 0
                    val color = group.color?.let { Color(it.toColorInt()) }
                        ?: MaterialTheme.colorScheme.surfaceVariant
                    val selected = group.id in state.selectedGroupIds

                    GroupCard(
                        name = group.name,
                        count = count,
                        icon = getIconForName(group.icon),
                        color = color,
                        selected = state.selectionMode && selected,
                        selectionMode = state.selectionMode,
                        onClick = {
                            if (state.selectionMode) {
                                viewModel.onEvent(FeedGroupsEvent.ToggleGroupSelection(group.id))
                            } else {
                                viewModel.onEvent(FeedGroupsEvent.OpenGroup(group.id, group.name))
                            }
                        },
                        onLongClick = {
                            if (state.selectionMode) {
                                viewModel.onEvent(FeedGroupsEvent.ToggleGroupSelection(group.id))
                            } else {
                                viewModel.onEvent(FeedGroupsEvent.StartSelection(group.id))
                            }
                        },
                        onEdit = if (state.selectionMode) null else ({ groupToEdit = group }),
                        onManageMembers = if (state.selectionMode) {
                            null
                        } else {
                            { viewModel.onEvent(FeedGroupsEvent.EditMembers(group.id)) }
                        },
                        onDelete = if (state.selectionMode) null else ({ groupToDelete = group })
                    )
                }
            }
        }

        if (showAddDialog) {
            GroupDialog(
                initialGroup = null,
                onDismiss = { showAddDialog = false },
                onConfirm = { name, icon, color ->
                    viewModel.onEvent(FeedGroupsEvent.AddGroup(name, color = color, icon = icon))
                    showAddDialog = false
                },
                onDelete = null
            )
        }

        if (groupToEdit != null) {
            GroupDialog(
                initialGroup = groupToEdit,
                onDismiss = { groupToEdit = null },
                onConfirm = { name, icon, color ->
                    viewModel.onEvent(
                        FeedGroupsEvent.UpdateGroup(groupToEdit!!.id, name, icon = icon, color = color)
                    )
                    groupToEdit = null
                },
                onDelete = {
                    groupToDelete = groupToEdit
                    groupToEdit = null
                }
            )
        }

        groupToDelete?.let { group ->
            AlertDialog(
                onDismissRequest = { groupToDelete = null },
                title = { Text(stringResource(R.string.delete_group_title)) },
                text = { Text(stringResource(R.string.delete_group_message, group.name)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.onEvent(FeedGroupsEvent.DeleteGroup(group.id))
                            groupToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { groupToDelete = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        if (state.showDeleteSelectedConfirm) {
            AlertDialog(
                onDismissRequest = { viewModel.onEvent(FeedGroupsEvent.CancelDeleteSelected) },
                title = { Text(stringResource(R.string.delete_groups_title)) },
                text = {
                    Text(
                        pluralStringResource(
                            R.plurals.delete_groups_message,
                            state.selectedGroupIds.size,
                            state.selectedGroupIds.size
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.onEvent(FeedGroupsEvent.ConfirmDeleteSelected) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.onEvent(FeedGroupsEvent.CancelDeleteSelected) }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        if (state.showExportFormatDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.onEvent(FeedGroupsEvent.DismissExportFormat) },
                title = { Text(stringResource(R.string.groups_export_title)) },
                text = { Text(stringResource(R.string.groups_export_description)) },
                confirmButton = {
                    Column {
                        TextButton(onClick = {
                            viewModel.onEvent(FeedGroupsEvent.ExportSelected(asUsernameList = false))
                        }) {
                            Text(stringResource(R.string.groups_export_json))
                        }
                        TextButton(onClick = {
                            viewModel.onEvent(FeedGroupsEvent.ExportSelected(asUsernameList = true))
                        }) {
                            Text(stringResource(R.string.groups_export_username_list))
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.onEvent(FeedGroupsEvent.DismissExportFormat) }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        state.membersGroup?.let { group ->
            GroupMembersDialog(
                group = group,
                subscriptions = state.subscriptions,
                isSaving = state.isSavingMembers,
                onDismiss = { viewModel.onEvent(FeedGroupsEvent.DismissMembers) },
                onConfirm = { usernames ->
                    viewModel.onEvent(FeedGroupsEvent.SaveMembers(group.id, usernames))
                }
            )
        }
    }
}

@Composable
private fun GroupMembersDialog(
    group: FeedGroup,
    subscriptions: List<Subscription>,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var selected by rememberSaveable(group.id) {
        mutableStateOf(
            subscriptions.filter { group.id in it.groupIds }.map { it.username }.toSet()
        )
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.group_members_title, group.name)) },
        text = {
            if (subscriptions.isEmpty()) {
                Text(stringResource(R.string.no_subscriptions_for_group))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(subscriptions, key = { it.username }) { subscription ->
                        val checked = selected.any { it.equals(subscription.username, ignoreCase = true) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked,
                                    enabled = !isSaving,
                                    role = Role.Checkbox,
                                    onValueChange = {
                                        selected = if (checked) {
                                            selected.filterNot {
                                                it.equals(subscription.username, ignoreCase = true)
                                            }.toSet()
                                        } else {
                                            selected + subscription.username
                                        }
                                    }
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("@${subscription.username}")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSaving && subscriptions.isNotEmpty(),
                onClick = { onConfirm(selected) }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun GroupCard(
    name: String,
    count: Int,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onManageMembers: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    selected: Boolean = false,
    selectionMode: Boolean = false
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val hasMenu = !selectionMode && (onEdit != null || onManageMembers != null || onDelete != null)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(color)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.Center),
                    tint = contentColorFor(color)
                )
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = null,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                    )
                }
                if (hasMenu) {
                    Box(modifier = Modifier.align(Alignment.TopEnd)) {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more_options),
                                tint = contentColorFor(color)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            if (onManageMembers != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.manage_group_members)) },
                                    onClick = {
                                        menuExpanded = false
                                        onManageMembers()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Group, contentDescription = null)
                                    }
                                )
                            }
                            if (onEdit != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.edit_group_action)) },
                                    onClick = {
                                        menuExpanded = false
                                        onEdit()
                                    }
                                )
                            }
                            if (onDelete != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete)) },
                                    onClick = {
                                        menuExpanded = false
                                        onDelete()
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = "($count)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun GroupDialog(
    initialGroup: FeedGroup?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by remember { mutableStateOf(initialGroup?.name ?: "") }
    var selectedIcon by remember { mutableStateOf(initialGroup?.icon ?: "folder") }
    var selectedColor by remember { mutableStateOf(initialGroup?.color ?: "#607D8B") }

    val icons = listOf(
        "music" to Icons.Default.MusicNote,
        "news" to Icons.Default.Newspaper,
        "politics" to Icons.Default.Gavel,
        "tech" to Icons.Default.Laptop,
        "sports" to Icons.Default.SportsBasketball,
        "movies" to Icons.Default.Movie,
        "gaming" to Icons.Default.Gamepad,
        "code" to Icons.Default.Code,
        "science" to Icons.Default.Science,
        "school" to Icons.Default.School,
        "art" to Icons.Default.Palette,
        "food" to Icons.Default.Restaurant,
        "travel" to Icons.Default.Flight,
        "finance" to Icons.Default.AttachMoney,
        "fitness" to Icons.Default.FitnessCenter,
        "pets" to Icons.Default.Pets,
        "cars" to Icons.Default.DirectionsCar,
        "camera" to Icons.Default.CameraAlt,
        "folder" to Icons.Default.Folder
    )

    val colors = listOf(
        "#F44336", "#E91E63", "#9C27B0", "#673AB7",
        "#3F51B5", "#2196F3", "#03A9F4", "#00BCD4",
        "#009688", "#4CAF50", "#8BC34A", "#CDDC39",
        "#FFEB3B", "#FFC107", "#FF9800", "#FF5722",
        "#795548", "#9E9E9E", "#607D8B"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initialGroup == null) R.string.add_group else R.string.edit_group)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.group_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text(
                    text = stringResource(R.string.select_icon),
                    style = MaterialTheme.typography.titleSmall
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(40.dp),
                    modifier = Modifier.height(100.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(icons) { (id, icon) ->
                        IconButton(
                            onClick = { selectedIcon = id },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (selectedIcon == id) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                        ) {
                            Icon(icon, contentDescription = null)
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.select_color),
                    style = MaterialTheme.typography.titleSmall
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(30.dp),
                    modifier = Modifier.height(100.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(colors) { hex ->
                        val color = Color(hex.toColorInt())
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (selectedColor == hex) 2.dp else 0.dp,
                                    color = if (selectedColor == hex) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color.Transparent
                                    },
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = hex }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initialGroup != null && onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                }
                TextButton(
                    onClick = { if (name.isNotBlank()) onConfirm(name, selectedIcon, selectedColor) },
                    enabled = name.isNotBlank()
                ) {
                    Text(stringResource(R.string.ok))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

fun getIconForName(name: String?): ImageVector {
    return when (name) {
        "music" -> Icons.Default.MusicNote
        "news" -> Icons.Default.Newspaper
        "politics" -> Icons.Default.Gavel
        "tech" -> Icons.Default.Laptop
        "sports" -> Icons.Default.SportsBasketball
        "movies" -> Icons.Default.Movie
        "gaming" -> Icons.Default.Gamepad
        "code" -> Icons.Default.Code
        "science" -> Icons.Default.Science
        "school" -> Icons.Default.School
        "art" -> Icons.Default.Palette
        "food" -> Icons.Default.Restaurant
        "travel" -> Icons.Default.Flight
        "finance" -> Icons.Default.AttachMoney
        "fitness" -> Icons.Default.FitnessCenter
        "pets" -> Icons.Default.Pets
        "cars" -> Icons.Default.DirectionsCar
        "camera" -> Icons.Default.CameraAlt
        else -> Icons.Default.Folder
    }
}
