package com.kaleedtc.nitterium.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kaleedtc.nitterium.R
import com.kaleedtc.nitterium.data.model.Subscription
import com.kaleedtc.nitterium.data.model.SubscriptionOptions

@Composable
fun GroupAssignmentDialog(
    subscription: Subscription,
    allGroups: List<com.kaleedtc.nitterium.data.model.FeedGroup>,
    onDismiss: () -> Unit,
    onConfirm: (SubscriptionOptions) -> Unit,
    isSubscribed: Boolean = true,
    isSaving: Boolean = false
) {
    var selectedGroupIds by rememberSaveable(subscription.username) { mutableStateOf(subscription.groupIds) }
    var hideRetweets by rememberSaveable(subscription.username) {
        mutableStateOf(triStateKey(subscription.hideRetweets))
    }
    var hideReplies by rememberSaveable(subscription.username) {
        mutableStateOf(triStateKey(subscription.hideReplies))
    }
    var mutedInFeed by rememberSaveable(subscription.username) { mutableStateOf(subscription.mutedInFeed) }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.subscription_options, subscription.username)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        text = stringResource(R.string.subscription_filters),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.subscription_filters_local_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    TriStateFilterRow(
                        label = stringResource(R.string.hide_retweets),
                        selected = hideRetweets,
                        enabled = !isSaving,
                        onSelected = { hideRetweets = it }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TriStateFilterRow(
                        label = stringResource(R.string.hide_replies),
                        selected = hideReplies,
                        enabled = !isSaving,
                        onSelected = { hideReplies = it }
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = mutedInFeed,
                                enabled = !isSaving,
                                role = Role.Switch,
                                onValueChange = { mutedInFeed = it }
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.mute_in_feed))
                            Text(
                                text = stringResource(R.string.mute_in_feed_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = mutedInFeed,
                            onCheckedChange = null,
                            enabled = !isSaving
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.assign_to_groups),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (allGroups.isEmpty()) {
                    item { Text(stringResource(R.string.no_groups_created)) }
                } else {
                    items(allGroups) { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = selectedGroupIds.contains(group.id),
                                    enabled = !isSaving,
                                    role = Role.Checkbox,
                                    onValueChange = {
                                        selectedGroupIds = if (selectedGroupIds.contains(group.id)) {
                                            selectedGroupIds - group.id
                                        } else {
                                            selectedGroupIds + group.id
                                        }
                                    }
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selectedGroupIds.contains(group.id),
                                onCheckedChange = null
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(group.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSaving,
                onClick = {
                    onConfirm(
                        SubscriptionOptions(
                            groupIds = selectedGroupIds.filter { id -> allGroups.any { it.id == id } },
                            hideRetweets = fromTriStateKey(hideRetweets),
                            hideReplies = fromTriStateKey(hideReplies),
                            mutedInFeed = mutedInFeed
                        )
                    )
                }
            ) {
                Text(stringResource(if (isSubscribed) R.string.save else R.string.subscribe_and_save))
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
private fun TriStateFilterRow(
    label: String,
    selected: String,
    enabled: Boolean,
    onSelected: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "default" to R.string.filter_default,
                "hide" to R.string.filter_hide,
                "show" to R.string.filter_show
            ).forEach { (key, labelRes) ->
                FilterChip(
                    selected = selected == key,
                    onClick = { if (enabled) onSelected(key) },
                    enabled = enabled,
                    label = { Text(stringResource(labelRes)) }
                )
            }
        }
    }
}

private fun triStateKey(value: Boolean?): String = when (value) {
    null -> "default"
    true -> "hide"
    false -> "show"
}

private fun fromTriStateKey(key: String): Boolean? = when (key) {
    "hide" -> true
    "show" -> false
    else -> null
}
