package org.apptwin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apptwin.groups.Group
import org.apptwin.compatibility.CompatibilityLevel
import org.apptwin.groups.GroupAppRemovalResult
import org.apptwin.gms.GmsGroupProductState
import org.apptwin.operations.OperationRecord
import org.apptwin.repair.RepairExecutionResult
import org.apptwin.revision.InstalledAppEntry
import org.apptwin.runtime.GroupAppRuntimeSupport
import org.apptwin.runtime.RuntimeCompatibility
import org.apptwin.spaces.CloneLifecycleState
import org.apptwin.spaces.SpaceStatePolicy
import org.apptwin.usecases.ClearCloneStorageResult
import org.apptwin.usecases.ClearSpaceStorageResult

import kotlinx.coroutines.CoroutineScope

internal class HomeStateOwner(
    private val scope: CoroutineScope,
    private val spaces: SpaceOperations,
    private val clones: CloneOperations,
    private val permissions: ClonePermissionOperations,
    private val snapshots: MainSnapshotOperations,
    private val background: BackgroundRecoveryOperations,
    private val ioDispatcher: CoroutineDispatcher,
    private val session: MainSessionCoordinator,
    private val showMessage: (String) -> Unit,
    private val validatePickerSelection: (List<GroupItem>) -> Unit,
    private val publishSettingsSnapshot: (MainRefreshSnapshot) -> Unit,
    private val presentResolvedDeepLink: (List<GroupItem>, Boolean) -> Unit,
    private val onGroupDeleted: (String) -> Unit,
    private val refreshDetailsIfVisible: () -> Unit,
) {
    private data class Content(
        val isRefreshing: Boolean = true,
        val dataWarnings: List<String> = emptyList(),
        val error: String? = null,
        val createSpaceError: String? = null,
    )
    private val mutableContent = MutableStateFlow(Content())
    private var content: Content
        get() = mutableContent.value
        set(value) { mutableContent.value = value }
    val state: HomeUiState get() = HomeUiState(
        groups = session.state.groups,
        isRefreshing = content.isRefreshing,
        dataWarnings = content.dataWarnings,
        error = content.error,
        createSpaceError = content.createSpaceError,
        isCreatingGroup = session.state.isCreatingGroup,
    )
    val states = combine(mutableContent, session.states) { _, _ -> state }
        .stateIn(scope, SharingStarted.Eagerly, state)
    fun start() { reconcileAndRefresh() }
    fun dismissCreateSpaceError() { content = content.copy(createSpaceError = null) }
    fun showCreateSpaceError(message: String) { content = content.copy(createSpaceError = message) }
    fun refresh() {
        refreshDetailsIfVisible()
        if (session.launchBlockedByRecovery) {
            reconcileAndRefresh()
            return
        }
        val generation = ++session.refreshGeneration
        content = content.copy(isRefreshing = true, error = null)
        scope.launch {
            val groups = loadAndPublishGroups(generation) ?: return@launch
            applyFullRefresh(generation, groups)
        }
    }

    private suspend fun loadAndPublishGroups(generation: Long): MainGroupSnapshot? {
        val snapshot = operationResult {
            withContext(ioDispatcher) { snapshots.loadGroupSnapshot() }
        }.getOrElse { error ->
            if (generation == session.refreshGeneration) {
                content = content.copy(isRefreshing = false, error = "讀取空間失敗：${error.operationMessage()}")
                showMessage("讀取空間失敗：${error.operationMessage()}")
            }
            return null
        }
        if (generation != session.refreshGeneration) return null
        publishGroups(snapshot)
        return snapshot
    }

    private suspend fun applyFullRefresh(generation: Long, groups: MainGroupSnapshot) {
        if (generation != session.refreshGeneration) return
        val snapshot = operationResult {
            withContext(ioDispatcher) { snapshots.refreshSnapshot(groups) }
        }.getOrElse { error ->
            if (generation == session.refreshGeneration) {
                content = content.copy(isRefreshing = false, error = "重新整理詳細資料失敗：${error.operationMessage()}")
                showMessage("重新整理詳細資料失敗：${error.operationMessage()}")
            }
            return
        }
        if (generation != session.refreshGeneration) return
        val entriesByPackage = snapshot.entries.associateBy(InstalledAppEntry::packageName)
        val appItems = snapshot.entries.map { entry ->
            val active = snapshot.activeRevisions[entry.packageName]
            AppItem(
                entry = entry,
                isSynced = active?.versionCode == entry.versionCode,
                activeVersionCode = active?.versionCode,
                groupCount = snapshot.groups.count { it.contains(entry.packageName) },
                compatibility = if (
                    GroupAppRuntimeSupport.compatibility(
                        entry.packageName,
                        entry.versionCode,
                        android.os.Build.VERSION.SDK_INT,
                    ) == RuntimeCompatibility.VERIFIED
                ) CompatibilityLevel.PARTIAL else CompatibilityLevel.UNTESTED,
            )
        }
        val groupItems = buildGroupItems(
            groups = snapshot.groups,
            operations = snapshot.operations,
            entriesByPackage = entriesByPackage,
            permissions = snapshot.permissions,
            gmsCompatibility = snapshot.gmsCompatibility,
            enrichmentComplete = true,
        )
        val warningsChanged = snapshot.dataWarnings != content.dataWarnings
        validatePickerSelection(groupItems)
        session.update { it.copy(apps = appItems, groups = groupItems) }
        content = content.copy(
            isRefreshing = false,
            dataWarnings = snapshot.dataWarnings,
            error = if (groupItems.isEmpty() && snapshot.dataWarnings.isNotEmpty()) {
                "部分空間資料無法讀取；原始資料已保留"
            } else null,
        )
        publishSettingsSnapshot(snapshot)
        presentResolvedDeepLink(groupItems, false)
        if (snapshot.dataWarnings.isNotEmpty() && warningsChanged) {
            showMessage("偵測到 ${snapshot.dataWarnings.size} 筆資料完整性問題；原始資料已保留")
        }
        session.resolvePendingLaunch(groupItems, ::refresh)
    }

    private fun publishGroups(snapshot: MainGroupSnapshot) {
        val groupItems = buildGroupItems(
            groups = snapshot.groups,
            operations = snapshot.operations,
            entriesByPackage = emptyMap(),
            permissions = emptyMap(),
            gmsCompatibility = emptyMap(),
            enrichmentComplete = false,
        )
        val warningsChanged = snapshot.dataWarnings != content.dataWarnings
        validatePickerSelection(groupItems)
        session.update { it.copy(groups = groupItems) }
        content = content.copy(dataWarnings = snapshot.dataWarnings, isRefreshing = true)
        if (snapshot.dataWarnings.isNotEmpty() && warningsChanged) {
            showMessage("偵測到 ${snapshot.dataWarnings.size} 筆資料完整性問題；原始資料已保留")
        }
    }

    private fun buildGroupItems(
        groups: List<Group>,
        operations: List<OperationRecord>,
        entriesByPackage: Map<String, InstalledAppEntry>,
        permissions: Map<String, ClonePermissionState>,
        gmsCompatibility: Map<String, GmsGroupProductState>,
        enrichmentComplete: Boolean,
    ): List<GroupItem> = groups.sortedByDescending(Group::createdAtEpochMillis).map { group ->
        val spaceState = SpaceStatePolicy.assess(group, operations)
        val cloneStates = spaceState.clones.associateBy { it.packageName }
        GroupItem(
            groupId = group.id,
            name = group.name,
            health = group.health,
            lifecycle = spaceState.lifecycle,
            gmsCompatibility = gmsCompatibility[group.id],
            apps = group.apps.map { app ->
                val source = entriesByPackage[app.packageName]
                GroupAppItem(
                    groupId = group.id,
                    groupName = group.name,
                    groupHealth = group.health,
                    app = app,
                    appLabel = source?.label ?: app.packageName,
                    versionName = source?.versionName.orEmpty(),
                    sourceInstalled = source != null,
                    launchStatus = if (enrichmentComplete) {
                        launchStatus(source?.versionCode)
                    } else {
                        SOURCE_SYNC_PENDING
                    },
                    lifecycle = cloneStates.getValue(app.packageName).lifecycle,
                    cameraGranted = permissions["${group.id}:${app.packageName}"]?.cameraGranted == true,
                    microphoneGranted = permissions["${group.id}:${app.packageName}"]
                        ?.microphoneGranted == true,
                )
            },
        )
    }

    fun reconcileGoogleAfterCommit() {
        scope.launch {
            // Messaging is separate from verified local space initialization. Its supervisor
            // reports connection evidence and bounded failures; it never rolls back committed data.
            operationResult { withContext(ioDispatcher) { background.reconcileGms() } }
            refresh()
        }
    }

    fun createGroup(name: String) {
        if (
            session.state.isCreatingGroup ||
            session.state.isSpaceActionBusy ||
            session.state.isCreatingSpaceFromBackup ||
            session.state.archiveBusyGroupId != null
        ) return
        session.update { it.copy(isCreatingGroup = true) }
        scope.launch {
            val result = try {
                operationResult {
                    withContext(ioDispatcher) { spaces.createGroup(name) }
                }
            } finally {
                session.update { it.copy(isCreatingGroup = false) }
            }
            result.onSuccess { group ->
                showMessage("已建立「${group.name}」")
                refresh()
                reconcileGoogleAfterCommit()
            }.onFailure { error -> content = content.copy(createSpaceError = "建立空間失敗：${error.operationMessage()}") }
        }
    }

    fun renameGroup(groupId: String, name: String) {
        scope.launch {
            val result = operationResult {
                withContext(ioDispatcher) { spaces.renameGroup(groupId, name) }
            }
            result.onSuccess { renamed ->
                if (renamed == null) showMessage("找不到這個群組")
                else {
                    showMessage("已重新命名為「${renamed.name}」")
                    refresh()
                }
            }.onFailure { error -> showMessage("重新命名失敗：${error.operationMessage()}") }
        }
    }

    fun deleteGroup(groupId: String) {
        if (
            session.state.busyGroupId != null ||
            session.state.archiveBusyGroupId != null ||
            session.state.isCreatingSpaceFromBackup ||

            session.state.uninstallingAppKey != null ||
            session.state.clearingStorageAppKey != null ||
            session.state.clearingStorageGroupId != null
        ) return
        session.update { it.copy(busyGroupId = groupId) }
        scope.launch {
            val result = try {
                operationResult {
                    withContext(ioDispatcher) { spaces.deleteGroup(groupId) }
                }
            } finally {
                session.update { it.copy(busyGroupId = null) }
            }
            result.onSuccess { deletion ->
                when (deletion) {
                    DeleteGroupResult.NotFound -> showMessage("找不到這個群組")
                    is DeleteGroupResult.Deleted -> {
                        val warning = deletion.cleanupWarning
                            ?.let { "；但$it" }
                            .orEmpty()
                        showMessage(
                            "已刪除「${deletion.group.name}」及其中的所有資料$warning",
                        )
                    }
                }
                if (deletion is DeleteGroupResult.Deleted) {
                    onGroupDeleted(groupId)
                }
                refresh()
            }.onFailure { error ->
                showMessage("刪除群組失敗：${error.operationMessage()}")
                refresh()
            }
        }
    }

    fun uninstallGroupApp(item: GroupAppItem) {
        if (
            session.state.uninstallingAppKey != null ||
            session.state.launchingAppKey != null ||
            session.state.clearingStorageGroupId != null ||
            session.state.busyGroupId != null ||
            session.state.busyPackageName != null
        ) return
        session.update { it.copy(uninstallingAppKey = item.launchKey) }
        scope.launch {
            val result = try {
                operationResult {
                    withContext(ioDispatcher) { clones.uninstallGroupApp(item) }
                }.getOrElse { error ->
                    GroupAppRemovalResult.Failed(error.operationMessage(), error)
                }
            } finally {
                session.update { it.copy(uninstallingAppKey = null) }
            }
            when (result) {
                is GroupAppRemovalResult.Succeeded ->
                    showMessage("已從「${item.groupName}」解除安裝 ${item.appLabel}")
                GroupAppRemovalResult.AlreadyAbsent ->
                    showMessage("${item.appLabel} 已不在「${item.groupName}」中")
                is GroupAppRemovalResult.Failed ->
                    showMessage("解除安裝失敗：${result.reason}")
            }
            refresh()
        }
    }

    fun clearGroupAppStorage(item: GroupAppItem) {
        if (
            session.state.clearingStorageAppKey != null ||
            session.state.clearingStorageGroupId != null ||
            session.state.uninstallingAppKey != null ||
            session.state.launchingAppKey != null ||
            session.state.busyGroupId != null ||
            session.state.busyPackageName != null
        ) return
        session.update { it.copy(clearingStorageAppKey = item.launchKey) }
        scope.launch {
            val result = try {
                operationResult {
                    withContext(ioDispatcher) { clones.clearGroupAppStorage(item) }
                }.getOrElse(ClearCloneStorageResult::Failed)
            } finally {
                session.update { it.copy(clearingStorageAppKey = null) }
            }
            when (result) {
                ClearCloneStorageResult.Cleared ->
                    showMessage("已清除 ${item.appLabel} 的分身資料")
                ClearCloneStorageResult.SpaceNotFound -> showMessage("找不到這個分身空間")
                ClearCloneStorageResult.CloneNotFound -> showMessage("找不到這個分身 App")
                ClearCloneStorageResult.SpaceUnavailable ->
                    showMessage("「${item.groupName}」目前無法清除資料")
                is ClearCloneStorageResult.Failed ->
                    showMessage("清除資料失敗：${result.error.operationMessage()}")
            }
            refresh()
        }
    }

    fun clearAllGroupAppData(groupId: String) {
        val group = session.state.groups.firstOrNull { it.groupId == groupId }
            ?: run {
                showMessage("找不到這個分身空間")
                return
            }
        if (
            session.state.clearingStorageGroupId != null ||
            session.state.clearingStorageAppKey != null ||
            session.state.uninstallingAppKey != null ||
            session.state.launchingAppKey != null ||
            session.state.busyGroupId != null ||
            session.state.busyPackageName != null
        ) return
        session.update { it.copy(clearingStorageGroupId = groupId) }
        scope.launch {
            val result = try {
                operationResult {
                    withContext(ioDispatcher) { spaces.clearGroupStorage(groupId) }
                }.getOrElse(ClearSpaceStorageResult::Failed)
            } finally {
                session.update { it.copy(clearingStorageGroupId = null) }
            }
            when (result) {
                is ClearSpaceStorageResult.Cleared -> {
                    val message = if (result.clearedCloneCount == 0) {
                        "「${group.name}」內沒有可清除的 App 資料"
                    } else {
                        "已清除「${group.name}」中 ${result.clearedCloneCount} 個 App 的分身資料"
                    }
                    showMessage(message)
                }
                is ClearSpaceStorageResult.PartiallyCleared -> {
                    val appLabel = group.apps.firstOrNull {
                        it.app.packageName == result.failedPackageName
                    }?.appLabel ?: result.failedPackageName
                    showMessage(
                        "已清除「${group.name}」中 ${result.clearedCloneCount} 個 App 的分身資料；" +
                            "清除 $appLabel 時失敗：${result.error.operationMessage()}",
                    )
                }
                ClearSpaceStorageResult.SpaceNotFound -> showMessage("找不到這個分身空間")
                ClearSpaceStorageResult.SpaceUnavailable ->
                    showMessage("「${group.name}」目前無法清除資料")
                is ClearSpaceStorageResult.Failed ->
                    showMessage("清除「${group.name}」的 App 資料失敗：${result.error.operationMessage()}")
            }
            refresh()
        }
    }

    fun createShortcut(item: GroupAppItem) {
        if (session.state.shortcutAppKey != null) return
        session.update { it.copy(shortcutAppKey = item.launchKey) }
        scope.launch {
            val result = try {
                operationResult {
                    withContext(ioDispatcher) { clones.createShortcut(item) }
                }.getOrElse { error -> ShortcutCreationResult.Failed(error.operationMessage()) }
            } finally {
                session.update { it.copy(shortcutAppKey = null) }
            }
            when (result) {
                ShortcutCreationResult.Requested ->
                    showMessage("請在啟動器確認建立「${item.groupName} ${item.appLabel}」捷徑")
                ShortcutCreationResult.Updated ->
                    showMessage("已更新「${item.groupName} ${item.appLabel}」捷徑")
                ShortcutCreationResult.Unsupported ->
                    showMessage("目前的啟動器不支援固定捷徑")
                is ShortcutCreationResult.Failed ->
                    showMessage("建立捷徑失敗：${result.reason}")
            }
        }
    }

    fun repairClone(item: GroupAppItem) {
        if (session.state.repairingAppKey != null || session.state.launchingAppKey != null) return
        session.update { it.copy(repairingAppKey = item.launchKey) }
        scope.launch {
            val result = try {
                operationResult {
                    withContext(ioDispatcher) { clones.repairClone(item) }
                }.getOrElse { error ->
                    RepairExecutionResult.Failed("REPAIR_FAILED", error)
                }
            } finally {
                session.update { it.copy(repairingAppKey = null) }
            }
            when (result) {
                RepairExecutionResult.Completed ->
                    showMessage("${item.appLabel} 已重新同步，登入與資料均保留")
                RepairExecutionResult.ActionUnavailable ->
                    showMessage("請先重新安裝手機上的原始 App，再嘗試修復")
                RepairExecutionResult.DestructiveConfirmationRequired ->
                    showMessage("此修復會刪除資料，必須另行確認")
                is RepairExecutionResult.Failed ->
                    showMessage("修復失敗：${result.code}")
            }
            refresh()
        }
    }

    fun setClonePermission(item: GroupAppItem, permission: String, granted: Boolean) {
        scope.launch {
            val updated = operationResult {
                withContext(ioDispatcher) {
                    permissions.setClonePermission(item, permission, granted)
                }
            }.getOrElse { false }
            if (updated) {
                showMessage(
                    "${item.appLabel} 的${permissionLabel(permission)}已${if (granted) "允許" else "關閉"}",
                )
            } else {
                showMessage("無法更新 ${item.appLabel} 的${permissionLabel(permission)}")
            }
            refresh()
        }
    }

    private fun launchStatus(versionCode: Long?): String = when {
        versionCode == null -> "來源 App 已移除"
        else -> "可使用"
    }

    private fun permissionLabel(permission: String): String = when (permission) {
        android.Manifest.permission.CAMERA -> "相機權限"
        android.Manifest.permission.RECORD_AUDIO -> "麥克風權限"
        else -> "權限"
    }
    private fun reconcileAndRefresh() = session.reconcile(
        onStart = { content = content.copy(isRefreshing = true, error = null) },
        loadAndPublishGroups = ::loadAndPublishGroups,
        applyFullRefresh = ::applyFullRefresh,
        validatePickerSelection = validatePickerSelection,
        refresh = ::refresh,
    )
}
