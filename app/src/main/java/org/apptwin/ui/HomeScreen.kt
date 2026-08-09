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
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Cloud
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
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
import org.apptwin.spaces.CloneLifecycleState
import org.apptwin.spaces.SpaceLifecycleState
import org.apptwin.gms.capabilities.GmsCapability
import org.apptwin.gms.capabilities.GmsCapabilityAssessment
import org.apptwin.gms.capabilities.GmsCapabilityStatus
import org.apptwin.gms.capabilities.GmsEvidenceTier
import org.apptwin.gms.model.GmsDesiredState
import org.apptwin.gms.model.GmsNetworkConsent
import org.apptwin.gms.model.GmsObservedState

@Composable
fun HomeScreen(
    state: MainUiState,
    onOpenSpace: (String) -> Unit,
    onCreateGroup: () -> Unit,
) {
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
                                    SpaceSummaryCard(
                                        item = item,
                                        isBusy = state.busyGroupId == item.groupId ||
                                            state.uninstallingAppKey
                                                ?.startsWith("${item.groupId}:") == true,
                                        onClick = { onOpenSpace(item.groupId) },
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
}

@Composable
fun SpaceDetailScreen(
    state: MainUiState,
    space: GroupItem,
    onLaunch: (GroupAppItem) -> Unit,
    onAddApp: (String) -> Unit,
    onRenameSpace: (String, String) -> Unit,
    onDeleteSpace: (String) -> Unit,
    onUninstallApp: (GroupAppItem) -> Unit,
    onCreateShortcut: (GroupAppItem) -> Unit,
    onRepairApp: (GroupAppItem) -> Unit,
    onSetPermission: (GroupAppItem, String, Boolean) -> Unit,
    onEnableGms: (String, Boolean) -> Unit = { _, _ -> },
    onDisableGms: (String) -> Unit = {},
    onResetGms: (String, Boolean) -> Unit = { _, _ -> },
) {
    var showRename by rememberSaveable(space.groupId) { mutableStateOf(false) }
    var showDelete by rememberSaveable(space.groupId) { mutableStateOf(false) }
    var uninstallTargetKey by rememberSaveable(space.groupId) { mutableStateOf<String?>(null) }
    var permissionTargetKey by rememberSaveable(space.groupId) { mutableStateOf<String?>(null) }
    var showGmsConsent by rememberSaveable(space.groupId) { mutableStateOf(false) }
    var showGmsDisable by rememberSaveable(space.groupId) { mutableStateOf(false) }
    var showGmsReset by rememberSaveable(space.groupId) { mutableStateOf(false) }
    val uninstallTarget = space.apps.firstOrNull { it.launchKey == uninstallTargetKey }
    val permissionTarget = space.apps.firstOrNull { it.launchKey == permissionTargetKey }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 48.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            SpaceDetailHeader(
                space = space,
                isBusy = state.busyGroupId == space.groupId,
                onRename = { showRename = true },
                onDelete = { showDelete = true },
            )
        }
        item {
            GmsCompatibilityCard(
                state = space.gmsCompatibility,
                isBusy = state.gmsBusyGroupId == space.groupId,
                onEnable = {
                    if (
                        space.gmsCompatibility?.profile?.networkConsent ==
                        GmsNetworkConsent.GRANTED
                    ) {
                        onEnableGms(space.groupId, false)
                    } else {
                        showGmsConsent = true
                    }
                },
                onDisable = { showGmsDisable = true },
                onReset = { showGmsReset = true },
            )
        }
        if (!space.apps.any() && space.lifecycle == SpaceLifecycleState.READY) {
            item { EmptySpaceApps(onAddApp = { onAddApp(space.groupId) }) }
        } else {
            item {
                AppGrid(
                    modifier = Modifier.fillMaxWidth(),
                    apps = space.apps,
                    launchingAppKey = state.launchingAppKey,
                    uninstallingAppKey = state.uninstallingAppKey,
                    shortcutAppKey = state.shortcutAppKey,
                    repairingAppKey = state.repairingAppKey,
                    enabled = space.lifecycle == SpaceLifecycleState.READY &&
                        state.busyGroupId == null &&
                        state.launchingAppKey == null &&
                        state.uninstallingAppKey == null,
                    onLaunch = onLaunch,
                    onUninstall = { uninstallTargetKey = it.launchKey },
                    onCreateShortcut = onCreateShortcut,
                    onRepair = onRepairApp,
                    onManagePermissions = { permissionTargetKey = it.launchKey },
                    onAddApp = { onAddApp(space.groupId) },
                )
            }
        }
    }

    if (showRename) {
        RenameGroupDialog(
            group = space,
            onDismiss = { showRename = false },
            onConfirm = { name ->
                onRenameSpace(space.groupId, name)
                showRename = false
            },
        )
    }

    if (showDelete) {
        AlertDialog(
            modifier = Modifier.testTag("delete-space-dialog"),
            onDismissRequest = { showDelete = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("刪除「${space.name}」？") },
            text = {
                Text(
                    "將永久刪除此空間、${space.apps.size} 個分身 App 的登入與所有資料。" +
                        "手機上的原始 App 和其他分身空間不受影響，此操作無法復原。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSpace(space.groupId)
                        showDelete = false
                    },
                ) { Text("永久刪除") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            },
        )
    }

    if (showGmsConsent) {
        AlertDialog(
            modifier = Modifier.testTag("gms-consent-dialog"),
            onDismissRequest = { showGmsConsent = false },
            icon = { Icon(Icons.Default.Cloud, contentDescription = null) },
            title = { Text("啟用 Google 服務相容功能？") },
            text = {
                Text(
                    "此功能由 microG 提供，並非 Google 官方服務。啟用後，只有「${space.name}」會連線至 Google 的裝置註冊、帳號及推播端點；帳號、token 與資料不會與其他空間共用。",
                )
            },
            confirmButton = {
                Button(
                    modifier = Modifier.testTag("confirm-gms-consent"),
                    onClick = {
                        onEnableGms(space.groupId, true)
                        showGmsConsent = false
                    },
                ) { Text("同意並啟用") }
            },
            dismissButton = {
                TextButton(onClick = { showGmsConsent = false }) { Text("取消") }
            },
        )
    }

    if (showGmsDisable) {
        AlertDialog(
            modifier = Modifier.testTag("gms-disable-dialog"),
            onDismissRequest = { showGmsDisable = false },
            title = { Text("停用相容功能？") },
            text = { Text("將停止此空間的 microG 背景服務；既有相容服務資料會保留，之後可重新啟用。") },
            confirmButton = {
                Button(
                    modifier = Modifier.testTag("confirm-gms-disable"),
                    onClick = {
                        onDisableGms(space.groupId)
                        showGmsDisable = false
                    },
                ) { Text("停用") }
            },
            dismissButton = {
                TextButton(onClick = { showGmsDisable = false }) { Text("取消") }
            },
        )
    }

    if (showGmsReset) {
        val reenable = space.gmsCompatibility?.profile?.desiredState == GmsDesiredState.ENABLED
        AlertDialog(
            modifier = Modifier.testTag("gms-reset-dialog"),
            onDismissRequest = { showGmsReset = false },
            icon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("永久重設相容服務資料？") },
            text = {
                Text(
                    "將永久刪除此空間的 microG 帳號、check-in 身分、FCM token 與資料庫。分身 App 資料及其他空間不受影響；此操作無法復原。",
                )
            },
            confirmButton = {
                Button(
                    modifier = Modifier.testTag("confirm-gms-reset"),
                    onClick = {
                        onResetGms(space.groupId, reenable)
                        showGmsReset = false
                    },
                ) { Text("永久重設") }
            },
            dismissButton = {
                TextButton(onClick = { showGmsReset = false }) { Text("取消") }
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
                    "只會從「${app.groupName}」移除此分身 App，並永久刪除它在此空間的登入與所有資料。手機上的原始 App 和其他分身空間不受影響；此操作無法復原。",
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

    permissionTarget?.let { app ->
        ClonePermissionDialog(
            app = app,
            onDismiss = { permissionTargetKey = null },
            onSetPermission = { permission, granted ->
                onSetPermission(app, permission, granted)
            },
        )
    }
}

@Composable
private fun GmsCompatibilityCard(
    state: org.apptwin.gms.GmsGroupProductState?,
    isBusy: Boolean,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onReset: () -> Unit,
) {
    val profile = state?.profile
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("gms-compatibility-card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Cloud, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Google 服務相容功能",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "由 microG 提供，實驗性；並非 Google 官方服務",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        gmsProfileLabel(profile?.observedState),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (state?.hasDataWarning == true) {
                Text(
                    "相容服務資料無法安全讀取；已停止變更並保留原始資料。",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("gms-data-warning"),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                state?.capabilities.orEmpty().forEach { assessment ->
                    GmsCapabilityRow(assessment)
                }
            }
            if (profile?.failureCode != null) {
                Text(
                    "狀態代碼：${profile.failureCode}",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (profile?.desiredState == GmsDesiredState.ENABLED) {
                        OutlinedButton(
                            onClick = onDisable,
                            enabled = !isBusy && state?.hasDataWarning != true,
                        ) {
                            Text("停用")
                        }
                    } else {
                        Button(
                            onClick = onEnable,
                            enabled = !isBusy && state?.hasDataWarning != true,
                        ) {
                            Text("啟用")
                        }
                    }
                    if (isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                OutlinedButton(
                    onClick = onReset,
                    enabled = !isBusy && state?.hasDataWarning != true,
                ) {
                    Text("重設資料")
                }
            }
        }
    }
}

@Composable
private fun GmsCapabilityRow(assessment: GmsCapabilityAssessment) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(gmsCapabilityLabel(assessment.capability), modifier = Modifier.weight(1f))
        Text(
            gmsCapabilityStatusLabel(assessment),
            modifier = Modifier.testTag("gms-capability-status-${assessment.capability.name}"),
            color = if (assessment.status == GmsCapabilityStatus.UNSUPPORTED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.End,
        )
    }
}

private fun gmsProfileLabel(state: GmsObservedState?): String = when (state) {
    GmsObservedState.ABSENT, null -> "未啟用"
    GmsObservedState.ENABLING -> "啟用中"
    GmsObservedState.READY_PARTIAL -> "部分可用"
    GmsObservedState.DEGRADED -> "需要處理"
    GmsObservedState.DISABLING -> "停用中"
    GmsObservedState.RESETTING -> "重設中"
    GmsObservedState.UPDATE_REQUIRED -> "需要更新"
    GmsObservedState.REVOKED -> "版本已撤銷"
}

private fun gmsCapabilityLabel(capability: GmsCapability): String = when (capability) {
    GmsCapability.PLAY_SERVICES_AVAILABILITY -> "Play services availability"
    GmsCapability.FCM_REGISTRATION -> "FCM token"
    GmsCapability.FCM_MESSAGE -> "FCM 訊息接收"
    GmsCapability.FCM_NOTIFICATION_ROUTING -> "通知與空間路由"
    GmsCapability.FUSED_LOCATION -> "Fused Location"
    GmsCapability.MAPS_SDK_V2 -> "Maps SDK v2"
    GmsCapability.GOOGLE_SIGN_IN_LEGACY -> "Google Sign-In (legacy)"
    GmsCapability.GOOGLE_SIGN_IN_GIS -> "Google Identity Services"
    GmsCapability.CAST_SENDER -> "Cast sender"
    GmsCapability.NEARBY -> "Nearby"
    GmsCapability.PLAY_BILLING -> "Play Billing"
    GmsCapability.PLAY_INTEGRITY -> "Play Integrity"
}

private fun gmsCapabilityStatusLabel(assessment: GmsCapabilityAssessment): String = when {
    assessment.capability in setOf(GmsCapability.PLAY_BILLING, GmsCapability.PLAY_INTEGRITY) ->
        "不支援"
    assessment.status == GmsCapabilityStatus.KNOWN_FAILURE ->
        "Fixture 失敗${assessment.failureCode?.let { " ($it)" }.orEmpty()}"
    assessment.status == GmsCapabilityStatus.FIXTURE_PASSED_EXTERNAL_UNTESTED -> when (
        assessment.evidenceTier
    ) {
        GmsEvidenceTier.ASUS_FIXTURE -> "ASUS fixture 通過／外部待驗"
        else -> "Local fixture 通過／外部待驗"
    }
    // The agreed product boundary does not promote stored external evidence in this build.
    assessment.status in setOf(
        GmsCapabilityStatus.REAL_EXTERNAL_VERIFIED,
        GmsCapabilityStatus.THIRD_PARTY_APP_VERIFIED,
    ) -> "Fixture 通過／外部待驗"
    else -> "尚未驗證"
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
private fun SpaceDetailHeader(
    space: GroupItem,
    isBusy: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by rememberSaveable(space.groupId) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${space.name}空間",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${space.apps.size} 個分身 App · 登入與資料不會與其他空間共用",
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                )
                SpaceStatusChip(lifecycle = space.lifecycle)
            }
            Box {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "空間選單")
                    }
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("重新命名空間") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("刪除空間") },
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
    }
}

