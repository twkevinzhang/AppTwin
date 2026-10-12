package org.apptwin

import android.app.Application
import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.apptwin.archive.ArchiveExportSettingsStore
import org.apptwin.archive.SharedPreferencesArchiveExportSettingsStore
import org.apptwin.archive.SpaceArchiveCompression
import org.apptwin.groups.Group
import org.apptwin.compatibility.CompatibilityLevel
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupAppRemovalResult
import org.apptwin.groups.GroupAppState
import org.apptwin.groups.GroupHealth
import org.apptwin.groups.GroupReconciliationResult
import org.apptwin.gms.GmsGroupProductState
import org.apptwin.gms.GmsStartupResult
import org.apptwin.operations.OperationRecord
import org.apptwin.permissions.ClonePermissionSummary
import org.apptwin.repair.RepairExecutionResult
import org.apptwin.revision.ActiveRevisionSummary
import org.apptwin.revision.InstalledAppEntry
import org.apptwin.runtime.GroupAppRuntimeSupport
import org.apptwin.runtime.RuntimeCompatibility
import org.apptwin.runtime.RuntimeLaunchResult
import org.apptwin.spaces.CloneLifecycleState
import org.apptwin.spaces.SpaceLifecycleState
import org.apptwin.spaces.SpaceStatePolicy
import org.apptwin.usecases.ClearCloneStorageResult
import org.apptwin.usecases.ClearSpaceStorageResult

enum class MainDestination { HOME, SETTINGS, SPACE_DETAILS }


private const val SOURCE_SYNC_PENDING = "正在同步"

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
    val launchKey: String = GroupAppLaunchContract.launchKey(groupId, app.packageName)
    val canAttemptLaunch: Boolean
        get() = sourceInstalled || launchStatus == SOURCE_SYNC_PENDING
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
    val selectedSpaceId: String? = null,
    val spaceDetails: SpaceDetails? = null,
    val spaceDetailsError: String? = null,
    val spaceBackgroundHealth: SpaceBackgroundHealth? = null,
    val isSpaceBackgroundRetrying: Boolean = false,
    val isLoadingSpaceDetails: Boolean = false,
    val createSpaceError: String? = null,
    val isSpaceActionBusy: Boolean = false,
    val apps: List<AppItem> = emptyList(),
    val groups: List<GroupItem> = emptyList(),
    val appPickerGroupId: String? = null,
    val showOnboarding: Boolean = false,
    val isRefreshing: Boolean = true,
    val isCreatingGroup: Boolean = false,
    val busyPackageName: String? = null,
    val busyGroupId: String? = null,
    val launchingAppKey: String? = null,
    val uninstallingAppKey: String? = null,
    val clearingStorageAppKey: String? = null,
    val clearingStorageGroupId: String? = null,
    val archiveBusyGroupId: String? = null,
    val isCreatingSpaceFromBackup: Boolean = false,
    val archiveCompression: SpaceArchiveCompression = SpaceArchiveCompression.MEDIUM,
    val globalBackupLocation: String? = null,
    val shortcutAppKey: String? = null,
    val repairingAppKey: String? = null,
    val allFilesGranted: Boolean = false,
    val downloadCount: Int = 0,
    val photoCount: Int = 0,
    val clonePermissions: List<ClonePermissionSummary> = emptyList(),
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
    val clonePermissions: List<ClonePermissionSummary> = emptyList(),
    val gmsCompatibility: Map<String, GmsGroupProductState> = emptyMap(),
)

/** The durable data needed to draw the space cards without starting the virtual runtime. */
internal data class MainGroupSnapshot(
    val groups: List<Group>,
    val operations: List<OperationRecord>,
    val dataWarnings: List<String>,
)

internal sealed interface DeleteGroupResult {
    data object NotFound : DeleteGroupResult
    data class Deleted(
        val group: Group,
        val cleanupWarning: String? = null,
    ) : DeleteGroupResult
}

internal data class SpaceArchiveExportResult(
    val groupName: String,
    val bytesWritten: Long,
)

internal data class CreateSpaceFromBackupResult(
    val group: Group,
)

