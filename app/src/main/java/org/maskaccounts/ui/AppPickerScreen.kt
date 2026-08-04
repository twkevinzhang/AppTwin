package org.maskaccounts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.maskaccounts.AppItem
import org.maskaccounts.GroupItem
import org.maskaccounts.MainUiState

@Composable
fun AppPickerScreen(
    state: MainUiState,
    group: GroupItem,
    onSelect: (AppItem) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filteredApps = remember(state.apps, group.group.apps, query) {
        val needle = query.trim()
        state.apps.filter { app ->
            !group.group.contains(app.entry.packageName) &&
                (
                    needle.isEmpty() ||
                        app.entry.label.contains(needle, ignoreCase = true) ||
                        app.entry.packageName.contains(needle, ignoreCase = true)
                    )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "加入「${group.group.name}」",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "從主系統匯入，不重複下載。這裡只顯示尚未加入此群組的 App。",
                        modifier = Modifier.padding(top = 7.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.82f),
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("搜尋 App 或套件名稱") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            )
        }
        if (filteredApps.isEmpty() && !state.isRefreshing) {
            item {
                Text(
                    if (query.isBlank()) "所有可用 App 都已加入" else "沒有符合「$query」的 App",
                    modifier = Modifier.padding(vertical = 32.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(filteredApps, key = { it.entry.packageName }) { app ->
            AppPickerItem(
                app = app,
                isBusy = state.busyPackageName == app.entry.packageName,
                enabled = state.busyPackageName == null,
                onClick = { onSelect(app) },
            )
        }
    }
}

@Composable
private fun AppPickerItem(
    app: AppItem,
    isBusy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column {
        ListItem(
            modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
            leadingContent = {
                AppIcon(packageName = app.entry.packageName, size = 52.dp)
            },
            headlineContent = {
                Text(
                    app.entry.label,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                Column {
                    Text(
                        app.entry.packageName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier.padding(top = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (app.isSynced) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            buildString {
                                append(
                                    when {
                                        app.isSynced -> "版本已同步"
                                        app.activeVersionCode != null -> "需要同步新版"
                                        else -> "首次使用時同步"
                                    },
                                )
                                if (app.groupCount > 0) append(" · 已加入 ${app.groupCount} 個群組")
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (app.isSynced) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            },
            trailingContent = {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.AddCircle,
                        contentDescription = "加入群組",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = 68.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
    }
}
