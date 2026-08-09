package org.apptwin

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apptwin.groups.Group
import org.apptwin.compatibility.CompatibilityLevel
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupAppRemovalResult
import org.apptwin.groups.GroupAppState
import org.apptwin.groups.GroupHealth
import org.apptwin.groups.GroupReconciliationResult
import org.apptwin.gms.GmsGroupProductState
import org.apptwin.gms.GmsStartupResult
import org.apptwin.gms.usecases.GmsLifecycleResult
import org.apptwin.operations.OperationRecord
import org.apptwin.repair.RepairExecutionResult
import org.apptwin.revision.ActiveRevisionSummary
import org.apptwin.revision.InstalledAppEntry
import org.apptwin.runtime.GroupAppRuntimeSupport
import org.apptwin.runtime.RuntimeCompatibility
import org.apptwin.runtime.RuntimeLaunchResult
import org.apptwin.spaces.CloneLifecycleState
import org.apptwin.spaces.SpaceLifecycleState
import org.apptwin.spaces.SpaceStatePolicy

enum class MainDestination { HOME, SETTINGS }

data class AppItem(
    val entry: InstalledAppEntry,
    val isSynced: Boolean,
    val activeVersionCode: Long?,
    val groupCount: Int,
    val compatibility: CompatibilityLevel = CompatibilityLevel.UNTESTED,
)

data class GroupAppItem(
    val groupId: String,
    val groupName: String,
    val groupHealth: GroupHealth,
    val app: GroupApp,
    val appLabel: String,
    val versionName: String,
    val sourceInstalled: Boolean,
    val launchStatus: String,
    val lifecycle: CloneLifecycleState = CloneLifecycleState.READY,
    val cameraGranted: Boolean = false,
    val microphoneGranted: Boolean = false,
) {
    val launchKey: String = "$groupId:${app.packageName}"
}

data class GroupItem(
    val groupId: String,
    val name: String,
    val health: GroupHealth,
    val apps: List<GroupAppItem>,
    val lifecycle: SpaceLifecycleState = SpaceLifecycleState.READY,
    val gmsCompatibility: GmsGroupProductState? = null,
) {
    fun contains(packageName: String): Boolean = apps.any { it.app.packageName == packageName }
}

data class MainUiState(
    val destination: MainDestination = MainDestination.HOME,
    val apps: List<AppItem> = emptyList(),
    val groups: List<GroupItem> = emptyList(),
    val selectedGroupId: String? = null,
    val appPickerGroupId: String? = null,
    val showOnboarding: Boolean = false,
    val isRefreshing: Boolean = true,
    val isCreatingGroup: Boolean = false,
    val busyPackageName: String? = null,
    val busyGroupId: String? = null,
    val gmsBusyGroupId: String? = null,
    val launchingAppKey: String? = null,
    val uninstallingAppKey: String? = null,
    val shortcutAppKey: String? = null,
    val repairingAppKey: String? = null,
    val allFilesGranted: Boolean = false,
    val downloadCount: Int = 0,
    val photoCount: Int = 0,
    val dataWarnings: List<String> = emptyList(),
    val diagnosticsReport: String? = null,
    val diagnosticsReportId: Long = 0,
    val pendingDeepLink: String? = null,
    val deepLinkCandidates: List<GroupAppItem> = emptyList(),
    val isResolvingDeepLink: Boolean = false,
    val message: String? = null,
    val messageId: Long = 0,
)

internal data class StorageStatus(
    val granted: Boolean,
    val downloadCount: Int,
    val photoCount: Int,
)

internal data class MainRefreshSnapshot(
    val storage: StorageStatus,
    val entries: List<InstalledAppEntry>,
    val groups: List<Group>,
    val activeRevisions: Map<String, ActiveRevisionSummary?>,
    val dataWarnings: List<String>,
    val operations: List<OperationRecord> = emptyList(),
    val permissions: Map<String, ClonePermissionState> = emptyMap(),
    val gmsCompatibility: Map<String, GmsGroupProductState> = emptyMap(),
)

