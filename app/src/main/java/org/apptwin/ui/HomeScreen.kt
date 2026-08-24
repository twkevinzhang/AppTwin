package org.apptwin.ui

import android.Manifest
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
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
import org.apptwin.GmsBusyAction
import org.apptwin.MainUiState
import org.apptwin.spaces.CloneLifecycleState
import org.apptwin.spaces.SpaceLifecycleState
import org.apptwin.gms.model.GmsDesiredState
import org.apptwin.gms.model.GmsNetworkConsent

@Composable
fun HomeScreen(
    state: MainUiState,
    onCreateGroup: () -> Unit,
    onLaunch: (GroupAppItem) -> Unit = {},
    onAddApp: (String) -> Unit = {},
    onRenameSpace: (String, String) -> Unit = { _, _ -> },
    onDeleteSpace: (String) -> Unit = {},
    onEnableGms: (String, Boolean) -> Unit = { _, _ -> },
    onDisableGms: (String) -> Unit = {},
    onClearAllAppData: (String) -> Unit = {},
    onUninstallApp: (GroupAppItem) -> Unit = {},
    onCreateShortcut: (GroupAppItem) -> Unit = {},
    onRepairApp: (GroupAppItem) -> Unit = {},
    clearingStorageAppKey: String? = null,
    onClearStorage: (GroupAppItem) -> Unit = {},
    onSetPermission: (GroupAppItem, String, Boolean) -> Unit = { _, _, _ -> },
) {
    var collapsedGroupIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var renameGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var clearAllGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var uninstallTargetKey by rememberSaveable { mutableStateOf<String?>(null) }
    var clearStorageTargetKey by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionTargetKey by rememberSaveable { mutableStateOf<String?>(null) }
    var gmsConsentGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var gmsDisableGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    val visibleGroupIds = state.groups.map(GroupItem::groupId).toSet()
    val allExpanded = state.groups.isNotEmpty() &&
        collapsedGroupIds.none { it in visibleGroupIds }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.dataWarnings.isNotEmpty()) {
            DataIntegrityWarning(
                count = state.dataWarnings.size,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
            )
        }
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val spaceColumns = if (maxWidth >= 720.dp) 2 else 1
            if (state.groups.isEmpty() && !state.isRefreshing) {
                EmptyGroups(onCreateGroup)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 104.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    item { HomeSummary(groupCount = state.groups.size) }
                    item {
                        TextButton(
                            modifier = Modifier.testTag("expand-all-spaces"),
                            onClick = {
                                collapsedGroupIds = if (allExpanded) {
                                    state.groups.map(GroupItem::groupId)
                                } else {
                                    emptyList()
                                }
                            },
                        ) {
                            Icon(
                                if (allExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                            )
                            Text(
                                if (allExpanded) "全部收合" else "全部展開",
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    items(
                        items = state.groups.chunked(spaceColumns),
                        key = { row -> row.joinToString(":", transform = GroupItem::groupId) },
                    ) { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            rowItems.forEach { item ->
                                Box(modifier = Modifier.weight(1f)) {
                                    ExpandableSpaceCard(
                                        item = item,
                                        expanded = item.groupId !in collapsedGroupIds,
                                        isBusy = state.busyGroupId == item.groupId ||
                                            state.gmsBusyGroupId == item.groupId ||
                                            state.clearingStorageGroupId == item.groupId ||
                                            state.uninstallingAppKey
                                                ?.startsWith("${item.groupId}:") == true,
                                        gmsBusyMessage = if (
                                            state.gmsBusyGroupId == item.groupId
                                        ) {
                                            gmsBusyMessage(state.gmsBusyAction)
                                        } else {
                                            null
                                        },
                                        launchingAppKey = state.launchingAppKey,
                                        uninstallingAppKey = state.uninstallingAppKey,
                                        shortcutAppKey = state.shortcutAppKey,
                                        repairingAppKey = state.repairingAppKey,
                                        onToggleExpanded = {
                                            collapsedGroupIds = if (item.groupId in collapsedGroupIds) {
                                                collapsedGroupIds - item.groupId
                                            } else {
                                                collapsedGroupIds + item.groupId
                                            }
                                        },
                                        onLaunch = onLaunch,
                                        onAddApp = { onAddApp(item.groupId) },
                                        onRename = { renameGroupId = item.groupId },
                                        onDelete = { deleteGroupId = item.groupId },
                                        onEnableGms = { grantConsent ->
                                            if (grantConsent) gmsConsentGroupId = item.groupId
                                            else onEnableGms(item.groupId, false)
                                        },
                                        onDisableGms = { gmsDisableGroupId = item.groupId },
                                        onClearAllAppData = { clearAllGroupId = item.groupId },
                                        onUninstall = { uninstallTargetKey = it.launchKey },
                                        onCreateShortcut = onCreateShortcut,
                                        onRepair = onRepairApp,
                                        onClearStorage = { clearStorageTargetKey = it.launchKey },
                                        onManagePermissions = { permissionTargetKey = it.launchKey },
                                        clearingStorageAppKey = clearingStorageAppKey,
                                    )
                                }
                            }
                            repeat(spaceColumns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }

    val renamedGroup = state.groups.firstOrNull { it.groupId == renameGroupId }
    val deleteGroup = state.groups.firstOrNull { it.groupId == deleteGroupId }
    val clearAllGroup = state.groups.firstOrNull { it.groupId == clearAllGroupId }
    val consentGroup = state.groups.firstOrNull { it.groupId == gmsConsentGroupId }
    val disableGroup = state.groups.firstOrNull { it.groupId == gmsDisableGroupId }
    val allApps = state.groups.flatMap(GroupItem::apps)
    val uninstallTarget = allApps.firstOrNull { it.launchKey == uninstallTargetKey }
    val clearStorageTarget = allApps.firstOrNull { it.launchKey == clearStorageTargetKey }
    val permissionTarget = allApps.firstOrNull { it.launchKey == permissionTargetKey }

    renamedGroup?.let { group ->
        RenameGroupDialog(
            group = group,
            onDismiss = { renameGroupId = null },
            onConfirm = { name ->
                onRenameSpace(group.groupId, name)
                renameGroupId = null
            },
        )
    }
    deleteGroup?.let { group ->
        DeleteSpaceDialog(
            group = group,
            onDismiss = { deleteGroupId = null },
            onConfirm = {
                onDeleteSpace(group.groupId)
                deleteGroupId = null
            },
        )
    }
    consentGroup?.let { group ->
        GmsEnableConsentDialog(
            group = group,
            onDismiss = { gmsConsentGroupId = null },
            onConfirm = {
                onEnableGms(group.groupId, true)
                gmsConsentGroupId = null
            },
        )
    }
    disableGroup?.let { group ->
        GmsDisableDialog(
            onDismiss = { gmsDisableGroupId = null },
            onConfirm = {
                onDisableGms(group.groupId)
                gmsDisableGroupId = null
            },
        )
    }
    clearAllGroup?.let { group ->
        val isClearing = state.clearingStorageGroupId == group.groupId
        var clearWasInProgress by rememberSaveable(group.groupId) { mutableStateOf(false) }
        LaunchedEffect(isClearing) {
            if (isClearing) {
                clearWasInProgress = true
            } else if (clearWasInProgress) {
                clearWasInProgress = false
                clearAllGroupId = null
            }
        }
        ClearAllSpaceDataDialog(
            group = group,
            isClearing = isClearing,
            onDismiss = {
                if (state.clearingStorageGroupId != group.groupId) clearAllGroupId = null
            },
            onConfirm = { onClearAllAppData(group.groupId) },
        )
    }
    uninstallTarget?.let { app ->
        UninstallAppDialog(
            app = app,
            onDismiss = { uninstallTargetKey = null },
            onConfirm = {
                onUninstallApp(app)
                uninstallTargetKey = null
            },
        )
    }
    clearStorageTarget?.let { app ->
        ClearAppStorageDialog(
            app = app,
            isClearingStorage = clearingStorageAppKey == app.launchKey,
            onDismiss = { if (clearingStorageAppKey != app.launchKey) clearStorageTargetKey = null },
            onConfirm = { onClearStorage(app) },
        )
    }
    permissionTarget?.let { app ->
        ClonePermissionDialog(
            app = app,
            onDismiss = { permissionTargetKey = null },
            onSetPermission = { permission, granted -> onSetPermission(app, permission, granted) },
        )
    }
}

@Composable
private fun GmsEnableConsentDialog(
    group: GroupItem,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("gms-consent-dialog"),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Cloud, contentDescription = null) },
        title = { Text("啟用 Google 服務相容功能？") },
        text = {
            Text(
                "此功能由 microG 提供，並非 Google 官方服務。啟用後，只有「${group.name}」會連線至 Google 的裝置註冊、帳號及推播端點；帳號、token 與資料不會與其他空間共用。",
            )
        },
        confirmButton = {
            Button(modifier = Modifier.testTag("confirm-gms-consent"), onClick = onConfirm) {
                Text("同意並啟用")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun GmsDisableDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag("gms-disable-dialog"),
        onDismissRequest = onDismiss,
        title = { Text("停用相容功能？") },
        text = { Text("將停止此空間的 microG 背景服務；既有相容服務資料會保留，之後可重新啟用。") },
        confirmButton = {
            Button(modifier = Modifier.testTag("confirm-gms-disable"), onClick = onConfirm) {
                Text("停用")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun DeleteSpaceDialog(
    group: GroupItem,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("delete-space-dialog"),
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text("刪除「${group.name}」？") },
        text = {
            Text(
                "將永久刪除此空間、${group.apps.size} 個分身 App，以及它們的登入、App 資料與 Google 服務相容資料；" +
                    "對應桌面捷徑會停用。手機上的原始 App 和其他分身空間不受影響，此操作無法復原。",
            )
        },
        confirmButton = {
            Button(
                modifier = Modifier.testTag("confirm-delete-space"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                onClick = onConfirm,
            ) { Text("永久刪除") }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag("cancel-delete-space"),
                onClick = onDismiss,
            ) { Text("取消") }
        },
    )
}

@Composable
private fun ClearAllSpaceDataDialog(
    group: GroupItem,
    isClearing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("clear-all-space-data-dialog"),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("清除「${group.name}」的所有 App 資料？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("將依序停止並永久清除這個空間中 ${group.apps.size} 個分身 App 的登入、App 資料、快取與各自私有外部檔案。")
                Text(
                    "App 本體、此空間與共用檔案，以及 Google/microG 資料都會保留。若中途失敗，已完成清除的 App 無法還原。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                modifier = Modifier.testTag("confirm-clear-all-space-data"),
                enabled = !isClearing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                onClick = onConfirm,
            ) { Text(if (isClearing) "清除中…" else "清除全部資料") }
        },
        dismissButton = {
            TextButton(enabled = !isClearing, onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun UninstallAppDialog(app: GroupAppItem, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag("uninstall-app-dialog"),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("解除安裝「${app.appLabel}」？") },
        text = {
            Text(
                "只會從「${app.groupName}」移除此分身 App，並永久刪除它在此空間的登入與所有資料。手機上的原始 App 和其他分身空間不受影響；此操作無法復原。",
            )
        },
        confirmButton = { Button(modifier = Modifier.testTag("confirm-uninstall-app"), onClick = onConfirm) { Text("解除安裝") } },
        dismissButton = { TextButton(modifier = Modifier.testTag("cancel-uninstall-app"), onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ClearAppStorageDialog(
    app: GroupAppItem,
    isClearingStorage: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("clear-storage-dialog"),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("清除「${app.appLabel}」的儲存空間？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("將先停止此分身 App，接著永久刪除登入、App 資料、快取及該分身可歸屬的私有外部檔案。")
                Text(
                    "同一空間內與其他分身共用的檔案不會清除。手機上的原始 App 和其他分身空間不受影響；此操作無法復原。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                modifier = Modifier.testTag("confirm-clear-storage"),
                enabled = !isClearingStorage,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                onClick = onConfirm,
            ) { Text(if (isClearingStorage) "清除中…" else "清除儲存空間") }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.testTag("cancel-clear-storage"),
                enabled = !isClearingStorage,
                onClick = onDismiss,
            ) { Text("取消") }
        },
    )
}

@Composable
private fun ClonePermissionDialog(
    app: GroupAppItem,
    onDismiss: () -> Unit,
    onSetPermission: (String, Boolean) -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("clone-permission-dialog"),
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Security, contentDescription = null) },
        title = { Text("${app.appLabel} 的空間權限") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "App 所見的權限決策只套用於「${app.groupName}」。手機上的原始 App 和其他分身空間不受影響；實際相機／麥克風能力仍可能因 App 與裝置而異。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PermissionToggle(
                    label = "相機",
                    checked = app.cameraGranted,
                    onCheckedChange = { onSetPermission(Manifest.permission.CAMERA, it) },
                )
                PermissionToggle(
                    label = "麥克風",
                    checked = app.microphoneGranted,
                    onCheckedChange = {
                        onSetPermission(Manifest.permission.RECORD_AUDIO, it)
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun PermissionToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
                Text("偵測到 $count 筆空間資料問題", fontWeight = FontWeight.Bold)
                Text("無法讀取的原始資料已保留。請先修復，避免覆寫仍可救回的資料。")
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
                    "一個空間，一套獨立身分",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "將工作、私人或購物帳號分開，手機上的原始 App 不受影響。",
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
private fun ExpandableSpaceCard(
    item: GroupItem,
    expanded: Boolean,
    isBusy: Boolean,
    gmsBusyMessage: String?,
    launchingAppKey: String?,
    uninstallingAppKey: String?,
    shortcutAppKey: String?,
    repairingAppKey: String?,
    onToggleExpanded: () -> Unit,
    onLaunch: (GroupAppItem) -> Unit,
    onAddApp: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onEnableGms: (requiresConsent: Boolean) -> Unit,
    onDisableGms: () -> Unit,
    onClearAllAppData: () -> Unit,
    onUninstall: (GroupAppItem) -> Unit,
    onCreateShortcut: (GroupAppItem) -> Unit,
    onRepair: (GroupAppItem) -> Unit,
    onClearStorage: (GroupAppItem) -> Unit,
    onManagePermissions: (GroupAppItem) -> Unit,
    clearingStorageAppKey: String?,
) {
    var menuExpanded by rememberSaveable(item.groupId) { mutableStateOf(false) }
    val gmsState = item.gmsCompatibility
    val gmsEnabled = gmsState?.profile?.desiredState == GmsDesiredState.ENABLED
    val gmsActionEnabled = !isBusy && gmsState?.hasDataWarning != true
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        item.name.trim().take(1).ifEmpty { "空" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${item.apps.size} 個 App",
                        modifier = Modifier.padding(top = 3.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isBusy && gmsBusyMessage == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(
                        modifier = Modifier.testTag("space-expand-${item.groupId}"),
                        onClick = onToggleExpanded,
                    ) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) {
                                "收合 ${item.name}"
                            } else {
                                "展開 ${item.name}"
                            },
                        )
                    }
                }
                Box {
                    IconButton(
                        modifier = Modifier.testTag("space-manage-${item.groupId}"),
                        enabled = !isBusy,
                        onClick = { menuExpanded = true },
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "管理 ${item.name}")
                    }
                    SpaceManagementMenu(
                        item = item,
                        expanded = menuExpanded,
                        gmsEnabled = gmsEnabled,
                        gmsActionEnabled = gmsActionEnabled,
                        gmsRequiresConsent =
                            gmsState?.profile?.networkConsent != GmsNetworkConsent.GRANTED,
                        onDismiss = { menuExpanded = false },
                        onRename = onRename,
                        onEnableGms = onEnableGms,
                        onDisableGms = onDisableGms,
                        onClearAllAppData = onClearAllAppData,
                        onDelete = onDelete,
                    )
                }
            }
            gmsBusyMessage?.let { message ->
                Row(
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .testTag("gms-busy-space-${item.groupId}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            SpaceStatusChip(lifecycle = item.lifecycle)
            if (expanded) {
                if (item.apps.isEmpty()) {
                    Button(
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .testTag("home-add-app-${item.groupId}"),
                        onClick = onAddApp,
                        enabled = item.lifecycle == SpaceLifecycleState.READY && !isBusy,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("加入 App", modifier = Modifier.padding(start = 8.dp))
                    }
                } else {
                    HomeAppGrid(
                        modifier = Modifier.padding(top = 16.dp),
                        groupId = item.groupId,
                        apps = item.apps,
                        launchingAppKey = launchingAppKey,
                        uninstallingAppKey = uninstallingAppKey,
                        shortcutAppKey = shortcutAppKey,
                        repairingAppKey = repairingAppKey,
                        clearingStorageAppKey = clearingStorageAppKey,
                        enabled = item.lifecycle == SpaceLifecycleState.READY && !isBusy,
                        onLaunch = onLaunch,
                        onUninstall = onUninstall,
                        onCreateShortcut = onCreateShortcut,
                        onRepair = onRepair,
                        onClearStorage = onClearStorage,
                        onManagePermissions = onManagePermissions,
                        onAddApp = onAddApp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpaceManagementMenu(
    item: GroupItem,
    expanded: Boolean,
    gmsEnabled: Boolean,
    gmsActionEnabled: Boolean,
    gmsRequiresConsent: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onEnableGms: (requiresConsent: Boolean) -> Unit,
    onDisableGms: () -> Unit,
    onClearAllAppData: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            modifier = Modifier.testTag("rename-space-${item.groupId}"),
            text = { Text("重新命名空間") },
            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
            onClick = {
                onDismiss()
                onRename()
            },
        )
        DropdownMenuItem(
            modifier = Modifier.testTag("gms-toggle-space-${item.groupId}"),
            text = { Text(if (gmsEnabled) "停用 Google 服務" else "啟用 Google 服務") },
            leadingIcon = { Icon(Icons.Default.Cloud, contentDescription = null) },
            enabled = gmsActionEnabled,
            onClick = {
                onDismiss()
                if (gmsEnabled) onDisableGms() else onEnableGms(gmsRequiresConsent)
            },
        )
        DropdownMenuItem(
            modifier = Modifier.testTag("clear-all-space-data-${item.groupId}"),
            text = { Text("清除空間所有 App 資料", color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            enabled = item.apps.isNotEmpty(),
            onClick = {
                onDismiss()
                onClearAllAppData()
            },
        )
        DropdownMenuItem(
            modifier = Modifier.testTag("delete-space-${item.groupId}"),
            text = { Text("刪除空間", color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            onClick = {
                onDismiss()
                onDelete()
            },
        )
    }
}

private fun gmsBusyMessage(action: GmsBusyAction?): String = when (action) {
    GmsBusyAction.ENABLE -> "正在啟用 Google 服務…"
    GmsBusyAction.DISABLE -> "正在停用 Google 服務…"
    GmsBusyAction.RESET -> "正在重設 Google 服務…"
    null -> "正在處理 Google 服務…"
}

@Composable
private fun HomeAppGrid(
    modifier: Modifier,
    groupId: String,
    apps: List<GroupAppItem>,
    launchingAppKey: String?,
    uninstallingAppKey: String?,
    shortcutAppKey: String?,
    repairingAppKey: String?,
    clearingStorageAppKey: String?,
    enabled: Boolean,
    onLaunch: (GroupAppItem) -> Unit,
    onUninstall: (GroupAppItem) -> Unit,
    onCreateShortcut: (GroupAppItem) -> Unit,
    onRepair: (GroupAppItem) -> Unit,
    onClearStorage: (GroupAppItem) -> Unit,
    onManagePermissions: (GroupAppItem) -> Unit,
    onAddApp: () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= 760.dp -> 6
            maxWidth >= 560.dp -> 4
            else -> 3
        }
        val cells = buildList<GroupAppItem?> {
            addAll(apps)
            add(null)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cells.chunked(columns).forEach { rowCells ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowCells.forEach { app ->
                        if (app == null) {
                            AddAppTile(
                                enabled = enabled,
                                onClick = onAddApp,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("home-add-app-$groupId"),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("home-app-tile-${app.launchKey}"),
                            ) {
                                AppGridTile(
                                    app = app,
                                    isLaunching = launchingAppKey == app.launchKey,
                                    isUninstalling = uninstallingAppKey == app.launchKey,
                                    isCreatingShortcut = shortcutAppKey == app.launchKey,
                                    isRepairing = repairingAppKey == app.launchKey,
                                    isClearingStorage = clearingStorageAppKey == app.launchKey,
                                    enabled = enabled && launchingAppKey == null,
                                    onClick = { onLaunch(app) },
                                    onUninstall = { onUninstall(app) },
                                    onCreateShortcut = { onCreateShortcut(app) },
                                    onRepair = { onRepair(app) },
                                    onClearStorage = { onClearStorage(app) },
                                    onManagePermissions = { onManagePermissions(app) },
                                )
                            }
                        }
                    }
                    repeat(columns - rowCells.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SpaceStatusChip(lifecycle: SpaceLifecycleState) {
    val visual = when (lifecycle) {
        SpaceLifecycleState.CREATING -> StatusVisual(
            "空間建立中",
            Icons.Default.HourglassTop,
        )
        SpaceLifecycleState.NEEDS_REPAIR -> StatusVisual(
            "空間需要修復",
            Icons.Default.Error,
        )
        SpaceLifecycleState.REPAIRING -> StatusVisual(
            "空間修復中",
            Icons.Default.HourglassTop,
        )
        SpaceLifecycleState.DELETING -> StatusVisual(
            "空間刪除中",
            Icons.Default.HourglassTop,
        )
        SpaceLifecycleState.READY -> StatusVisual(
            "可使用",
            Icons.Default.CheckCircle,
        )
    }
    AssistChip(
        modifier = Modifier.padding(top = 8.dp),
        onClick = {},
        enabled = false,
        leadingIcon = {
            if (lifecycle in setOf(
                    SpaceLifecycleState.CREATING,
                    SpaceLifecycleState.REPAIRING,
                    SpaceLifecycleState.DELETING,
                )
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
@OptIn(ExperimentalFoundationApi::class)
private fun AppGridTile(
    app: GroupAppItem,
    isLaunching: Boolean,
    isUninstalling: Boolean,
    isCreatingShortcut: Boolean,
    isRepairing: Boolean,
    isClearingStorage: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onUninstall: () -> Unit,
    onCreateShortcut: () -> Unit,
    onRepair: () -> Unit,
    onClearStorage: () -> Unit,
    onManagePermissions: () -> Unit,
) {
    var menuExpanded by remember(app.launchKey) { mutableStateOf(false) }
    val busy = isLaunching || isUninstalling || isCreatingShortcut || isRepairing || isClearingStorage
    val launchEnabled = enabled && app.sourceInstalled && !busy
    val menuEnabled = enabled && !busy
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
                        isCreatingShortcut -> "建立捷徑中"
                        isRepairing -> "重新同步中"
                        isClearingStorage -> "清除儲存空間中"
                        !app.sourceInstalled -> "原始 App 已移除"
                        !enabled -> "暫時無法操作"
                        else -> "可操作"
                    }
                }
                .testTag("group-app-tile-${app.launchKey}")
                .combinedClickable(
                    enabled = menuEnabled,
                    role = Role.Button,
                    onClickLabel = "開啟 ${app.appLabel}",
                    onLongClickLabel = "開啟 App 選單",
                    onLongClick = { menuExpanded = true },
                    onClick = { if (launchEnabled) onClick() },
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
                app.appLabel,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    isUninstalling -> "移除中…"
                    isCreatingShortcut -> "建立捷徑中…"
                    isRepairing -> "重新同步中…"
                    isClearingStorage -> "清除儲存空間中…"
                    !app.sourceInstalled -> "原始 App 已移除"
                    isLaunching -> "開啟中…"
                    app.lifecycle == CloneLifecycleState.READY -> app.launchStatus
                    else -> cloneLifecycleLabel(app.lifecycle)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (!app.sourceInstalled) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(
            modifier = Modifier.testTag("group-app-menu-${app.launchKey}"),
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                modifier = Modifier.testTag("permissions-app-${app.launchKey}"),
                text = { Text("空間權限") },
                leadingIcon = { Icon(Icons.Default.Security, contentDescription = null) },
                enabled = menuEnabled && app.sourceInstalled,
                onClick = {
                    menuExpanded = false
                    onManagePermissions()
                },
            )
            DropdownMenuItem(
                modifier = Modifier.testTag("repair-app-${app.launchKey}"),
                text = { Text("重新同步 App") },
                leadingIcon = { Icon(Icons.Default.Build, contentDescription = null) },
                enabled = menuEnabled,
                onClick = {
                    menuExpanded = false
                    onRepair()
                },
            )
            DropdownMenuItem(
                modifier = Modifier.testTag("shortcut-app-${app.launchKey}"),
                text = { Text("建立桌面捷徑") },
                leadingIcon = { Icon(Icons.Default.Home, contentDescription = null) },
                enabled = menuEnabled && app.sourceInstalled,
                onClick = {
                    menuExpanded = false
                    onCreateShortcut()
                },
            )
            DropdownMenuItem(
                modifier = Modifier.testTag("clear-storage-app-${app.launchKey}"),
                text = {
                    Text(
                        "清除儲存空間",
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
                enabled = menuEnabled,
                onClick = {
                    menuExpanded = false
                    onClearStorage()
                },
            )
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
                enabled = menuEnabled,
                onClick = {
                    menuExpanded = false
                    onUninstall()
                },
            )
        }
    }
}

private fun cloneLifecycleLabel(lifecycle: CloneLifecycleState): String = when (lifecycle) {
    CloneLifecycleState.PREPARING -> "準備中"
    CloneLifecycleState.READY -> "可使用"
    CloneLifecycleState.LAUNCHING -> "開啟中"
    CloneLifecycleState.UPDATING -> "更新中"
    CloneLifecycleState.SOURCE_MISSING -> "原始 App 已移除"
    CloneLifecycleState.UNSUPPORTED -> "暫不支援"
    CloneLifecycleState.NEEDS_REPAIR -> "需要修復"
    CloneLifecycleState.REPAIRING -> "修復中"
    CloneLifecycleState.REMOVING -> "移除中"
}

@Composable
private fun AddAppTile(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
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
            "建立第一個分身空間",
            modifier = Modifier.padding(top = 24.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "每個空間都有獨立的登入與 App 資料，手機上的原始 App 不受影響。",
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
            Text("建立空間", modifier = Modifier.padding(start = 8.dp))
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
    val trimmedName = name.trim()
    val nameError = when {
        name.isNotEmpty() && trimmedName.isEmpty() -> "名稱不能只有空白"
        trimmedName.length > 40 -> "名稱最多 40 個字元"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重新命名空間") },
        text = {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("空間名稱") },
                isError = nameError != null,
                supportingText = nameError?.let { message -> { Text(message) } },
            )
        },
        confirmButton = {
            TextButton(
                enabled = trimmedName.isNotEmpty() && trimmedName.length <= 40,
                onClick = { onConfirm(trimmedName) },
            ) {
                Text("儲存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private data class StatusVisual(val label: String, val icon: ImageVector)