@Composable
private fun EmptySpaceApps(onAddApp: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("這個空間還沒有 App", style = MaterialTheme.typography.titleMedium)
            Text(
                "加入手機上已安裝的 App，建立一套獨立登入與資料。",
                modifier = Modifier.padding(top = 8.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(modifier = Modifier.padding(top = 20.dp), onClick = onAddApp) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("加入第一個 App", modifier = Modifier.padding(start = 8.dp))
            }
        }
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
private fun SpaceSummaryCard(
    item: GroupItem,
    isBusy: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("space-card-${item.groupId}"),
        onClick = onClick,
        enabled = !isBusy,
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
                if (isBusy) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            SpaceStatusChip(lifecycle = item.lifecycle)
            Text(
                if (item.apps.isEmpty()) {
                    "尚未加入 App，點一下開始設定"
                } else {
                    item.apps.take(4).joinToString(" · ") { it.appLabel } +
                        if (item.apps.size > 4) " · …" else ""
                },
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
private fun AppGrid(
    modifier: Modifier,
    apps: List<GroupAppItem>,
    launchingAppKey: String?,
    uninstallingAppKey: String?,
    shortcutAppKey: String?,
    repairingAppKey: String?,
    enabled: Boolean,
    onLaunch: (GroupAppItem) -> Unit,
    onUninstall: (GroupAppItem) -> Unit,
    onCreateShortcut: (GroupAppItem) -> Unit,
    onRepair: (GroupAppItem) -> Unit,
    onManagePermissions: (GroupAppItem) -> Unit,
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
                    isCreatingShortcut = shortcutAppKey == app.launchKey,
                    isRepairing = repairingAppKey == app.launchKey,
                    enabled = enabled && launchingAppKey == null,
                    onClick = { onLaunch(app) },
                    onUninstall = { onUninstall(app) },
                    onCreateShortcut = { onCreateShortcut(app) },
                    onRepair = { onRepair(app) },
                    onManagePermissions = { onManagePermissions(app) },
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
    isCreatingShortcut: Boolean,
    isRepairing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onUninstall: () -> Unit,
    onCreateShortcut: () -> Unit,
    onRepair: () -> Unit,
    onManagePermissions: () -> Unit,
) {
    var menuExpanded by remember(app.launchKey) { mutableStateOf(false) }
    val busy = isLaunching || isUninstalling || isCreatingShortcut || isRepairing
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