/** Blocking application operations. MainViewModel always invokes these on its IO dispatcher. */
internal interface MainOperations {
    suspend fun refreshSnapshot(): MainRefreshSnapshot
    suspend fun findGroup(groupId: String): Group?
    suspend fun createGroup(name: String): Group
    suspend fun renameGroup(groupId: String, name: String): Group?
    suspend fun deleteGroup(groupId: String): Group?
    suspend fun addAppToGroup(groupId: String, packageName: String): Group
    suspend fun launchGroupApp(item: GroupAppItem): RuntimeLaunchResult
    suspend fun uninstallGroupApp(item: GroupAppItem): GroupAppRemovalResult
    suspend fun createShortcut(item: GroupAppItem): ShortcutCreationResult
    suspend fun exportDiagnostics(): String
    suspend fun repairClone(item: GroupAppItem): RepairExecutionResult
    suspend fun setClonePermission(
        item: GroupAppItem,
        permission: String,
        granted: Boolean,
    ): Boolean
    suspend fun resolveDeepLink(uri: String): List<Pair<String, String>>
    suspend fun launchDeepLink(item: GroupAppItem, uri: String): RuntimeLaunchResult
    suspend fun reconcileGroups(): GroupReconciliationResult
    suspend fun reconcileAppRemovals()
    suspend fun reconcileApplicationOperations()
    suspend fun reconcileGms(): GmsStartupResult
    suspend fun grantGmsConsent(groupId: String)
    suspend fun enableGms(groupId: String): GmsLifecycleResult
    suspend fun disableGms(groupId: String): GmsLifecycleResult
    suspend fun resetGms(groupId: String, reenable: Boolean): GmsLifecycleResult
}

internal interface OnboardingStore {
    fun isCompleted(): Boolean
    fun markCompleted()
}

internal data class ClonePermissionState(
    val cameraGranted: Boolean,
    val microphoneGranted: Boolean,
)

private class AndroidOnboardingStore(application: Application) : OnboardingStore {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun isCompleted(): Boolean = preferences.getBoolean(COMPLETED_KEY, false)

    override fun markCompleted() {
        preferences.edit().putBoolean(COMPLETED_KEY, true).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "product-onboarding"
        const val COMPLETED_KEY = "completed"
    }
}

private object CompletedOnboardingStore : OnboardingStore {
    override fun isCompleted(): Boolean = true
    override fun markCompleted() = Unit
}

