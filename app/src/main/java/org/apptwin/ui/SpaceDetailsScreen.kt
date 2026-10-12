package org.apptwin.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.apptwin.GroupAppItem
import org.apptwin.GroupItem
import org.apptwin.archive.SpaceBackupEntry
import java.text.DateFormat
import java.util.Date

/** Presentation-only values. Runtime and host grants must both be true for effective access. */
data class SpacePermissionRow(
    val permission: String,
    val label: String,
    val app: GroupAppItem,
    val allowed: Boolean,
    val hostGranted: Boolean,
    val platformExempt: Boolean = false,
)
data class SpaceAccountRow(val name: String, val type: String, val syncAuthorities: List<String>, val syncStatus: String)
data class SpaceAuthenticatorRow(val type: String, val label: String)

@Composable
fun SpaceDetailsScreen(
    group: GroupItem,
    storage: List<Pair<String, Long>>,
    permissions: List<SpacePermissionRow>,
    accounts: List<SpaceAccountRow>,
    authenticators: List<SpaceAuthenticatorRow>,
    backups: List<SpaceBackupEntry>,
    loading: Boolean,
    busy: Boolean,
    error: String?,
    globalBackupLocation: String?,
    onRefresh: () -> Unit,
    onSetPermission: (GroupAppItem, String, Boolean) -> Unit,
    onAddAccount: (String) -> Unit,
    onRemoveAccount: (SpaceAccountRow) -> Unit,
    onSynchronizeAccount: (SpaceAccountRow, String) -> Unit,
    onCreateFromBackup: (SpaceBackupEntry) -> Unit,
    onDeleteBackup: (SpaceBackupEntry) -> Unit,
    onExport: () -> Unit,
    onOpenGlobalSettings: () -> Unit,
    onRename: (String) -> Unit,
    onClearAllAppData: () -> Unit,
    onDeleteSpace: () -> Unit,
    backgroundServiceState: org.apptwin.runtime.BackgroundServiceState = org.apptwin.runtime.BackgroundServiceState(),
    backgroundHealth: org.apptwin.SpaceBackgroundHealth? = null,
    backgroundRetrying: Boolean = false,
    onRetryBackground: () -> Unit = {},
) {
    var section by rememberSaveable(group.groupId) { mutableStateOf("overview") }
    BackHandler(enabled = section != "overview") { section = "overview" }
    var rename by rememberSaveable { mutableStateOf(false) }
    var clear by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    var export by rememberSaveable { mutableStateOf(false) }
    var addAccount by rememberSaveable { mutableStateOf(false) }
    var removeAccount by remember { mutableStateOf<SpaceAccountRow?>(null) }
    var deleteBackup by remember { mutableStateOf<SpaceBackupEntry?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("space-details"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(group.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${group.apps.size} 個 App · Google 服務為空間必要組件", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("space-details-error"))
                TextButton(onClick = onRefresh, enabled = !busy) { Text("重新載入") }
            }
        }
        if (section != "overview") item {
            TextButton(onClick = { section = "overview" }, modifier = Modifier.testTag("space-details-overview")) { Text("返回儀錶板") }
        }
        when (section) {
            "overview" -> {
                item { SpaceBackgroundCard(backgroundServiceState, backgroundHealth, backgroundRetrying, onRetryBackground) }
                item { DetailCard("儲存空間", "space-details-storage") {
                    Text(if (loading) "計算中…" else if (error != null) "無法取得使用量" else formatSpaceBytes(storage.sumOf { it.second }), style = MaterialTheme.typography.headlineMedium)
                    TextButton(onClick = { section = "storage" }) { Text("查看使用量") }
                } }
                item { DetailCard("權限", "space-details-permissions") {
                    Text(if (loading) "讀取中…" else "${permissions.count { it.allowed && it.hostGranted }} / ${permissions.size} 項 App 權限允許")
                    TextButton(onClick = { section = "permissions" }) { Text("按權限管理 App") }
                } }
                item { DetailCard("帳號", "space-details-accounts") {
                    Text(if (loading) "讀取中…" else "${accounts.size} 個 AccountManager 帳號")
                    TextButton(onClick = { section = "accounts" }) { Text("管理帳號") }
                } }
                item { DetailCard("備份", "space-details-backups") {
                    Text(if (loading) "讀取中…" else "${backups.size} 份備份")
                    TextButton(onClick = { section = "backups" }) { Text("管理備份") }
                } }
                item { DetailCard("空間管理", "space-details-management") {
                    TextButton(onClick = { rename = true }, enabled = !busy, modifier = Modifier.testTag("space-rename")) { Text("重新命名") }
                    TextButton(onClick = { clear = true }, enabled = !busy && group.apps.isNotEmpty(), modifier = Modifier.testTag("space-clear-data")) { Text("清除所有 App 資料") }
                    TextButton(onClick = { delete = true }, enabled = !busy, modifier = Modifier.testTag("space-delete")) { Text("刪除空間", color = MaterialTheme.colorScheme.error) }
                } }
            }
            "storage" -> item { DetailCard("儲存空間", "space-storage-page") {
                storage.forEach { (label, bytes) -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(label, Modifier.weight(1f)); Text(formatSpaceBytes(bytes))
                } }
                Text("共用 APK 與匯出備份另外計算。", style = MaterialTheme.typography.bodySmall)
            } }
            "permissions" -> {
                item { Text("拒絕或撤銷權限會停止此空間中受影響的 App，未儲存操作可能中斷。照片拒絕不影響使用者明確選取的檔案與 App 私有副本。", style = MaterialTheme.typography.bodySmall) }
                permissions.groupBy { it.label }.forEach { (label, rows) -> item {
                    DetailCard(label, "space-permission-group-${rows.first().permission}") {
                        if (rows.first().permission == android.Manifest.permission.ACCESS_COARSE_LOCATION) {
                            Text("概略位置支援 Android 系統定位；部分使用 Google 定位的 App 需要精確位置。", style = MaterialTheme.typography.bodySmall)
                        }
                        rows.forEach { row -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(row.app.appLabel, fontWeight = FontWeight.SemiBold)
                                Text(when { row.platformExempt -> "自動允許（此版本免設定）"; !row.allowed -> "此空間已拒絕"; !row.hostGranted -> "AppTwin 系統權限尚未允許"; else -> "允許存取" }, style = MaterialTheme.typography.bodySmall)
                            }
                            if (!row.platformExempt) Switch(checked = row.allowed, enabled = !busy,
                                onCheckedChange = { onSetPermission(row.app, row.permission, it) },
                                modifier = Modifier.testTag("space-permission-${row.permission}-${row.app.launchKey}"))
                        } }
                    }
                } }
                if (permissions.isEmpty()) item { Text("此空間尚無 App 宣告可管理的權限。") }
            }
            "accounts" -> {
                item { FilledTonalButton(onClick = { addAccount = true }, enabled = !busy && authenticators.isNotEmpty(), modifier = Modifier.testTag("space-account-add")) { Text("新增帳號") } }
                if (authenticators.isEmpty()) item { Text("此空間尚無可新增帳號的服務。請先加入提供 AccountManager 的 App。") }
                if (accounts.isEmpty()) item { Text("尚無 AccountManager 帳號。App 自行儲存的登入請在該 App 中管理。") }
                accounts.forEach { account -> item { DetailCard(account.name, "space-account-${account.type}-${account.name}") {
                    Text(authenticators.firstOrNull { it.type == account.type }?.label ?: account.type, style = MaterialTheme.typography.bodySmall)
                    Text(account.syncStatus, style = MaterialTheme.typography.bodySmall)
                    account.syncAuthorities.forEach { authority ->
                        TextButton(onClick = { onSynchronizeAccount(account, authority) }, enabled = !busy,
                            modifier = Modifier.testTag("space-account-sync-${account.type}-$authority")) { Text("立即同步 · $authority") }
                    }
                    TextButton(onClick = { removeAccount = account }, enabled = !busy, modifier = Modifier.testTag("space-account-remove-${account.type}-${account.name}")) { Text("移除帳號") }
                } } }
            }
            "backups" -> {
                item { DetailCard("備份位置", "space-backup-location") {
                    Text(backupLocationLabel(globalBackupLocation) ?: "尚未設定 Global 備份位置", style = MaterialTheme.typography.bodySmall)
                    Text("所有空間跟隨 Global 設定。")
                    TextButton(onClick = onOpenGlobalSettings) { Text("前往 Global 設定") }
                    FilledTonalButton(onClick = { export = true }, enabled = !busy && globalBackupLocation != null, modifier = Modifier.testTag("space-backup-export")) { Text("建立備份") }
                } }
                if (backups.isEmpty()) item { Text("此空間尚無備份。") }
                backups.forEach { backup -> item { DetailCard(backup.spaceName, "space-backup-${backup.id}") {
                    Text("${formatSpaceBytes(backup.bytes)} · ${DateFormat.getDateTimeInstance().format(Date(backup.createdAt))}")
                    if (!backup.accessible) Text("無法存取，檔案可能已移動、刪除或授權失效。", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { onCreateFromBackup(backup) }, enabled = !busy && backup.accessible, modifier = Modifier.testTag("space-backup-create-${backup.id}")) { Text("從備份建立") }
                    TextButton(onClick = { deleteBackup = backup }, enabled = !busy && backup.accessible, modifier = Modifier.testTag("space-backup-delete-${backup.id}")) { Text("永久刪除備份", color = MaterialTheme.colorScheme.error) }
                } } }
            }
        }
    }
    if (rename) RenameGroupDialog(group, { rename = false }, { onRename(it); rename = false })
    if (clear) ClearAllSpaceDataDialog(group, busy, { clear = false }, { onClearAllAppData(); clear = false })
    if (delete) DeleteSpaceDialog(group, { delete = false }, { onDeleteSpace(); delete = false })
    if (export) ExportSpaceArchiveDialog(group, { export = false }, { onExport(); export = false })
    if (addAccount) AlertDialog(onDismissRequest = { addAccount = false }, title = { Text("新增帳號") }, text = {
        Column { authenticators.forEach { authenticator -> TextButton(onClick = { addAccount = false; onAddAccount(authenticator.type) }, modifier = Modifier.testTag("space-authenticator-${authenticator.type}")) { Text(authenticator.label) } } }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { addAccount = false }) { Text("取消") } })
    removeAccount?.let { account -> AlertDialog(onDismissRequest = { removeAccount = null }, title = { Text("移除帳號？") }, text = { Text("移除此空間中的 ${account.name}，其他空間不受影響。") }, confirmButton = {
        TextButton(onClick = { removeAccount = null; onRemoveAccount(account) }, modifier = Modifier.testTag("confirm-remove-space-account")) { Text("移除") }
    }, dismissButton = { TextButton(onClick = { removeAccount = null }) { Text("取消") } }) }
    deleteBackup?.let { backup -> AlertDialog(modifier = Modifier.testTag("delete-backup-dialog"), onDismissRequest = { deleteBackup = null }, title = { Text("永久刪除備份？") }, text = { Text("將永久刪除這份 ${formatSpaceBytes(backup.bytes)} 的備份檔案。此操作無法復原，現有空間不受影響。") }, confirmButton = {
        TextButton(onClick = { deleteBackup = null; onDeleteBackup(backup) }, modifier = Modifier.testTag("confirm-delete-backup")) { Text("永久刪除", color = MaterialTheme.colorScheme.error) }
    }, dismissButton = { TextButton(onClick = { deleteBackup = null }) { Text("取消") } }) }
}

