package com.kaleedtc.nitterium.ui.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaleedtc.nitterium.R
import com.kaleedtc.nitterium.data.model.InstanceDirectoryEntry
import com.kaleedtc.nitterium.data.security.TorOnionPreference
import com.kaleedtc.nitterium.data.security.UrlPolicy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceBrowserDialog(
    selectedUrl: String,
    builtIn: List<String>,
    custom: List<String>,
    discovered: List<String>,
    directoryEntries: List<InstanceDirectoryEntry> = emptyList(),
    disabledUrls: Set<String>,
    onionOnly: Boolean,
    onSelect: (String) -> Unit,
    onAddCustom: (String) -> Unit,
    onRemoveCustom: (String) -> Unit,
    onSetDisabled: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var draftUrl by remember { mutableStateOf("") }
    val needle = query.trim().lowercase()
    @Suppress("DEPRECATION")
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val catalog = remember(builtIn, custom, discovered, onionOnly) {
        (builtIn + discovered + custom)
            .filter { UrlPolicy.instanceUrl(it) != null }
            .filter { !onionOnly || TorOnionPreference.isOnionInstance(it) }
            .distinctBy { it.trimEnd('/').lowercase() }
    }
    val filtered = remember(catalog, needle, selectedUrl) {
        catalog
            .filter { url ->
                needle.isEmpty() ||
                    url.lowercase().contains(needle) ||
                    UrlPolicy.displayHost(url).lowercase().contains(needle)
            }
            .sortedWith(
                compareByDescending<String> { sameInstanceUrl(it, selectedUrl) }
                    .thenBy { UrlPolicy.displayHost(it).lowercase() }
            )
    }
    val groups = remember(filtered, custom, selectedUrl) {
        UrlPolicy.groupedInstanceChoices(
            builtIn = filtered.filter { url ->
                custom.none { it.equals(url, ignoreCase = true) }
            },
            custom = custom.filter { url ->
                filtered.any { it.equals(url, ignoreCase = true) }
            }.toSet(),
            selected = selectedUrl
        )
    }
    val expanded = remember {
        mutableStateMapOf<UrlPolicy.InstanceGroup, Boolean>()
    }
    LaunchedEffect(groups, selectedUrl, onionOnly) {
        groups.forEach { (group, instances) ->
            if (group !in expanded) {
                val hasSelected = instances.any { sameInstanceUrl(it, selectedUrl) }
                expanded[group] = when (group) {
                    UrlPolicy.InstanceGroup.Public -> true
                    UrlPolicy.InstanceGroup.Custom -> true
                    UrlPolicy.InstanceGroup.Onion -> hasSelected || onionOnly
                }
            }
        }
    }
    val disabledNorm = remember(disabledUrls) {
        disabledUrls.map { it.trimEnd('/').lowercase() }.toSet()
    }
    val catalogByUrl = remember(directoryEntries) {
        directoryEntries.associateBy { it.normalizedUrl().lowercase() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(
                    if (onionOnly) R.string.instance_browser_onion_title
                    else R.string.instance_browser_title
                ),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = stringResource(
                    if (onionOnly) R.string.instance_browser_onion_subtitle
                    else R.string.instance_browser_subtitle
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.instance_browser_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 420.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.instance_browser_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                groups.forEach { (group, instances) ->
                    val isExpanded = expanded[group] != false
                    item(key = "h_${group.name}") {
                        val title = stringResource(groupTitle(group))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) {
                                    expanded[group] = !isExpanded
                                }
                                .padding(top = 10.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$title (${instances.size})",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = if (isExpanded) {
                                    Icons.Filled.ExpandLess
                                } else {
                                    Icons.Filled.ExpandMore
                                },
                                contentDescription = stringResource(
                                    if (isExpanded) {
                                        R.string.instance_group_collapse
                                    } else {
                                        R.string.instance_group_expand
                                    },
                                    title
                                ),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (isExpanded) {
                        items(instances, key = { it }) { instance ->
                            val selected = sameInstanceUrl(instance, selectedUrl)
                            val isCustom = custom.any { it.equals(instance, ignoreCase = true) }
                            val enabledInPool = instance.trimEnd('/').lowercase() !in disabledNorm
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelect(instance)
                                        onDismiss()
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = {
                                        onSelect(instance)
                                        onDismiss()
                                    }
                                )
                                Icon(
                                    imageVector = when (group) {
                                        UrlPolicy.InstanceGroup.Onion -> Icons.Outlined.Shield
                                        UrlPolicy.InstanceGroup.Public,
                                        UrlPolicy.InstanceGroup.Custom -> Icons.Filled.Public
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = UrlPolicy.displayHost(instance),
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = instance,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    val meta = catalogByUrl[instance.trimEnd('/').lowercase()]
                                    if (meta != null) {
                                        Text(
                                            text = instanceMetaLine(meta),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }
                                if (!onionOnly) {
                                    Switch(
                                        checked = enabledInPool,
                                        onCheckedChange = { checked ->
                                            onSetDisabled(instance, !checked)
                                        }
                                    )
                                }
                                if (isCustom) {
                                    IconButton(onClick = { onRemoveCustom(instance) }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = stringResource(R.string.remove),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
            if (!onionOnly) {
                Text(
                    text = stringResource(R.string.instance_browser_pool_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = draftUrl,
                onValueChange = { draftUrl = it },
                label = { Text(stringResource(R.string.custom_instance_url_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        val trimmed = draftUrl.trim()
                        if (trimmed.isNotBlank()) {
                            onAddCustom(trimmed)
                            draftUrl = ""
                        }
                    },
                    enabled = draftUrl.isNotBlank()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.add_custom_instance),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

private fun groupTitle(group: UrlPolicy.InstanceGroup): Int = when (group) {
    UrlPolicy.InstanceGroup.Public -> R.string.instance_group_public
    UrlPolicy.InstanceGroup.Onion -> R.string.instance_group_onion
    UrlPolicy.InstanceGroup.Custom -> R.string.instance_group_custom
}

private fun sameInstanceUrl(a: String, b: String): Boolean =
    a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)

private fun instanceMetaLine(entry: InstanceDirectoryEntry): String {
    val parts = mutableListOf<String>()
    entry.version?.takeIf { it.isNotBlank() }?.let { parts += "v$it" }
    entry.ddosProtection?.takeIf { it.isNotBlank() && !it.equals("None", true) }
        ?.let { parts += it }
    when (entry.rssReported) {
        true -> parts += "RSS"
        false -> parts += "no RSS"
        null -> Unit
    }
    when (entry.worksWithoutJs) {
        true -> parts += "no-JS ok"
        false -> parts += "no-JS fail"
        null -> Unit
    }
    when (entry.worksHtml) {
        true -> parts += "HTML ok"
        false -> parts += "HTML fail"
        null -> Unit
    }
    when (entry.worksRss) {
        true -> if (entry.rssReported != true) parts += "RSS ok"
        false -> if (entry.rssReported != false) parts += "RSS fail"
        null -> Unit
    }
    return parts.joinToString(" · ")
}
