package org.maskaccounts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.maskaccounts.GroupAppItem
import org.maskaccounts.GroupItem
import org.maskaccounts.MainUiState
import org.maskaccounts.groups.GoogleServicesState
import org.maskaccounts.groups.GroupHealth

@Composable
fun HomeScreen(
    state: MainUiState,
    onLaunch: (GroupAppItem) -> Unit,
    onAddApp: (String) -> Unit,
    onPrepareGroup: (String) -> Unit,
    onRenameGroup: (String, String) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onCreateGroup: () -> Unit,
) {
    var renameTarget by remember { mutableStateOf<GroupItem?>(null) }
    var deleteTarget by remember { mutableStateOf<GroupItem?>(null) }

    if (state.groups.isEmpty() && !state.isRefreshing) {
        EmptyGroups(onCreateGroup)
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 104.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item { HomeSummary(groupCount = state.groups.size) }
            items(state.groups, key = GroupItem::groupId) { item ->
                GroupCard(
                    item = item,
                    launchingAppKey = state.launchingAppKey,
                    isBusy = state.busyGroupId == item.groupId,
                    onLaunch = onLaunch,
                    onAddApp = { onAddApp(item.groupId) },
                    onPrepare = { onPrepareGroup(item.groupId) },
                    onRename = { renameTarget = item },
                    onDelete = { deleteTarget = item },
                )
            }
        }
    }

    renameTarget?.let { group ->
        RenameGroupDialog(
            group = group,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                onRenameGroup(group.groupId, name)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { group ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("刪除「${group.name}」？") },
            text = {
                Text("這會永久刪除群組內所有 App 資料、Google 帳戶與獨立 GMS 環境。主系統 App 和其他群組不受影響。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteGroup(group.groupId)
                        deleteTarget = null
                    },
                ) { Text("永久刪除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun HomeSummary(groupCount: Int) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "一個群組，一套獨立帳戶",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "App 共用群組內的 Google 服務，但不會看到其他群組的資料。",
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                )
            }
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    groupCount.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun GroupCard(
    item: GroupItem,
    launchingAppKey: String?,
    isBusy: Boolean,
    onLaunch: (GroupAppItem) -> Unit,
    onAddApp: () -> Unit,
    onPrepare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    GroupStatusChip(
                        health = item.health,
                        googleServicesState = item.googleServicesState,
                        canPrepare = item.health == GroupHealth.HEALTHY && item.apps.isNotEmpty(),
                        onPrepare = onPrepare,
                    )
                }
                Box {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(12.dp)
                                .size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "群組選單")
                        }
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("重新命名") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("刪除群組") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
            AppGrid(
                modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp),
                apps = item.apps,
                launchingAppKey = launchingAppKey,
                enabled = item.health == GroupHealth.HEALTHY && !isBusy,
                onLaunch = onLaunch,
                onAddApp = onAddApp,
            )
        }
    }
}

@Composable
private fun GroupStatusChip(
    health: GroupHealth,
    googleServicesState: GoogleServicesState,
    canPrepare: Boolean,
    onPrepare: () -> Unit,
) {
    val visual = when (health) {
        GroupHealth.PROVISIONING -> StatusVisual(
            "正在建立隔離環境",
            Icons.Default.HourglassTop,
        )
        GroupHealth.DAMAGED -> StatusVisual(
            "隔離環境已損毀",
            Icons.Default.Error,
        )
        GroupHealth.DELETING -> StatusVisual(
            "正在刪除群組",
            Icons.Default.HourglassTop,
        )
        GroupHealth.HEALTHY -> when (googleServicesState) {
            GoogleServicesState.NOT_PREPARED -> StatusVisual(
            "Google 服務尚未準備",
            Icons.Default.HourglassTop,
        )
            GoogleServicesState.PREPARING -> StatusVisual(
            "正在準備 Google 服務",
            Icons.Default.HourglassTop,
        )
            GoogleServicesState.READY -> StatusVisual(
            "Google 服務就緒",
            Icons.Default.CheckCircle,
        )
            GoogleServicesState.FAILED -> StatusVisual(
            "準備失敗 · 點此重試",
            Icons.Default.Refresh,
        )
        }
    }
    AssistChip(
        modifier = Modifier.padding(top = 8.dp),
        onClick = {
            if (
                canPrepare &&
                googleServicesState in setOf(
                    GoogleServicesState.NOT_PREPARED,
                    GoogleServicesState.FAILED,
                )
            ) {
                onPrepare()
            }
        },
        enabled = health == GroupHealth.HEALTHY &&
            googleServicesState != GoogleServicesState.PREPARING,
        leadingIcon = {
            if (
                health == GroupHealth.PROVISIONING ||
                health == GroupHealth.DELETING ||
                googleServicesState == GoogleServicesState.PREPARING
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(visual.icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        },
        label = { Text(visual.label) },
    )
}

@Composable
private fun AppGrid(
    modifier: Modifier,
    apps: List<GroupAppItem>,
    launchingAppKey: String?,
    enabled: Boolean,
    onLaunch: (GroupAppItem) -> Unit,
    onAddApp: () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= 760.dp -> 6
            maxWidth >= 560.dp -> 4
            else -> 3
        }
        val cells: List<@Composable () -> Unit> = apps.map { app ->
            @Composable {
                AppGridTile(
                    app = app,
                    isLaunching = launchingAppKey == app.launchKey,
                    enabled = enabled,
                    onClick = { onLaunch(app) },
                )
            }
        } + listOf<@Composable () -> Unit>({ AddAppTile(enabled, onAddApp) })
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cells.chunked(columns).forEach { rowCells ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowCells.forEach { cell ->
                        Box(modifier = Modifier.weight(1f)) { cell() }
                    }
                    repeat(columns - rowCells.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun AppGridTile(
    app: GroupAppItem,
    isLaunching: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.82f)
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            AppIcon(packageName = app.app.packageName, size = 52.dp)
            if (isLaunching) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.52f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        Text(
            app.appLabel,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AddAppTile(enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.82f)
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Text(
            "加入 App",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EmptyGroups(onCreateGroup: () -> Unit) {
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
            "建立第一個群組",
            modifier = Modifier.padding(top = 24.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "每個群組都有獨立的 Google 帳戶與 App 資料。建立本身很快，環境會在加入 App 後準備。",
            modifier = Modifier.padding(top = 10.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(
            modifier = Modifier.padding(top = 24.dp),
            onClick = onCreateGroup,
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("新增群組", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun RenameGroupDialog(
    group: GroupItem,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(group.groupId) { mutableStateOf(group.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重新命名群組") },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("群組名稱") },
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) {
                Text("儲存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private data class StatusVisual(val label: String, val icon: ImageVector)