@Composable
private fun DetailCard(title: String, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().testTag(tag)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        content()
    } }
}
internal fun formatSpaceBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
}

/** Human-facing folder path; the persisted SAF URI remains the operation target. */
internal fun backupLocationLabel(location: String?): String? {
    if (location == null) return null
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(location)) }.getOrNull()
        ?: return "已選擇備份資料夾"
    val volume = documentId.substringBefore(':')
    val folder = documentId.substringAfter(':', "").trim('/')
    val storage = if (volume == "primary") "內部儲存空間" else "儲存裝置 $volume"
    return if (folder.isEmpty()) storage else "$storage / $folder"
}

@Composable
internal fun SpaceBackgroundCard(
    setting: org.apptwin.runtime.BackgroundServiceState,
    health: org.apptwin.SpaceBackgroundHealth?,
    retrying: Boolean,
    retry: () -> Unit,
) {
    val enabled = setting.loaded && setting.enabled && !setting.busy
    val recovering = retrying || health?.recovering == true
    val off = setting.loaded && !setting.enabled
    val alpha = if (enabled) 1f else 0.45f
    Card(Modifier.fillMaxWidth().testTag("space-background-card").semantics { if (!enabled) disabled() }.graphicsLayer { this.alpha = alpha }) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("背景服務", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(when { off -> "已停用"; !enabled -> "讀取中…"; recovering -> "恢復中…"; health?.healthy == true -> "正常"; health?.microg == org.apptwin.SpaceBackgroundStatus.SUCCESS && health.tcp == org.apptwin.SpaceBackgroundStatus.SUCCESS && !health.sessionVerified && health.error == null -> "尚未確認"; health?.retryable == true -> "異常"; else -> "尚未確認" }, Modifier.testTag("space-background-summary"))
            }
            fun label(status: org.apptwin.SpaceBackgroundStatus?, success: String, failure: String, starting: String): String = when {
                off -> "已停用"
                !enabled -> "尚未確認"
                status == org.apptwin.SpaceBackgroundStatus.SUCCESS -> success
                status == org.apptwin.SpaceBackgroundStatus.STARTING -> starting
                status == org.apptwin.SpaceBackgroundStatus.FAILED -> failure
                status == org.apptwin.SpaceBackgroundStatus.DISABLED -> "已停用"
                else -> "尚未確認"
            }
            Row(Modifier.fillMaxWidth()) { Text("microG", Modifier.weight(1f)); Text(label(health?.microg, "已啟動", "啟動失敗", "啟動中…"), Modifier.testTag("space-background-microg")) }
            Row(Modifier.fillMaxWidth()) { Text("TCP 連線", Modifier.weight(1f)); Text(if (recovering && enabled) "連線中…" else label(health?.tcp, "已連線", "已中斷", "連線中…"), Modifier.testTag("space-background-tcp")) }
            if (off) Text("請至總設定開啟背景服務。", style = MaterialTheme.typography.bodySmall)
            else if (enabled) {
                health?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (health?.tcp == org.apptwin.SpaceBackgroundStatus.SUCCESS && !health.sessionVerified && health.error == null) Text("TCP 已連線，推播登入尚未確認。", style = MaterialTheme.typography.bodySmall)
                if ((health?.checkedAtMillis ?: 0) > 0) Text("最後檢查：${java.text.DateFormat.getTimeInstance().format(java.util.Date(health!!.checkedAtMillis))}", style = MaterialTheme.typography.bodySmall)
            }
            if (off || recovering || health?.retryable == true) TextButton(onClick = retry,
                enabled = enabled && !recovering && health?.retryable == true,
                modifier = Modifier.testTag("space-background-retry")) {
                Text(if (recovering) "連線中…" else if (health?.microg == org.apptwin.SpaceBackgroundStatus.FAILED) "重新啟動" else "重新連線")
            }
        }
    }
}
