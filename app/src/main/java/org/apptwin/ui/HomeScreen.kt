package org.apptwin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.apptwin.GroupAppItem
import org.apptwin.GroupItem
import org.apptwin.MainUiState
import org.apptwin.groups.GroupHealth

@Composable
fun HomeScreen(
    state: MainUiState,
    onLaunch: (GroupAppItem) -> Unit,
    onAddApp: (String) -> Unit,
    onRenameGroup: (String, String) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onUninstallApp: (GroupAppItem) -> Unit,
    onCreateGroup: () -> Unit,
) {
    var renameTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var uninstallTargetKey by rememberSaveable { mutableStateOf<String?>(null) }
    val renameTarget = state.groups.firstOrNull { it.groupId == renameTargetId }
    val deleteTarget = state.groups.firstOrNull { it.groupId == deleteTargetId }
    val uninstallTarget = state.groups.asSequence()
        .flatMap { it.apps.asSequence() }
        .firstOrNull { it.launchKey == uninstallTargetKey }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.dataWarnings.isNotEmpty()) {
            DataIntegrityWarning(
                count = state.dataWarnings.size,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
            )
        }
        Box(modifier = Modifier.weight(1f)) {
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
                            uninstallingAppKey = state.uninstallingAppKey,
                            isAnyLaunchBusy = state.launchingAppKey != null ||
                                state.uninstallingAppKey != null,
                            isBusy = state.busyGroupId == item.groupId ||
                                state.uninstallingAppKey?.startsWith("${item.groupId}:") == true,
                            onLaunch = onLaunch,
                            onAddApp = { onAddApp(item.groupId) },
                            onRename = { renameTargetId = item.groupId },
                            onDelete = { deleteTargetId = item.groupId },
                            onUninstallApp = { uninstallTargetKey = it.launchKey },
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { group ->
        RenameGroupDialog(
            group = group,
            onDismiss = { renameTargetId = null },
            onConfirm = { name ->
                onRenameGroup(group.groupId, name)
                renameTargetId = null
            },
        )
    }

    deleteTarget?.let { group ->
        AlertDialog(
            onDismissRequest = { deleteTargetId = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("刪除「${group.name}」？") },
            text = {
                Text("這會永久刪除群組內所有 App 資料與隔離環境。主系統 App 和其他群組不受影響。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteGroup(group.groupId)
                        deleteTargetId = null
                    },
                ) { Text("永久刪除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetId = null }) { Text("取消") }
            },
        )
    }

    uninstallTarget?.let { app ->
        AlertDialog(
            modifier = Modifier.testTag("uninstall-app-dialog"),
            onDismissRequest = { uninstallTargetKey = null },
            icon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("解除安裝「${app.appLabel}」？") },
            text = {
                Text(
                    "只會從「${app.groupName}」Group 移除此 App，並永久刪除它在此 Group 內的所有私有資料。主系統 App 與其他 Group 不受影響；此操作無法復原。",
                )
            },
            confirmButton = {
                Button(
                    modifier = Modifier.testTag("confirm-uninstall-app"),
                    onClick = {
                        onUninstallApp(app)
                        uninstallTargetKey = null
                    },
                ) { Text("解除安裝") }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.testTag("cancel-uninstall-app"),
                    onClick = { uninstallTargetKey = null },
                ) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DataIntegrityWarning(count: Int, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("data-integrity-warning"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Error, contentDescription = null)
            Column {
                Text("偵測到 $count 筆資料完整性問題", fontWeight = FontWeight.Bold)
                Text("無法讀取的原始資料已保留，請勿重建同名群組或覆寫 revision。")
            }
        }
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
                    "同一群組內的 App 共用帳戶環境，但不會看到其他群組的資料。",
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
    uninstallingAppKey: String?,
    isAnyLaunchBusy: Boolean,
    isBusy: Boolean,
    onLaunch: (GroupAppItem) -> Unit,
    onAddApp: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onUninstallApp: (GroupAppItem) -> Unit,
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
                    GroupStatusChip(health = item.health)
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
                uninstallingAppKey = uninstallingAppKey,
                enabled = item.health == GroupHealth.HEALTHY &&
                    !isBusy &&
                    !isAnyLaunchBusy &&
                    uninstallingAppKey == null,
                onLaunch = onLaunch,
                onUninstall = onUninstallApp,
                onAddApp = onAddApp,
            )
        }
    }
}

@Composable
private fun GroupStatusChip(health: GroupHealth) {
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
        GroupHealth.HEALTHY -> StatusVisual(
            "隔離環境就緒",
            Icons.Default.CheckCircle,
        )
    }
    AssistChip(
        modifier = Modifier.padding(top = 8.dp),
        onClick = {},
        enabled = false,
        leadingIcon = {
            if (health == GroupHealth.PROVISIONING || health == GroupHealth.DELETING) {
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
    uninstallingAppKey: String?,
    enabled: Boolean,
    onLaunch: (GroupAppItem) -> Unit,
    onUninstall: (GroupAppItem) -> Unit,
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
                    isUninstalling = uninstallingAppKey == app.launchKey,
                    enabled = enabled && launchingAppKey == null,
                    onClick = { onLaunch(app) },
                    onUninstall = { onUninstall(app) },
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
@OptIn(ExperimentalFoundationApi::class)
private fun AppGridTile(
    app: GroupAppItem,
    isLaunching: Boolean,
    isUninstalling: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onUninstall: () -> Unit,
) {
    var menuExpanded by remember(app.launchKey) { mutableStateOf(false) }
    val busy = isLaunching || isUninstalling
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.82f)
                .clip(MaterialTheme.shapes.medium)
                .semantics {
                    stateDescription = when {
                        isUninstalling -> "解除安裝中"
                        isLaunching -> "開啟中"
                        !enabled -> "暫時無法操作"
                        else -> "可操作"
                    }
                }
                .testTag("group-app-tile-${app.launchKey}")
                .combinedClickable(
                    enabled = enabled && !busy,
                    role = Role.Button,
                    onClickLabel = "開啟 ${app.appLabel}",
                    onLongClickLabel = "開啟 App 選單",
                    onLongClick = { menuExpanded = true },
                    onClick = onClick,
                )
                .padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                AppIcon(packageName = app.app.packageName, size = 52.dp)
                if (busy) {
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
                if (isUninstalling) "解除安裝中…" else app.appLabel,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(
            modifier = Modifier.testTag("group-app-menu-${app.launchKey}"),
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                modifier = Modifier.testTag("uninstall-app-${app.launchKey}"),
                text = {
                    Text(
                        "解除安裝",
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                enabled = enabled && !busy,
                onClick = {
                    menuExpanded = false
                    onUninstall()
                },
            )
        }
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
            "每個群組都有獨立的帳戶環境與 App 資料。建立後即可加入 App。",
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
    var name by rememberSaveable(group.groupId) { mutableStateOf(group.name) }
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