/** Blocking application operations. MainViewModel always invokes these on its IO dispatcher. */
internal interface MainOperations {
    suspend fun loadGroupSnapshot(): MainGroupSnapshot
    suspend fun refreshSnapshot(groups: MainGroupSnapshot): MainRefreshSnapshot
    suspend fun globalBackupLocation(): String? = null
    suspend fun loadSpaceDetails(groupId: String): SpaceDetails? = null
    suspend fun loadSpaceBackgroundHealth(groupId: String): SpaceBackgroundHealth = SpaceBackgroundHealth(groupId)
    suspend fun retrySpaceBackground(groupId: String): Boolean = false
    suspend fun addSpaceAccount(groupId: String, type: String) = Unit
    suspend fun synchronizeSpaceAccount(groupId: String, account: SpaceAccountEntry, authority: String): String = "同步未支援"
    suspend fun removeSpaceAccount(groupId: String, account: SpaceAccountEntry): AccountRemovalResult = AccountRemovalResult.REJECTED
    suspend fun deleteBackup(id: String) = Unit
    suspend fun setGlobalBackupLocation(uri: Uri) = Unit
    suspend fun exportToGlobalLocation(groupId: String, compression: SpaceArchiveCompression): SpaceArchiveExportResult = error("尚未設定備份位置")
    suspend fun findGroup(groupId: String): Group?
    suspend fun createGroup(name: String): Group
    suspend fun renameGroup(groupId: String, name: String): Group?
    suspend fun deleteGroup(groupId: String): DeleteGroupResult
    suspend fun exportSpace(
        groupId: String,
        destination: Uri,
        compression: SpaceArchiveCompression,
    ): SpaceArchiveExportResult
    suspend fun createSpaceFromBackup(source: Uri): CreateSpaceFromBackupResult
    suspend fun addAppToGroup(groupId: String, packageName: String): Group
    suspend fun launchGroupApp(item: GroupAppItem): RuntimeLaunchResult
    suspend fun uninstallGroupApp(item: GroupAppItem): GroupAppRemovalResult
    suspend fun clearGroupAppStorage(item: GroupAppItem): ClearCloneStorageResult
    suspend fun clearGroupStorage(groupId: String): ClearSpaceStorageResult
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

private object DefaultArchiveExportSettingsStore : ArchiveExportSettingsStore {
    override fun load(): SpaceArchiveCompression = SpaceArchiveCompression.MEDIUM
    override fun save(compression: SpaceArchiveCompression) = Unit
}

class MainViewModel internal constructor(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val operations: MainOperations,
    private val ioDispatcher: CoroutineDispatcher,
    private val onboardingStore: OnboardingStore = CompletedOnboardingStore,
    private val archiveExportSettingsStore: ArchiveExportSettingsStore =
        DefaultArchiveExportSettingsStore,
    private val launchDispatcher: CoroutineDispatcher = ioDispatcher,
) : AndroidViewModel(application) {
    @OptIn(ExperimentalCoroutinesApi::class)
    constructor(application: Application, savedStateHandle: SavedStateHandle) : this(
        application = application,
        savedStateHandle = savedStateHandle,
        operations = AndroidMainOperations(application),
        ioDispatcher = Dispatchers.IO.limitedParallelism(1),
        launchDispatcher = Dispatchers.IO.limitedParallelism(1),
        onboardingStore = AndroidOnboardingStore(application),
        archiveExportSettingsStore = SharedPreferencesArchiveExportSettingsStore(application),
    )

    private var pendingLaunch: PendingLaunch? = null
    private var resolvingDeepLinkUri: String? = null
    private var resolvedDeepLinkIdentities: Set<Pair<String, String>>? = null
    private var spaceDetailsGeneration = 0L
    private var backgroundGeneration = 0L
    private var backgroundJob: kotlinx.coroutines.Job? = null
    private var refreshGeneration = 0L
    private var pickerRequestGeneration = 0L
    private var launchBlockedByRecovery = true
    private var recoveryInProgress = false
    private val appLaunchMutex = Mutex()

    var uiState by mutableStateOf(
        MainUiState(
            destination = savedStateHandle.get<String>(DESTINATION_KEY)
                ?.let(::destinationFromSavedState)
                ?: MainDestination.HOME,
            selectedSpaceId = savedStateHandle.get<String>(SELECTED_SPACE_KEY),
            appPickerGroupId = savedStateHandle.get<String>(APP_PICKER_GROUP_KEY),
            showOnboarding = !onboardingStore.isCompleted(),
            archiveCompression = archiveExportSettingsStore.load(),
        ),
    )
        private set

    init {
        if (uiState.destination == MainDestination.SPACE_DETAILS) {
            if (uiState.selectedSpaceId == null) navigate(MainDestination.HOME) else refreshSpaceDetails()
        }
        reconcileAndRefresh()
    }

    fun closeSpaceDetails() { navigate(MainDestination.HOME) }
    fun dismissCreateSpaceError() { uiState = uiState.copy(createSpaceError = null) }
    fun openSpaceDetails(groupId: String) {
        savedStateHandle[SELECTED_SPACE_KEY] = groupId
        savedStateHandle[DESTINATION_KEY] = MainDestination.SPACE_DETAILS.name
        uiState = uiState.copy(selectedSpaceId = groupId, destination = MainDestination.SPACE_DETAILS, spaceDetails = null, spaceDetailsError = null, spaceBackgroundHealth = null, isSpaceBackgroundRetrying = false)
        refreshSpaceDetails()
    }
    fun refreshSpaceDetails() {
        val id = uiState.selectedSpaceId ?: return
        val generation = ++spaceDetailsGeneration
        uiState = uiState.copy(isLoadingSpaceDetails = true, spaceDetailsError = null)
        viewModelScope.launch {
            val result = runCatching { withContext(ioDispatcher) { operations.loadSpaceDetails(id) } }
            if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) throw result.exceptionOrNull()!!
            if (generation != spaceDetailsGeneration || uiState.selectedSpaceId != id || uiState.destination != MainDestination.SPACE_DETAILS) return@launch
            if (uiState.selectedSpaceId == id) {
                if (result.isSuccess && result.getOrNull() == null) {
                    savedStateHandle.remove<String>(SELECTED_SPACE_KEY)
                    uiState = uiState.copy(selectedSpaceId = null, spaceDetails = null, isLoadingSpaceDetails = false)
                    navigate(MainDestination.HOME)
                } else uiState = uiState.copy(spaceDetails = result.getOrNull(), isLoadingSpaceDetails = false, spaceDetailsError = result.exceptionOrNull()?.userMessage())
            }
            result.onFailure { showMessage(it.userMessage()) }
        }
    }
    fun refreshSpaceBackgroundHealth() {
        val id = uiState.selectedSpaceId ?: return
        if (uiState.destination != MainDestination.SPACE_DETAILS || uiState.isSpaceBackgroundRetrying) return
        backgroundJob?.cancel()
        val generation = ++backgroundGeneration
        backgroundJob = viewModelScope.launch {
            val result = runCatching { withContext(ioDispatcher) { operations.loadSpaceBackgroundHealth(id) } }
            if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) throw result.exceptionOrNull()!!
            if (generation != backgroundGeneration || uiState.selectedSpaceId != id || uiState.destination != MainDestination.SPACE_DETAILS) return@launch
            uiState = uiState.copy(spaceBackgroundHealth = result.getOrElse { SpaceBackgroundHealth(id, error = "無法讀取背景服務狀態") })
        }
    }
    fun stopSpaceBackgroundObservation() {
        ++backgroundGeneration
        backgroundJob?.cancel()
        uiState = uiState.copy(isSpaceBackgroundRetrying = false)
    }
    fun retrySpaceBackground() {
        val id = uiState.selectedSpaceId ?: return
        if (uiState.destination != MainDestination.SPACE_DETAILS || uiState.isSpaceBackgroundRetrying) return
        val health = uiState.spaceBackgroundHealth?.takeIf { it.groupId == id } ?: return
        if (!health.retryable) return
        backgroundJob?.cancel()
        val generation = ++backgroundGeneration
        uiState = uiState.copy(isSpaceBackgroundRetrying = true)
        backgroundJob = viewModelScope.launch {
            val result = runCatching { withContext(ioDispatcher) {
                val accepted = operations.retrySpaceBackground(id)
                var health = operations.loadSpaceBackgroundHealth(id)
                if (accepted) {
                    var checks = 0
                    while ((health.microg == SpaceBackgroundStatus.STARTING || health.tcp == SpaceBackgroundStatus.STARTING) && checks++ < 20) {
                        kotlinx.coroutines.delay(2_000L)
                        health = operations.loadSpaceBackgroundHealth(id)
                    }
                    if (health.microg == SpaceBackgroundStatus.STARTING || health.tcp == SpaceBackgroundStatus.STARTING) health.copy(error = "連線尚未恢復，請稍後檢查") else health
                } else health.copy(error = "重試未被接受，請確認背景服務已開啟")
            } }
            if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) throw result.exceptionOrNull()!!
            if (generation != backgroundGeneration || uiState.selectedSpaceId != id || uiState.destination != MainDestination.SPACE_DETAILS) return@launch
            uiState = uiState.copy(isSpaceBackgroundRetrying = false,
                spaceBackgroundHealth = result.getOrElse { SpaceBackgroundHealth(id, error = "重試失敗，請再試一次") })
        }
    }

    fun addSpaceAccount(type: String) = spaceAction { id -> operations.addSpaceAccount(id, type) }
    fun synchronizeSpaceAccount(account: SpaceAccountEntry, authority: String) = spaceAction { id -> showMessage(operations.synchronizeSpaceAccount(id, account, authority)) }
    fun removeSpaceAccount(account: SpaceAccountEntry) = spaceAction { id ->
        when (operations.removeSpaceAccount(id, account)) {
            AccountRemovalResult.REMOVED -> showMessage("帳號已移除")
            AccountRemovalResult.CONFIRMATION_OPENED -> showMessage("已開啟帳號移除確認")
            AccountRemovalResult.REJECTED -> showMessage("帳號服務未允許移除")
        }
    }
    fun deleteBackup(id: String) = spaceAction { operations.deleteBackup(id) }
    fun setGlobalBackupLocation(uri: Uri) {
        viewModelScope.launch { runCatching { withContext(ioDispatcher) { operations.setGlobalBackupLocation(uri); operations.globalBackupLocation() } }.onSuccess { uiState = uiState.copy(globalBackupLocation = it) }.onFailure { showMessage(it.userMessage()) }; refreshSpaceDetails() }
    }
    fun exportToGlobalLocation(groupId: String) {
        if (uiState.isSpaceActionBusy) return
        uiState = uiState.copy(isSpaceActionBusy = true)
        viewModelScope.launch { runCatching { withContext(ioDispatcher) { operations.exportToGlobalLocation(groupId, uiState.archiveCompression) } }.onSuccess { showMessage("已建立備份"); refreshSpaceDetails() }.onFailure { showMessage(it.userMessage()) }; uiState = uiState.copy(isSpaceActionBusy = false) }
    }
    fun setSpacePermission(packageName: String, permission: String, granted: Boolean) {
        val item = uiState.groups.firstOrNull { it.groupId == uiState.selectedSpaceId }?.apps?.firstOrNull { it.app.packageName == packageName } ?: return
        spaceAction { check(operations.setClonePermission(item, permission, granted)) { "權限設定失敗" } }
    }
    private fun spaceAction(action: suspend (String) -> Unit) {
        val id = uiState.selectedSpaceId ?: return
        if (uiState.isSpaceActionBusy) return
        uiState = uiState.copy(isSpaceActionBusy = true)
        viewModelScope.launch { try { runCatching { withContext(ioDispatcher) { action(id) } }.onFailure { showMessage(it.userMessage()) }; refreshSpaceDetails() } finally { uiState = uiState.copy(isSpaceActionBusy = false) } }
    }

    fun navigate(destination: MainDestination) {
        spaceDetailsGeneration += 1
        stopSpaceBackgroundObservation()
        uiState = uiState.copy(isSpaceBackgroundRetrying = false)
        if (destination == MainDestination.SETTINGS) viewModelScope.launch {
            val location = withContext(ioDispatcher) { operations.globalBackupLocation() }
            uiState = uiState.copy(globalBackupLocation = location)
        }
        pickerRequestGeneration += 1
        savedStateHandle[DESTINATION_KEY] = destination.name
        savedStateHandle[APP_PICKER_GROUP_KEY] = null
        uiState = uiState.copy(
            destination = destination,
            appPickerGroupId = null,
        )
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
                    savedStateHandle[APP_PICKER_GROUP_KEY] = groupId
                    uiState = uiState.copy(
                        destination = MainDestination.HOME,
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
        if (uiState.destination == MainDestination.SPACE_DETAILS && uiState.selectedSpaceId != null) refreshSpaceDetails()
        if (launchBlockedByRecovery) {
            reconcileAndRefresh()
            return
        }
        val generation = ++refreshGeneration
        uiState = uiState.copy(isRefreshing = true)
        viewModelScope.launch {
            val groups = loadAndPublishGroups(generation) ?: return@launch
            applyFullRefresh(generation, groups)
        }
    }

    private suspend fun loadAndPublishGroups(generation: Long): MainGroupSnapshot? {
        val snapshot = runCatching {
            withContext(ioDispatcher) { operations.loadGroupSnapshot() }
        }.getOrElse { error ->
            if (generation == refreshGeneration) {
                uiState = uiState.copy(isRefreshing = false)
                showMessage("讀取空間失敗：${error.userMessage()}")
            }
            return null
        }
        if (generation != refreshGeneration) return null
        publishGroups(snapshot)
        return snapshot
    }

    private suspend fun applyFullRefresh(generation: Long, groups: MainGroupSnapshot) {
        if (generation != refreshGeneration) return
        val snapshot = runCatching {
            withContext(ioDispatcher) { operations.refreshSnapshot(groups) }
        }.getOrElse { error ->
            if (generation == refreshGeneration) {
                uiState = uiState.copy(isRefreshing = false)
                showMessage("重新整理詳細資料失敗：${error.userMessage()}")
            }
            return
        }
        if (generation != refreshGeneration) return
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
        val warningsChanged = snapshot.dataWarnings != uiState.dataWarnings
        updateAppPickerGroup(groupItems)
        uiState = uiState.copy(
            apps = appItems,
            groups = groupItems,
            isRefreshing = false,
            allFilesGranted = snapshot.storage.granted,
            downloadCount = snapshot.storage.downloadCount,
            photoCount = snapshot.storage.photoCount,
            clonePermissions = snapshot.clonePermissions,
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
                if (
                    target.groupId != null &&
                    pending.lifecycle !in setOf(CloneLifecycleState.READY, CloneLifecycleState.PREPARING)
                ) {
                    pendingLaunch = null
                    showUnavailableShortcutMessage(pending)
                } else if (tryLaunchGroupApp(pending) != LaunchAttempt.BUSY) {
                    pendingLaunch = null
                }
            } else if (target.groupId != null) {
                pendingLaunch = null
                showMessage("此捷徑對應的分身已不存在；請從 AppTwin 重新建立捷徑")
            }
        }
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
        val warningsChanged = snapshot.dataWarnings != uiState.dataWarnings
        updateAppPickerGroup(groupItems)
        uiState = uiState.copy(
            groups = groupItems,
            dataWarnings = snapshot.dataWarnings,
            isRefreshing = true,
        )
        if (snapshot.dataWarnings.isNotEmpty() && warningsChanged) {
            showMessage("偵測到 ${snapshot.dataWarnings.size} 筆資料完整性問題；原始資料已保留")
        }
    }

    private fun updateAppPickerGroup(groupItems: List<GroupItem>) {
        val restoredPickerId = uiState.appPickerGroupId?.takeIf { selectedId ->
            groupItems.any { it.groupId == selectedId }
        }
        if (restoredPickerId != uiState.appPickerGroupId) {
            savedStateHandle[APP_PICKER_GROUP_KEY] = null
        }
        uiState = uiState.copy(appPickerGroupId = restoredPickerId)
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

    private fun reconcileGoogleAfterCommit() {
        viewModelScope.launch {
            // Messaging is separate from verified local space initialization. Its supervisor
            // reports connection evidence and bounded failures; it never rolls back committed data.
            runCatching { withContext(ioDispatcher) { operations.reconcileGms() } }
            refresh()
        }
    }

    fun createGroup(name: String) {
        if (
            uiState.isCreatingGroup ||
            uiState.isSpaceActionBusy ||
            uiState.isCreatingSpaceFromBackup ||
            uiState.archiveBusyGroupId != null
        ) return
        uiState = uiState.copy(isCreatingGroup = true)
        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) { operations.createGroup(name) }
            }
            uiState = uiState.copy(isCreatingGroup = false)
            result.onSuccess { group ->
                showMessage("已建立「${group.name}」")
                refresh()
                reconcileGoogleAfterCommit()
            }.onFailure { error -> uiState = uiState.copy(createSpaceError = "建立空間失敗：${error.userMessage()}") }
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
        if (
            uiState.busyGroupId != null ||
            uiState.archiveBusyGroupId != null ||
            uiState.isCreatingSpaceFromBackup ||

            uiState.uninstallingAppKey != null ||
            uiState.clearingStorageAppKey != null ||
            uiState.clearingStorageGroupId != null
        ) return
        uiState = uiState.copy(busyGroupId = groupId)
        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) { operations.deleteGroup(groupId) }
            }
            uiState = uiState.copy(busyGroupId = null)
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
                    if (uiState.selectedSpaceId == groupId) {
                        savedStateHandle.remove<String>(SELECTED_SPACE_KEY)
                        uiState = uiState.copy(selectedSpaceId = null, spaceDetails = null, spaceDetailsError = null)
                        navigate(MainDestination.HOME)
                    }
                    if (uiState.appPickerGroupId == groupId) closeAppPicker()
                }
                refresh()
            }.onFailure { error ->
                showMessage("刪除群組失敗：${error.userMessage()}")
                refresh()
            }
        }
    }

    fun exportSpace(groupId: String, destination: Uri) {
        if (
            uiState.archiveBusyGroupId != null ||
            uiState.isCreatingSpaceFromBackup ||
            uiState.busyGroupId != null
        ) return
        val compression = uiState.archiveCompression
        uiState = uiState.copy(archiveBusyGroupId = groupId)
        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) {
                    operations.exportSpace(groupId, destination, compression)
                }
            }
            uiState = uiState.copy(archiveBusyGroupId = null)
            result.onSuccess { exported ->
                val mebibytes = exported.bytesWritten / (1024 * 1024)
                showMessage("已匯出「${exported.groupName}」存檔（${mebibytes} MB）")
            }.onFailure { error ->
                showMessage("匯出存檔失敗：${error.userMessage()}")
            }
            refresh()
        }
    }

    fun setArchiveCompression(compression: SpaceArchiveCompression) {
        if (uiState.archiveCompression == compression) return
        archiveExportSettingsStore.save(compression)
        uiState = uiState.copy(archiveCompression = compression)
    }

    fun createSpaceFromBackup(source: Uri) {
        if (
            uiState.isCreatingGroup ||
            uiState.isSpaceActionBusy ||
            uiState.archiveBusyGroupId != null ||
            uiState.isCreatingSpaceFromBackup ||
            uiState.busyGroupId != null
        ) return
        uiState = uiState.copy(isCreatingSpaceFromBackup = true)
        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) { operations.createSpaceFromBackup(source) }
            }
            uiState = uiState.copy(isCreatingSpaceFromBackup = false)
            result.onSuccess { created ->
                navigate(MainDestination.HOME)
                showMessage("已從備份建立新的「${created.group.name}」")
                reconcileGoogleAfterCommit()
            }.onFailure { error ->
                uiState = uiState.copy(createSpaceError = "從備份建立空間失敗：${error.userMessage()}")
            }
            refresh()
        }
    }

    fun selectApp(app: AppItem) {
        if (
            uiState.uninstallingAppKey != null ||
            uiState.busyPackageName != null ||
            uiState.clearingStorageGroupId != null ||
            uiState.launchingAppKey != null
        ) return
        val groupId = uiState.appPickerGroupId ?: return
        if (!appLaunchMutex.tryLock()) return
        uiState = uiState.copy(busyPackageName = app.entry.packageName)
        viewModelScope.launch {
            var appWasAdded = false
            try {
                val group = runCatching {
                    withContext(ioDispatcher) {
                        operations.addAppToGroup(groupId, app.entry.packageName)
                    }
                }.getOrElse { error ->
                    showMessage(error.userMessage())
                    return@launch
                }
                appWasAdded = true
                closeAppPicker()
                clearMatchingPendingLaunch(groupId, app.entry.packageName)
                val groupApp = group.apps.firstOrNull {
                    it.packageName == app.entry.packageName
                }
                if (groupApp == null) {
                    showMessage(
                        "已將 ${app.entry.label} 加入「${group.name}」，但找不到分身資料；" +
                            "分身已保留，請重新整理後再試",
                    )
                    return@launch
                }
                val item = GroupAppItem(
                    groupId = group.id,
                    groupName = group.name,
                    groupHealth = group.health,
                    app = groupApp,
                    appLabel = app.entry.label,
                    versionName = app.entry.versionName,
                    sourceInstalled = true,
                    launchStatus = "準備中",
                )
                uiState = uiState.copy(launchingAppKey = item.launchKey)
                val launchResult = runCatching {
                    withContext(launchDispatcher) { operations.launchGroupApp(item) }
                }.getOrElse { error -> RuntimeLaunchResult.Failed(error.userMessage(), error) }
                clearMatchingPendingLaunch(groupId, app.entry.packageName)
                when (launchResult) {
                    is RuntimeLaunchResult.Started ->
                        showMessage("已將 ${app.entry.label} 加入「${group.name}」並啟動")
                    RuntimeLaunchResult.PackageRepairRequired ->
                        showMessage("分身套件需要重新同步，請稍後重試")
                    is RuntimeLaunchResult.Failed -> showMessage(
                        "已將 ${app.entry.label} 加入「${group.name}」，但自動啟動失敗：" +
                            "${launchResult.reason}；分身已保留，可稍後重試",
                    )
                }
            } finally {
                uiState = uiState.copy(
                    busyPackageName = null,
                    launchingAppKey = null,
                )
                appLaunchMutex.unlock()
                if (appWasAdded) refresh()
            }
        }
    }

    fun launchGroupApp(item: GroupAppItem) {
        tryLaunchGroupApp(item)
    }

    private fun tryLaunchGroupApp(item: GroupAppItem): LaunchAttempt {
        if (isLaunchBlocked(item.groupId)) return LaunchAttempt.BUSY
        if (item.groupHealth != GroupHealth.HEALTHY) {
            showMessage(
                if (item.groupHealth == GroupHealth.DAMAGED) {
                    "「${item.groupName}」的隔離環境已損毀"
                } else {
                    "「${item.groupName}」目前無法啟動 App"
                },
            )
            return LaunchAttempt.REJECTED
        }
        if (!item.canAttemptLaunch) {
            showMessage("來源 App 已移除，暫時無法啟動")
            return LaunchAttempt.REJECTED
        }
        if (!appLaunchMutex.tryLock()) return LaunchAttempt.BUSY
        // A launch owns the foreground path. Older enrichment may keep running on its
        // background lane, but its snapshot must not overwrite state after launch begins.
        refreshGeneration += 1
        uiState = uiState.copy(launchingAppKey = item.launchKey)
        viewModelScope.launch {
            try {
                val result = runCatching {
                    withContext(launchDispatcher) { operations.launchGroupApp(item) }
                }.getOrElse { error -> RuntimeLaunchResult.Failed(error.userMessage(), error) }
                when (result) {
                    is RuntimeLaunchResult.Started ->
                        showMessage("${item.appLabel} 已從「${item.groupName}」啟動")
                    RuntimeLaunchResult.PackageRepairRequired ->
                        showMessage("分身套件需要重新同步，請稍後重試")
                    is RuntimeLaunchResult.Failed -> showMessage("啟動失敗：${result.reason}")
                }
            } finally {
                uiState = uiState.copy(launchingAppKey = null)
                appLaunchMutex.unlock()
                refresh()
            }
        }
        return LaunchAttempt.STARTED
    }

    private fun clearMatchingPendingLaunch(groupId: String, packageName: String) {
        pendingLaunch = pendingLaunch?.takeUnless { pending ->
            pending.packageName == packageName &&
                (pending.groupId == null || pending.groupId == groupId)
        }
    }

    fun uninstallGroupApp(item: GroupAppItem) {
        if (
            uiState.uninstallingAppKey != null ||
            uiState.launchingAppKey != null ||
            uiState.clearingStorageGroupId != null ||
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

    fun clearGroupAppStorage(item: GroupAppItem) {
        if (
            uiState.clearingStorageAppKey != null ||
            uiState.clearingStorageGroupId != null ||
            uiState.uninstallingAppKey != null ||
            uiState.launchingAppKey != null ||
            uiState.busyGroupId != null ||
            uiState.busyPackageName != null
        ) return
        uiState = uiState.copy(clearingStorageAppKey = item.launchKey)
        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) { operations.clearGroupAppStorage(item) }
            }.getOrElse(ClearCloneStorageResult::Failed)
            uiState = uiState.copy(clearingStorageAppKey = null)
            when (result) {
                ClearCloneStorageResult.Cleared ->
                    showMessage("已清除 ${item.appLabel} 的分身資料")
                ClearCloneStorageResult.SpaceNotFound -> showMessage("找不到這個分身空間")
                ClearCloneStorageResult.CloneNotFound -> showMessage("找不到這個分身 App")
                ClearCloneStorageResult.SpaceUnavailable ->
                    showMessage("「${item.groupName}」目前無法清除資料")
                is ClearCloneStorageResult.Failed ->
                    showMessage("清除資料失敗：${result.error.userMessage()}")
            }
            refresh()
        }
    }

    fun clearAllGroupAppData(groupId: String) {
        val group = uiState.groups.firstOrNull { it.groupId == groupId }
            ?: run {
                showMessage("找不到這個分身空間")
                return
            }
        if (
            uiState.clearingStorageGroupId != null ||
            uiState.clearingStorageAppKey != null ||
            uiState.uninstallingAppKey != null ||
            uiState.launchingAppKey != null ||
            uiState.busyGroupId != null ||
            uiState.busyPackageName != null
        ) return
        uiState = uiState.copy(clearingStorageGroupId = groupId)
        viewModelScope.launch {
            val result = runCatching {
                withContext(ioDispatcher) { operations.clearGroupStorage(groupId) }
            }.getOrElse(ClearSpaceStorageResult::Failed)
            uiState = uiState.copy(clearingStorageGroupId = null)
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
                            "清除 $appLabel 時失敗：${result.error.userMessage()}",
                    )
                }
                ClearSpaceStorageResult.SpaceNotFound -> showMessage("找不到這個分身空間")
                ClearSpaceStorageResult.SpaceUnavailable ->
                    showMessage("「${group.name}」目前無法清除資料")
                is ClearSpaceStorageResult.Failed ->
                    showMessage("清除「${group.name}」的 App 資料失敗：${result.error.userMessage()}")
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
                ShortcutCreationResult.Updated ->
                    showMessage("已更新「${item.groupName} ${item.appLabel}」捷徑")
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
        if (isLaunchBlocked(item.groupId) || !item.sourceInstalled) return
        if (!appLaunchMutex.tryLock()) return
        closeDeepLink()
        refreshGeneration += 1
        uiState = uiState.copy(launchingAppKey = item.launchKey)
        viewModelScope.launch {
            try {
                val result = runCatching {
                    withContext(launchDispatcher) { operations.launchDeepLink(item, uri) }
                }.getOrElse { error -> RuntimeLaunchResult.Failed(error.userMessage(), error) }
                when (result) {
                    is RuntimeLaunchResult.Started -> Unit
                    RuntimeLaunchResult.PackageRepairRequired ->
                        showMessage("分身套件需要重新同步，請稍後重試")
                    is RuntimeLaunchResult.Failed ->
                        showMessage("連結開啟失敗：${result.reason}")
                }
            } finally {
                uiState = uiState.copy(launchingAppKey = null)
                appLaunchMutex.unlock()
                refresh()
            }
        }
    }

    fun consumeDiagnostics(reportId: Long) {
        if (uiState.diagnosticsReportId == reportId) {
            uiState = uiState.copy(diagnosticsReport = null)
        }
    }

    fun launchGroupApp(groupId: String, packageName: String) {
        if (groupId.isBlank() || packageName.isBlank()) {
            showMessage("捷徑資料無效；請從 AppTwin 重新建立捷徑")
            return
        }
        if (uiState.showOnboarding) {
            pendingLaunch = PendingLaunch(groupId, packageName)
            return
        }
        // Recovery may mutate install state, so shortcuts wait for that bounded phase. Once
        // it finishes, exact-package launch may proceed without waiting for enrichment.
        if (launchBlockedByRecovery) {
            pendingLaunch = PendingLaunch(groupId, packageName)
            return
        }
        val item = uiState.groups.asSequence()
            .flatMap { it.apps.asSequence() }
            .firstOrNull { it.groupId == groupId && it.app.packageName == packageName }
        if (item != null) {
            if (item.lifecycle in setOf(CloneLifecycleState.READY, CloneLifecycleState.PREPARING)) {
                tryLaunchGroupApp(item)
            } else {
                showUnavailableShortcutMessage(item)
            }
        } else {
            pendingLaunch = PendingLaunch(groupId, packageName)
            if (!uiState.isRefreshing) refresh()
        }
    }

    fun launchFirst(packageName: String) {
        val item = uiState.groups.asSequence()
            .flatMap { it.apps.asSequence() }
            .firstOrNull { it.app.packageName == packageName }
        if (item != null && !launchBlockedByRecovery) {
            tryLaunchGroupApp(item)
        } else {
            pendingLaunch = PendingLaunch(null, packageName)
        }
    }

    fun consumeMessage(messageId: Long) {
        if (uiState.messageId == messageId) uiState = uiState.copy(message = null)
    }

    private fun showUnavailableShortcutMessage(item: GroupAppItem) {
        showMessage(
            "「${item.groupName}」的 ${item.appLabel} 分身目前不可使用；" +
                "請在 AppTwin 中修復",
        )
    }

    private fun reconcileAndRefresh() {
        if (recoveryInProgress) return
        recoveryInProgress = true
        val generation = ++refreshGeneration
        uiState = uiState.copy(isRefreshing = true)
        viewModelScope.launch {
            try {
                // Do not make the home screen wait for recovery work or GMS network reconciliation.
                // The index is durable local metadata and is safe to present while enrichment continues.
                loadAndPublishGroups(generation) ?: return@launch
                val groupResult = withContext(ioDispatcher) {
                    runCatching { operations.reconcileGroups() }
                }
                val (appRemovalResult, applicationOperationResult) = withContext(ioDispatcher) {
                    runCatching { operations.reconcileAppRemovals() } to
                        runCatching { operations.reconcileApplicationOperations() }
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
                }
                if (notices.isNotEmpty()) appendMessage(notices.joinToString("\n"))
                val refreshedGroups = loadAndPublishGroups(generation)
                if (refreshedGroups != null) {
                    // Reconciliation may change clone membership. Only expose the launch lane
                    // after its post-recovery index has been loaded and published successfully.
                    launchBlockedByRecovery = false
                    resolvePendingLaunch(uiState.groups)
                    applyFullRefresh(generation, refreshedGroups)
                }
                val gmsResult = withContext(ioDispatcher) { runCatching { operations.reconcileGms() } }
                gmsResult.onFailure { error ->
                    appendMessage("Google 服務相容資料需要處理：${error.userMessage()}")
                }.onSuccess { result ->
                    if (result.productStates.isNotEmpty()) {
                        val updatedGroups = uiState.groups.map { group ->
                            result.productStates[group.groupId]?.let { productState ->
                                group.copy(gmsCompatibility = productState)
                            } ?: group
                        }
                        updateAppPickerGroup(updatedGroups)
                        uiState = uiState.copy(groups = updatedGroups)
                    }
                    if (result.cloudMessagingRepairFailures.isNotEmpty()) {
                        appendMessage("Google 背景通知服務需要重試")
                    }
                }
            } finally {
                recoveryInProgress = false
            }
        }
    }

    private fun resolvePendingLaunch(groupItems: List<GroupItem>) {
        val target = pendingLaunch ?: return
        val pending = groupItems.asSequence()
            .flatMap { it.apps.asSequence() }
            .firstOrNull { item ->
                item.app.packageName == target.packageName &&
                    (target.groupId == null || item.groupId == target.groupId)
            }
        when {
            pending == null && target.groupId != null -> {
                pendingLaunch = null
                showMessage("此捷徑對應的分身已不存在；請從 AppTwin 重新建立捷徑")
            }
            pending != null &&
                target.groupId != null &&
                pending.lifecycle !in setOf(
                    CloneLifecycleState.READY,
                    CloneLifecycleState.PREPARING,
                ) -> {
                pendingLaunch = null
                showUnavailableShortcutMessage(pending)
            }
            pending != null && tryLaunchGroupApp(pending) != LaunchAttempt.BUSY -> {
                pendingLaunch = null
            }
        }
    }

    private fun isLaunchBlocked(groupId: String): Boolean =
        launchBlockedByRecovery ||
            uiState.launchingAppKey != null ||
            uiState.uninstallingAppKey != null ||
            uiState.repairingAppKey != null ||
            uiState.clearingStorageAppKey != null ||
            uiState.clearingStorageGroupId != null ||
            uiState.busyGroupId != null ||
            uiState.busyPackageName != null

    private fun launchStatus(versionCode: Long?): String = when {
        versionCode == null -> "來源 App 已移除"
        else -> "可使用"
    }

    private fun showMessage(message: String) {
        uiState = uiState.copy(message = message, messageId = uiState.messageId + 1)
    }

    private fun appendMessage(message: String) {
        showMessage(listOfNotNull(uiState.message, message).joinToString("\n"))
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
        if (waitForRefresh) return

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
        const val SELECTED_SPACE_KEY = "main.selectedSpace"
        const val DESTINATION_KEY = "main.destination"
        const val APP_PICKER_GROUP_KEY = "main.appPickerGroupId"

        fun destinationFromSavedState(value: String): MainDestination? =
            runCatching { MainDestination.valueOf(value) }.getOrNull()
    }

    private data class PendingLaunch(val groupId: String?, val packageName: String)

    private enum class LaunchAttempt { STARTED, BUSY, REJECTED }
}

private fun Throwable.userMessage(): String = message ?: javaClass.simpleName
