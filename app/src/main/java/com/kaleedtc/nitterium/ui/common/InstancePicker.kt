package com.kaleedtc.nitterium.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaleedtc.nitterium.R
import com.kaleedtc.nitterium.data.repository.UserPreferencesRepository
import com.kaleedtc.nitterium.data.security.UrlPolicy
import java.io.IOException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstancePicker(preferences: UserPreferencesRepository, onDismiss: () -> Unit) {
    val selected by preferences.instanceUrl.collectAsStateWithLifecycle(initialValue = "")
    val custom by preferences.customInstances.collectAsStateWithLifecycle(initialValue = emptySet())
    val builtIn = stringArrayResource(R.array.default_instance_urls).toList()
    val torFriendly = stringArrayResource(R.array.tor_friendly_instance_urls).toList()
    val groups = remember(builtIn, custom, selected, torFriendly) {
        UrlPolicy.groupedInstanceChoices(builtIn, custom, selected, torFriendly)
    }
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    @Suppress("DEPRECATION")
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.switch_instance),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )
            Text(
                text = stringResource(R.string.instance_picker_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .selectableGroup()
                    .testTag("instance_choices"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                groups.forEach { (group, instances) ->
                    item(key = "header_${group.name}") {
                        Text(
                            text = stringResource(groupTitle(group)),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(instances, key = { it }) { instance ->
                        val isSelected = instance.trimEnd('/') == selected.trimEnd('/')
                        InstanceChoiceRow(
                            host = UrlPolicy.displayHost(instance),
                            url = instance,
                            group = group,
                            selected = isSelected,
                            enabled = !saving && !isSelected,
                            onClick = {
                                saving = true
                                failed = false
                                scope.launch {
                                    try {
                                        preferences.setInstanceUrl(instance)
                                        onDismiss()
                                    } catch (_: IOException) {
                                        failed = true
                                    } finally {
                                        saving = false
                                    }
                                }
                            }
                        )
                    }
                }
            }
            if (failed) {
                Text(
                    stringResource(R.string.instance_switch_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
            TextButton(
                onClick = onDismiss,
                enabled = !saving,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(horizontal = 16.dp)
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
private fun InstanceChoiceRow(
    host: String,
    url: String,
    group: UrlPolicy.InstanceGroup,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val badge = when (group) {
        UrlPolicy.InstanceGroup.Onion -> stringResource(R.string.instance_tor_label)
        UrlPolicy.InstanceGroup.TorFriendly -> stringResource(R.string.instance_tor_friendly_label)
        UrlPolicy.InstanceGroup.Public, UrlPolicy.InstanceGroup.Custom -> null
    }
    val icon: ImageVector = when (group) {
        UrlPolicy.InstanceGroup.Onion, UrlPolicy.InstanceGroup.TorFriendly -> Icons.Outlined.Shield
        UrlPolicy.InstanceGroup.Public, UrlPolicy.InstanceGroup.Custom -> Icons.Filled.Public
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
            .testTag("instance_$url"),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = host,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (badge != null) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(badge) },
                        modifier = Modifier.padding(top = 4.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            disabledContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            disabledLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

private fun groupTitle(group: UrlPolicy.InstanceGroup): Int = when (group) {
    UrlPolicy.InstanceGroup.TorFriendly -> R.string.instance_group_tor_friendly
    UrlPolicy.InstanceGroup.Public -> R.string.instance_group_public
    UrlPolicy.InstanceGroup.Onion -> R.string.instance_group_onion
    UrlPolicy.InstanceGroup.Custom -> R.string.instance_group_custom
}
