package org.apptwin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.apptwin.MainUiState
import org.apptwin.archive.SpaceArchiveCompression
import org.apptwin.permissions.ClonePermissionAction
import org.apptwin.permissions.ClonePermissionCategory
import org.apptwin.permissions.ClonePermissionSummary
import org.apptwin.permissions.ClonePermissionVirtualScope

@Composable
fun SettingsScreen(
    state: MainUiState,
    onArchiveCompressionChange: (SpaceArchiveCompression) -> Unit = {},
    onOpenStorageSettings: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onImportSpace: () -> Unit = {},
    notificationsGranted: Boolean,
    onRequestNotifications: () -> Unit,
    onPermissionAction: (ClonePermissionSummary) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ArchiveExportSettingsCard(
                archiveCompression = state.archiveCompression,
                onArchiveCompressionChange = onArchiveCompressionChange,
            )
        }
        item {
            SettingsCard(
                icon = { Icon(Icons.Default.Unarchive, contentDescription = null) },
                title = "匯入空間存檔",
            ) {
                Text(
                    "只會建立新的空間，不會覆寫現有空間。匯入前會驗證格式、檔案大小與 SHA-256。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "存檔未加密，且 LINE 登入金鑰僅能在原裝置與目前 AppTwin 主程式資料仍存在時使用。",
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                FilledTonalButton(
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .testTag("import-space-archive"),
                    enabled = !state.isImportingArchive && state.archiveBusyGroupId == null,
                    onClick = onImportSpace,
                ) {
                    Icon(Icons.Default.Unarchive, contentDescription = null)
                    Text(
                        if (state.isImportingArchive) "匯入中…" else "選擇存檔",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        item {
            ClonePermissionSettingsCard(
                permissions = state.clonePermissions,
                onPermissionAction = onPermissionAction,
            )
        }
        item {
            SettingsCard(
                icon = {
                    Icon(
                        if (notificationsGranted) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                    )
                },
                title = "分身通知",
            ) {
                Text(
                    if (notificationsGranted) {
                        "通知權限已開啟；轉送通知會標示來源分身空間。"
                    } else {
                        "尚未允許通知。Android 13 以上必須先允許，分身通知才會顯示。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!notificationsGranted) {
                    FilledTonalButton(
                        modifier = Modifier.padding(top = 16.dp),
                        onClick = onRequestNotifications,
                    ) {
                        Text("允許通知")
                    }
                }
            }
        }
        item {
            SettingsCard(
                icon = {
                    Icon(
                        if (state.allFilesGranted) Icons.Default.CheckCircle else Icons.Default.Folder,
                        contentDescription = null,
                    )
                },
                title = "共享檔案存取",
            ) {
                Text(
                    if (state.allFilesGranted) {
                        "已開啟 · Download ${state.downloadCount} 項 · 相片目錄 ${state.photoCount} 項"
                    } else {
                        "尚未開啟。只有需要瀏覽共享相片或 Download 時才前往系統授權。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    modifier = Modifier.padding(top = 16.dp),
                    onClick = onOpenStorageSettings,
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Text(
                        if (state.allFilesGranted) "檢視系統設定" else "開啟權限設定",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        item {
            SettingsCard(
                icon = { Icon(Icons.Default.Share, contentDescription = null) },
                title = "診斷與支援",
            ) {
                Text(
                    "報告只包含版本、狀態與穩定錯誤代碼；空間與 package 會以每次不同的代碼取代，不包含帳號、通知、Intent、檔案內容或路徑。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    modifier = Modifier.padding(top = 16.dp),
                    onClick = onExportDiagnostics,
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Text("產生並分享報告", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ArchiveExportSettingsCard(
    archiveCompression: SpaceArchiveCompression,
    onArchiveCompressionChange: (SpaceArchiveCompression) -> Unit,
) {
    SettingsCard(
        modifier = Modifier.testTag("archive-export-settings-card"),
        icon = { Icon(Icons.Default.Folder, contentDescription = null) },
        title = "匯出空間設定",
    ) {
        Text(
            "壓縮比率",
            fontWeight = FontWeight.SemiBold,
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            SpaceArchiveCompression.entries.forEachIndexed { index, compression ->
                SegmentedButton(
                    selected = archiveCompression == compression,
                    onClick = { onArchiveCompressionChange(compression) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = SpaceArchiveCompression.entries.size,
                    ),
                    modifier = Modifier
                        .testTag("archive-compression-${compression.name.lowercase()}"),
                ) {
                    Text(archiveCompressionTitle(compression))
                }
            }
        }
        Text(
            archiveCompressionDescription(archiveCompression),
            modifier = Modifier.padding(top = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun archiveCompressionTitle(compression: SpaceArchiveCompression): String = when (compression) {
    SpaceArchiveCompression.HIGH -> "高"
    SpaceArchiveCompression.MEDIUM -> "中"
    SpaceArchiveCompression.LOW -> "低"
}

private fun archiveCompressionDescription(compression: SpaceArchiveCompression): String = when (compression) {
    SpaceArchiveCompression.HIGH -> "檔案通常較小，匯出較慢"
    SpaceArchiveCompression.MEDIUM -> "檔案大小與匯出速度較為平衡"
    SpaceArchiveCompression.LOW -> "匯出較快，檔案通常較大"
}

@Composable
private fun ClonePermissionSettingsCard(
    permissions: List<ClonePermissionSummary>,
    onPermissionAction: (ClonePermissionSummary) -> Unit,
) {
    val actionable = permissions.filter {
        it.category == ClonePermissionCategory.RUNTIME ||
            it.category == ClonePermissionCategory.SPECIAL
    }
    val automatic = permissions.filter { it.category == ClonePermissionCategory.AUTOMATIC }
    val unsupported = permissions.filter { it.category == ClonePermissionCategory.UNSUPPORTED }
    SettingsCard(
        modifier = Modifier.testTag("clone-permission-card"),
        icon = { Icon(Icons.Default.Security, contentDescription = null) },
        title = "分身 App 權限",
    ) {
        if (permissions.isEmpty()) {
            Text(
                "尚未加入分身 App；加入後會在這裡彙整其宣告的權限。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SettingsCard
        }
        val readyCount = actionable.count(ClonePermissionSummary::granted)
        Text(
            if (actionable.isEmpty()) {
                "目前沒有需要使用者額外授權的項目。"
            } else {
                "$readyCount / ${actionable.size} 項已就緒"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        actionable.forEachIndexed { index, permission ->
            if (index == 0) Spacer(Modifier.height(8.dp))
            PermissionRow(permission, onPermissionAction)
        }
        PermissionGroup(
            title = "系統自動授權",
            permissions = automatic,
            testTag = "permission-auto-section",
            onPermissionAction = onPermissionAction,
        )
        PermissionGroup(
            title = "無法授權或尚未支援",
            permissions = unsupported,
            testTag = "permission-unsupported-section",
            onPermissionAction = onPermissionAction,
        )
    }
}

@Composable
private fun PermissionGroup(
    title: String,
    permissions: List<ClonePermissionSummary>,
    testTag: String,
    onPermissionAction: (ClonePermissionSummary) -> Unit,
) {
    if (permissions.isEmpty()) return
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    TextButton(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .testTag(testTag),
        onClick = { expanded = !expanded },
    ) {
        Text("$title（${permissions.size}）", modifier = Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "收合$title" else "展開$title",
        )
    }
    if (expanded) {
        permissions.forEach { PermissionRow(it, onPermissionAction) }
    }
}

@Composable
private fun PermissionRow(
    permission: ClonePermissionSummary,
    onPermissionAction: (ClonePermissionSummary) -> Unit,
) {
    var affectedExpanded by rememberSaveable(permission.permission) { mutableStateOf(false) }
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .testTag("permission-row-${permission.permission}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(permission.label, fontWeight = FontWeight.SemiBold)
                Text(
                    permissionStatus(permission),
                    color = if (permission.granted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    permissionScopeLabel(permission.virtualScope),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (permission.action != ClonePermissionAction.NONE) {
                FilledTonalButton(
                    modifier = Modifier.testTag("permission-action-${permission.permission}"),
                    onClick = { onPermissionAction(permission) },
                ) {
                    Text(
                        if (permission.action == ClonePermissionAction.REQUEST_RUNTIME) {
                            "允許使用"
                        } else {
                            "開啟設定"
                        },
                    )
                }
            }
        }
        TextButton(onClick = { affectedExpanded = !affectedExpanded }) {
            Text("影響 ${permission.affectedClones.size} 個分身")
            Icon(
                if (affectedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
        }
        if (affectedExpanded) {
            permission.affectedClones.forEach { target ->
                Text(
                    "${target.groupName} · ${target.appLabel}",
                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun permissionStatus(permission: ClonePermissionSummary): String = when {
    permission.category == ClonePermissionCategory.AUTOMATIC -> "系統自動授權"
    permission.category == ClonePermissionCategory.UNSUPPORTED -> "無法授權或尚未支援"
    permission.granted -> "已就緒"
    else -> "尚未授權"
}

private fun permissionScopeLabel(scope: ClonePermissionVirtualScope): String = when (scope) {
    ClonePermissionVirtualScope.CAMERA_MIC_PER_SPACE -> "宿主授權；各分身可另行調整"
    ClonePermissionVirtualScope.HOST_SHARED -> "所有分身共用宿主系統授權"
    ClonePermissionVirtualScope.NOT_SUPPORTED -> "AppTwin 尚未支援此宿主授權"
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                icon()
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(modifier = Modifier.padding(top = 14.dp), content = { content() })
        }
    }
}