class MainViewModel internal constructor(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val operations: MainOperations,
    private val ioDispatcher: CoroutineDispatcher,
    private val onboardingStore: OnboardingStore = CompletedOnboardingStore,
) : AndroidViewModel(application) {
    @OptIn(ExperimentalCoroutinesApi::class)
    constructor(application: Application, savedStateHandle: SavedStateHandle) : this(
        application = application,
        savedStateHandle = savedStateHandle,
        operations = AndroidMainOperations(application),
        ioDispatcher = Dispatchers.IO.limitedParallelism(1),
        onboardingStore = AndroidOnboardingStore(application),
    )

    private var pendingLaunch: PendingLaunch? = null
    private var resolvingDeepLinkUri: String? = null
    private var resolvedDeepLinkIdentities: Set<Pair<String, String>>? = null
    private var refreshGeneration = 0L
    private var pickerRequestGeneration = 0L

    var uiState by mutableStateOf(
        MainUiState(
            destination = savedStateHandle.get<String>(DESTINATION_KEY)
                ?.let(::destinationFromSavedState)
                ?: MainDestination.HOME,
            selectedGroupId = savedStateHandle[SELECTED_GROUP_KEY],
            appPickerGroupId = savedStateHandle.get<String>(APP_PICKER_GROUP_KEY),
            showOnboarding = !onboardingStore.isCompleted(),
        ),
    )
        private set

    init {
        reconcileAndRefresh()
    }

    fun navigate(destination: MainDestination) {
        pickerRequestGeneration += 1
        savedStateHandle[DESTINATION_KEY] = destination.name
        savedStateHandle[SELECTED_GROUP_KEY] = null
        savedStateHandle[APP_PICKER_GROUP_KEY] = null
        uiState = uiState.copy(
            destination = destination,
            selectedGroupId = null,
            appPickerGroupId = null,
        )
    }

    fun openGroup(groupId: String) {
        if (uiState.groups.none { it.groupId == groupId }) {
            showMessage("找不到這個分身空間")
            return
        }
        savedStateHandle[DESTINATION_KEY] = MainDestination.HOME.name
        savedStateHandle[SELECTED_GROUP_KEY] = groupId
        savedStateHandle[APP_PICKER_GROUP_KEY] = null
        uiState = uiState.copy(
            destination = MainDestination.HOME,
            selectedGroupId = groupId,
            appPickerGroupId = null,
        )
    }

    fun closeGroup() {
        pickerRequestGeneration += 1
        savedStateHandle[SELECTED_GROUP_KEY] = null
        savedStateHandle[APP_PICKER_GROUP_KEY] = null
        uiState = uiState.copy(selectedGroupId = null, appPickerGroupId = null)
    }

    fun completeOnboarding() {
        if (!uiState.showOnboarding) return
        onboardingStore.markCompleted()
        uiState = uiState.copy(showOnboarding = false)
        if (pendingLaunch != null) refresh()
    }

    fun openAppPicker(groupId: String) {
        if (uiState.uninstallingAppKey != null) {
            showMessage("App 正在解除安裝，請稍候")
            return
        }
        val requestGeneration = ++pickerRequestGeneration
        viewModelScope.launch {
            val lookup = runCatching {
                withContext(ioDispatcher) { operations.findGroup(groupId) }
            }
            if (requestGeneration != pickerRequestGeneration) return@launch
            val group = lookup.getOrElse { error ->
                showMessage("讀取群組失敗：${error.userMessage()}")
                return@launch
            }
            when {
                group == null -> showMessage("找不到這個群組")
                group.health != GroupHealth.HEALTHY -> showMessage("這個群組目前無法加入 App")
                else -> {
                    savedStateHandle[DESTINATION_KEY] = MainDestination.HOME.name
                    savedStateHandle[SELECTED_GROUP_KEY] = groupId
                    savedStateHandle[APP_PICKER_GROUP_KEY] = groupId
                    uiState = uiState.copy(
                        destination = MainDestination.HOME,
                        selectedGroupId = groupId,
                        appPickerGroupId = groupId,
                    )
                }
            }
        }
    }

    fun closeAppPicker() {
        pickerRequestGeneration += 1
        savedStateHandle[APP_PICKER_GROUP_KEY] = null
        uiState = uiState.copy(appPickerGroupId = null)
    }

    fun refresh() {
        val generation = ++refreshGeneration
        uiState = uiState.copy(isRefreshing = true)
        viewModelScope.launch {
            val snapshot = runCatching {
                withContext(ioDispatcher) { operations.refreshSnapshot() }
            }.getOrElse { error ->
                if (generation == refreshGeneration) {
                    uiState = uiState.copy(isRefreshing = false)
                    showMessage("重新整理失敗：${error.userMessage()}")
                }
                return@launch
            }
            if (generation != refreshGeneration) return@launch
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
                        ) ==
                        RuntimeCompatibility.VERIFIED
                    ) {
                        CompatibilityLevel.PARTIAL
                    } else {
                        CompatibilityLevel.UNTESTED
                    },
                )
            }
            val groupItems = snapshot.groups.sortedByDescending(Group::createdAtEpochMillis)
                .map { group ->
                    val spaceState = SpaceStatePolicy.assess(group, snapshot.operations)
                    val cloneStates = spaceState.clones.associateBy { it.packageName }
                    GroupItem(
                        groupId = group.id,
                        name = group.name,
                        health = group.health,
                        lifecycle = spaceState.lifecycle,
                        gmsCompatibility = snapshot.gmsCompatibility[group.id],
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
                                launchStatus = launchStatus(
                                    app.packageName,
                                    source?.versionCode,
                                ),
                                lifecycle = cloneStates.getValue(app.packageName).lifecycle,
                                cameraGranted = snapshot.permissions["${group.id}:${app.packageName}"]
                                    ?.cameraGranted == true,
                                microphoneGranted = snapshot.permissions[
                                    "${group.id}:${app.packageName}"
                                ]?.microphoneGranted == true,
                            )
                        },
                    )
                }
            val restoredPickerId = uiState.appPickerGroupId?.takeIf { selectedId ->
                groupItems.any { it.groupId == selectedId }
            }
            val restoredSelectedId = uiState.selectedGroupId?.takeIf { selectedId ->
                groupItems.any { it.groupId == selectedId }
            }
            if (restoredPickerId != uiState.appPickerGroupId) {
                savedStateHandle[APP_PICKER_GROUP_KEY] = null
            }
            if (restoredSelectedId != uiState.selectedGroupId) {
                savedStateHandle[SELECTED_GROUP_KEY] = null
            }
            val warningsChanged = snapshot.dataWarnings != uiState.dataWarnings
            uiState = uiState.copy(
                apps = appItems,
                groups = groupItems,
                selectedGroupId = restoredSelectedId,
                appPickerGroupId = restoredPickerId,
                isRefreshing = false,
                allFilesGranted = snapshot.storage.granted,
                downloadCount = snapshot.storage.downloadCount,
                photoCount = snapshot.storage.photoCount,
                dataWarnings = snapshot.dataWarnings,
            )
            presentResolvedDeepLink(groupItems, waitForRefresh = false)
            if (snapshot.dataWarnings.isNotEmpty() && warningsChanged) {
                showMessage("偵測到 ${snapshot.dataWarnings.size} 筆資料完整性問題；原始資料已保留")
            }
            pendingLaunch?.let { target ->
                val pending = groupItems.asSequence()
                    .flatMap { it.apps.asSequence() }
                    .firstOrNull { item ->
                        item.app.packageName == target.packageName &&
                            (target.groupId == null || item.groupId == target.groupId)
                    }
                if (pending != null) {
                    pendingLaunch = null
                    launchGroupApp(pending)
                }
            }
        }
    }

    fun createGroup(name: String) {
        if (uiState.isCreatingGroup) return
        uiState = uiState.copy(isCreatingGroup = true)
        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) { operations.createGroup(name) }
            }
            uiState = uiState.copy(isCreatingGroup = false)
            result.onSuccess { group ->
                showMessage("已建立「${group.name}」")
                refresh()
            }.onFailure { error -> showMessage("建立群組失敗：${error.userMessage()}") }
        }
    }

    fun renameGroup(groupId: String, name: String) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) { operations.renameGroup(groupId, name) }
            }
            result.onSuccess { renamed ->
                if (renamed == null) showMessage("找不到這個群組")
                else {
                    showMessage("已重新命名為「${renamed.name}」")
                    refresh()
                }
            }.onFailure { error -> showMessage("重新命名失敗：${error.userMessage()}") }
        }
    }

    fun deleteGroup(groupId: String) {
        if (uiState.busyGroupId != null || uiState.uninstallingAppKey != null) return
        uiState = uiState.copy(busyGroupId = groupId)
        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) { operations.deleteGroup(groupId) }
            }
            uiState = uiState.copy(busyGroupId = null)
            result.onSuccess { group ->
                if (group == null) {
                    showMessage("找不到這個群組")
                } else {
                    showMessage("已刪除「${group.name}」及其中的所有資料")
                    if (uiState.selectedGroupId == groupId) closeGroup()
                    if (uiState.appPickerGroupId == groupId) closeAppPicker()
                }
                refresh()
            }.onFailure { error ->
                showMessage("刪除群組失敗：${error.userMessage()}")
                refresh()
            }
        }
    }

    fun enableGms(groupId: String, grantConsent: Boolean = false) {
        runGmsAction(groupId, "啟用") {
            if (grantConsent) operations.grantGmsConsent(groupId)
            operations.enableGms(groupId)
        }
    }

    fun disableGms(groupId: String) {
        runGmsAction(groupId, "停用") { operations.disableGms(groupId) }
    }

    fun resetGms(groupId: String, reenable: Boolean) {
        runGmsAction(groupId, "重設") { operations.resetGms(groupId, reenable) }
    }

    fun selectApp(app: AppItem) {
        if (uiState.uninstallingAppKey != null || uiState.busyPackageName != null) return
        val groupId = uiState.appPickerGroupId ?: return
        uiState = uiState.copy(busyPackageName = app.entry.packageName)
        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) {
                    operations.addAppToGroup(groupId, app.entry.packageName)
                }
            }
            uiState = uiState.copy(busyPackageName = null)
            result.onSuccess { group ->
                closeAppPicker()
                showMessage("已將 ${app.entry.label} 加入「${group.name}」")
                refresh()
            }.onFailure { error -> showMessage(error.userMessage()) }
        }
    }

    fun launchGroupApp(item: GroupAppItem) {
        if (uiState.launchingAppKey != null || uiState.uninstallingAppKey != null) return
        if (item.groupHealth != GroupHealth.HEALTHY) {
            showMessage(
                if (item.groupHealth == GroupHealth.DAMAGED) {
                    "「${item.groupName}」的隔離環境已損毀"
                } else {
                    "「${item.groupName}」目前無法啟動 App"
                },
            )
            return
        }
        if (!item.sourceInstalled) {
            showMessage("來源 App 已移除，暫時無法啟動")
            return
        }
        uiState = uiState.copy(launchingAppKey = item.launchKey)
        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) { operations.launchGroupApp(item) }
            }.getOrElse { error -> RuntimeLaunchResult.Failed(error.userMessage(), error) }
            uiState = uiState.copy(launchingAppKey = null)
            when (result) {
                is RuntimeLaunchResult.Started ->
                    showMessage("${item.appLabel} 已從「${item.groupName}」啟動")
                is RuntimeLaunchResult.Failed -> showMessage("啟動失敗：${result.reason}")
            }
            refresh()
        }
    }

    fun uninstallGroupApp(item: GroupAppItem) {
        if (
            uiState.uninstallingAppKey != null ||
            uiState.launchingAppKey != null ||
            uiState.busyGroupId != null ||
            uiState.busyPackageName != null
        ) return
        uiState = uiState.copy(uninstallingAppKey = item.launchKey)
        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) { operations.uninstallGroupApp(item) }
            }.getOrElse { error ->
                GroupAppRemovalResult.Failed(error.userMessage(), error)
            }
            uiState = uiState.copy(uninstallingAppKey = null)
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

    fun createShortcut(item: GroupAppItem) {
        if (uiState.shortcutAppKey != null) return
        uiState = uiState.copy(shortcutAppKey = item.launchKey)
        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) { operations.createShortcut(item) }
            }.getOrElse { error -> ShortcutCreationResult.Failed(error.userMessage()) }
            uiState = uiState.copy(shortcutAppKey = null)
            when (result) {
                ShortcutCreationResult.Requested ->
                    showMessage("請在啟動器確認建立「${item.groupName} ${item.appLabel}」捷徑")
                ShortcutCreationResult.Unsupported ->
                    showMessage("目前的啟動器不支援固定捷徑")
                is ShortcutCreationResult.Failed ->
                    showMessage("建立捷徑失敗：${result.reason}")
            }
        }
    }

    fun exportDiagnostics() {
        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) { operations.exportDiagnostics() }
            }
            result.onSuccess { report ->
                uiState = uiState.copy(
                    diagnosticsReport = report,
                    diagnosticsReportId = uiState.diagnosticsReportId + 1,
                )
            }.onFailure { error ->
                showMessage("產生診斷報告失敗：${error.userMessage()}")
            }
        }
    }

    fun repairClone(item: GroupAppItem) {
        if (uiState.repairingAppKey != null || uiState.launchingAppKey != null) return
        uiState = uiState.copy(repairingAppKey = item.launchKey)
        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) { operations.repairClone(item) }
            }.getOrElse { error ->
                RepairExecutionResult.Failed("REPAIR_FAILED", error)
            }
            uiState = uiState.copy(repairingAppKey = null)
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
        viewModelScope.launch {
            val updated = runCatching {
                withContext(ioDispatcher) {
                    operations.setClonePermission(item, permission, granted)
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

    fun openDeepLink(uri: String) {
        if (uiState.pendingDeepLink != null || uiState.isResolvingDeepLink) return
        resolvingDeepLinkUri = uri
        uiState = uiState.copy(isResolvingDeepLink = true)
        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) { operations.resolveDeepLink(uri) }
            }
            result.onSuccess { identities ->
                resolvedDeepLinkIdentities = identities.toSet()
                presentResolvedDeepLink(uiState.groups, waitForRefresh = uiState.isRefreshing)
            }.onFailure { error ->
                resolvingDeepLinkUri = null
                resolvedDeepLinkIdentities = null
                uiState = uiState.copy(isResolvingDeepLink = false)
                showMessage("無法解析連結：${error.userMessage()}")
            }
        }
    }

    fun closeDeepLink() {
        resolvingDeepLinkUri = null
        resolvedDeepLinkIdentities = null
        uiState = uiState.copy(
            pendingDeepLink = null,
            deepLinkCandidates = emptyList(),
            isResolvingDeepLink = false,
        )
    }

    fun launchDeepLink(item: GroupAppItem) {
        val uri = uiState.pendingDeepLink ?: return
        closeDeepLink()
        uiState = uiState.copy(launchingAppKey = item.launchKey)
        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) { operations.launchDeepLink(item, uri) }
            }.getOrElse { error -> RuntimeLaunchResult.Failed(error.userMessage(), error) }
            uiState = uiState.copy(launchingAppKey = null)
            if (result is RuntimeLaunchResult.Failed) {
                showMessage("連結開啟失敗：${result.reason}")
            }
        }
    }

    fun consumeDiagnostics(reportId: Long) {
        if (uiState.diagnosticsReportId == reportId) {
            uiState = uiState.copy(diagnosticsReport = null)
        }
    }

    fun launchGroupApp(groupId: String, packageName: String) {
        if (uiState.showOnboarding) {
            pendingLaunch = PendingLaunch(groupId, packageName)
            return
        }
        val item = uiState.groups.asSequence()
            .flatMap { it.apps.asSequence() }
            .firstOrNull { it.groupId == groupId && it.app.packageName == packageName }
        if (item != null) {
            launchGroupApp(item)
        } else {
            pendingLaunch = PendingLaunch(groupId, packageName)
        }
    }

    fun launchFirst(packageName: String) {
        val item = uiState.groups.asSequence()
            .flatMap { it.apps.asSequence() }
            .firstOrNull { it.app.packageName == packageName }
        if (item != null) launchGroupApp(item) else pendingLaunch = PendingLaunch(null, packageName)
    }

    fun consumeMessage(messageId: Long) {
        if (uiState.messageId == messageId) uiState = uiState.copy(message = null)
    }

    private fun reconcileAndRefresh() {
        uiState = uiState.copy(isRefreshing = true)
        viewModelScope.launch {
            val groupResult = withContext(ioDispatcher) {
                runCatching { operations.reconcileGroups() }
            }
            val (appRemovalResult, applicationOperationResult, gmsResult) =
                withContext(ioDispatcher) {
                    Triple(
                        runCatching { operations.reconcileAppRemovals() },
                        runCatching { operations.reconcileApplicationOperations() },
                        runCatching { operations.reconcileGms() },
                    )
                }
            val notices = buildList {
                groupResult.onSuccess { result ->
                    if (result.loadIssues.isNotEmpty()) {
                        add("偵測到 ${result.loadIssues.size} 筆群組資料無法讀取；原始資料已保留")
                    }
                }.onFailure { error ->
                    add("部分群組環境需要處理：${error.userMessage()}")
                }
                appRemovalResult.onFailure { error ->
                    add("部分 App 解除安裝作業需要處理：${error.userMessage()}")
                }
                applicationOperationResult.onFailure { error ->
                    add("部分分身空間作業需要處理：${error.userMessage()}")
                }
                gmsResult.onFailure { error ->
                    add("Google 服務相容資料需要處理：${error.userMessage()}")
                }
            }
            if (notices.isNotEmpty()) showMessage(notices.joinToString("\n"))
            refresh()
        }
    }

    private fun runGmsAction(
        groupId: String,
        actionLabel: String,
        action: suspend () -> GmsLifecycleResult,
    ) {
        if (uiState.gmsBusyGroupId != null || uiState.busyGroupId != null) return
        uiState = uiState.copy(gmsBusyGroupId = groupId)
        viewModelScope.launch {
            val result = runCatching { withContext(ioDispatcher) { action() } }
            uiState = uiState.copy(gmsBusyGroupId = null)
            result.onSuccess { lifecycle ->
                showMessage(gmsResultMessage(actionLabel, lifecycle))
                refresh()
            }.onFailure { error ->
                showMessage("$actionLabel Google 服務相容功能失敗：${error.userMessage()}")
                refresh()
            }
        }
    }

    private fun gmsResultMessage(actionLabel: String, result: GmsLifecycleResult): String =
        when (result) {
            is GmsLifecycleResult.Completed -> "已${actionLabel} Google 服務相容功能"
            is GmsLifecycleResult.AlreadySatisfied -> "Google 服務相容功能已是要求的狀態"
            GmsLifecycleResult.ConsentRequired -> "啟用前必須同意 Google 網路連線說明"
            GmsLifecycleResult.TrustedReleaseUnavailable ->
                "目前沒有可用且受信任的 microG 版本"
            GmsLifecycleResult.DestructiveConfirmationRequired -> "重設前必須確認清除資料"
            is GmsLifecycleResult.RetryScheduled ->
                "作業暫未完成，稍後會重試（${result.failureCode}）"
            is GmsLifecycleResult.Rejected -> "作業遭拒（${result.failureCode}）"
        }

    private fun launchStatus(packageName: String, versionCode: Long?): String = when {
        versionCode == null -> "來源 App 已移除"
        GroupAppRuntimeSupport.compatibility(
            packageName,
            versionCode,
            android.os.Build.VERSION.SDK_INT,
        ) == RuntimeCompatibility.VERIFIED ->
            "已通過實機啟動驗證"
        else -> "尚未完成實機相容驗證"
    }

    private fun showMessage(message: String) {
        uiState = uiState.copy(message = message, messageId = uiState.messageId + 1)
    }

    private fun presentResolvedDeepLink(
        groups: List<GroupItem>,
        waitForRefresh: Boolean,
    ) {
        val uri = resolvingDeepLinkUri ?: return
        val identitySet = resolvedDeepLinkIdentities ?: return
        val candidates = groups.asSequence()
            .flatMap { it.apps.asSequence() }
            .filter { (it.groupId to it.app.packageName) in identitySet }
            .toList()
        if (candidates.isEmpty() && waitForRefresh) return

        resolvingDeepLinkUri = null
        resolvedDeepLinkIdentities = null
        if (candidates.isEmpty()) {
            uiState = uiState.copy(isResolvingDeepLink = false)
            showMessage("沒有可開啟此連結的分身 App；請先啟動並完成 App 設定")
        } else {
            uiState = uiState.copy(
                pendingDeepLink = uri,
                deepLinkCandidates = candidates,
                isResolvingDeepLink = false,
            )
        }
    }

    private fun permissionLabel(permission: String): String = when (permission) {
        android.Manifest.permission.CAMERA -> "相機權限"
        android.Manifest.permission.RECORD_AUDIO -> "麥克風權限"
        else -> "權限"
    }

    private companion object {
        const val DESTINATION_KEY = "main.destination"
        const val SELECTED_GROUP_KEY = "main.selectedGroupId"
        const val APP_PICKER_GROUP_KEY = "main.appPickerGroupId"

        fun destinationFromSavedState(value: String): MainDestination? =
            runCatching { MainDestination.valueOf(value) }.getOrNull()
    }

    private data class PendingLaunch(val groupId: String?, val packageName: String)
}

private fun Throwable.userMessage(): String = message ?: javaClass.simpleName
