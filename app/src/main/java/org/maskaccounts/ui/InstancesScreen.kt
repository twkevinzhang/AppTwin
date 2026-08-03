package org.maskaccounts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.maskaccounts.InstanceItem
import org.maskaccounts.MainUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstancesScreen(
    state: MainUiState,
    onLaunch: (InstanceItem) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
) {
    var sheetTarget by remember { mutableStateOf<InstanceItem?>(null) }
    var renameTarget by remember { mutableStateOf<InstanceItem?>(null) }
    var deleteTarget by remember { mutableStateOf<InstanceItem?>(null) }

    if (state.instances.isEmpty() && !state.isRefreshing) {
        EmptyInstances(onAdd = onAdd)
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 108.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                InstanceSummary(instanceCount = state.instances.size)
            }
            items(state.instances, key = { it.instance.id }) { item ->
                InstanceCard(
                    item = item,
                    isLaunching = state.launchingInstanceId == item.instance.id,
                    onLaunch = { onLaunch(item) },
                    onMore = { sheetTarget = item },
                )
            }
        }
    }

    sheetTarget?.let { item ->
        ModalBottomSheet(onDismissRequest = { sheetTarget = null }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    AppIcon(packageName = item.instance.packageName, size = 48.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.instance.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            item.appLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ListItem(
                    headlineContent = { Text("重新命名") },
                    leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.bottomSheetClickable {
                        sheetTarget = null
                        renameTarget = item
                    },
                )
                ListItem(
                    headlineContent = { Text("刪除分身") },
                    supportingContent = { Text("移除這筆分身與它的 MaskAccounts instance 目錄") },
                    leadingContent = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.bottomSheetClickable {
                        sheetTarget = null
                        deleteTarget = item
                    },
                )
            }
        }
    }

    renameTarget?.let { item ->
        RenameDialog(
            item = item,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                onRename(item.instance.id, name)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("刪除 ${item.instance.displayName}？") },
            text = {
                Text("這會刪除分身記錄與保留的 instance 目錄，且無法復原。來源 App 與其他分身不會被刪除。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete(item.instance.id)
                        deleteTarget = null
                    },
                ) {
                    Text("刪除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun InstanceSummary(instanceCount: Int) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "一點即開，帳號入口更清楚",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "點卡片直接啟動；重新命名與刪除都在更多選單。",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                )
            }
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    instanceCount.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun InstanceCard(
    item: InstanceItem,
    isLaunching: Boolean,
    onLaunch: () -> Unit,
    onMore: () -> Unit,
) {
    val status = when {
        !item.sourceInstalled -> "來源 App 已移除"
        !item.launchSupported -> "尚未完成實機相容驗證"
        else -> "已就緒 · 點一下啟動"
    }
    Card(
        onClick = onLaunch,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, top = 18.dp, bottom = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(packageName = item.instance.packageName, size = 58.dp)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                Text(
                    item.instance.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(item.appLabel)
                        if (item.versionName.isNotBlank()) append(" · ${item.versionName}")
                    },
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (item.launchSupported) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        status,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (item.launchSupported) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            if (isLaunching) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(onClick = onMore) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                }
            }
        }
    }
}

@Composable
private fun EmptyInstances(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            "建立第一個分身",
            modifier = Modifier.padding(top = 24.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "從手機已安裝的 App 選擇來源，MaskAccounts 會先同步程式版本，再建立分身入口。",
            modifier = Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("選擇 App", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun RenameDialog(
    item: InstanceItem,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(item.instance.id) { mutableStateOf(item.instance.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重新命名分身") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("分身名稱") },
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name) },
            ) {
                Text("儲存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun Modifier.bottomSheetClickable(onClick: () -> Unit): Modifier =
    clickable(onClick = onClick)
