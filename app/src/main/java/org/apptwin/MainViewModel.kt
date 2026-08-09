package org.apptwin

import android.app.Application
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
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupAppRemovalResult
import org.apptwin.groups.GroupAppState
import org.apptwin.groups.GroupHealth
import org.apptwin.groups.GroupReconciliationResult
import org.apptwin.revision.ActiveRevisionSummary
import org.apptwin.revision.InstalledAppEntry
import org.apptwin.runtime.GroupAppRuntimeSupport
import org.apptwin.runtime.RuntimeCompatibility
import org.apptwin.runtime.RuntimeLaunchResult

enum class MainDestination { HOME, SETTINGS }

data class AppItem(
    val entry: InstalledAppEntry,
    val isSynced: Boolean,
    val activeVersionCode: Long?,
    val groupCount: Int,
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
) {
    val launchKey: String = "$groupId:${app.packageName}"
}

data class GroupItem(
    val groupId: String,
    val name: String,
    val health: GroupHealth,
    val apps: List<GroupAppItem>,
) {
    fun contains(packageName: String): Boolean = apps.any { it.app.packageName == packageName }
}

data class MainUiState(
    val destination: MainDestination = MainDestination.HOME,
    val apps: List<AppItem> = emptyList(),
    val groups: List<GroupItem> = emptyList(),
    val appPickerGroupId: String? = null,
    val isRefreshing: Boolean = true,
    val isCreatingGroup: Boolean = false,
    val busyPackageName: String? = null,
    val busyGroupId: String? = null,
    val launchingAppKey: String? = null,
    val uninstallingAppKey: String? = null,
    val allFilesGranted: Boolean = false,
    val downloadCount: Int = 0,
    val photoCount: Int = 0,
    val dataWarnings: List<String> = emptyList(),
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
    suspend fun reconcileGroups(): GroupReconciliationResult
    suspend fun reconcileAppRemovals()
}

class MainViewModel internal constructor(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
    private val operations: MainOperations,
    private val ioDispatcher: CoroutineDispatcher,
) : AndroidViewModel(application) {
    @OptIn(ExperimentalCoroutinesApi::class)
    constructor(application: Application, savedStateHandle: SavedStateHandle) : this(
        application = application,
        savedStateHandle = savedStateHandle,
        operations = AndroidMainOperations(application),
        ioDispatcher = Dispatchers.IO.limitedParallelism(1),
    )

    private var pendingLaunchPackage: String? = null
    private var refreshGeneration = 0L
    private var pickerRequestGeneration = 0L

    var uiState by mutableStateOf(
        MainUiState(
            destination = savedStateHandle.get<String>(DESTINATION_KEY)
                ?.let(::destinationFromSavedState)
                ?: MainDestination.HOME,
            appPickerGroupId = savedStateHandle.get<String>(APP_PICKER_GROUP_KEY),
        ),
    )
        private set

    init {
        reconcileAndRefresh()
    }

    fun navigate(destination: MainDestination) {
        pickerRequestGeneration += 1
        savedStateHandle[DESTINATION_KEY] = destination.name
        savedStateHandle[APP_PICKER_GROUP_KEY] = null
        uiState = uiState.copy(destination = destination, appPickerGroupId = null)
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
                )
            }
            val groupItems = snapshot.groups.sortedByDescending(Group::createdAtEpochMillis)
                .map { group ->
                    GroupItem(
                        groupId = group.id,
                        name = group.name,
                        health = group.health,
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
                                launchStatus = launchStatus(app.packageName, source != null),
                            )
                        },
                    )
                }
            val restoredPickerId = uiState.appPickerGroupId?.takeIf { selectedId ->
                groupItems.any { it.groupId == selectedId }
            }
            if (restoredPickerId != uiState.appPickerGroupId) {
                savedStateHandle[APP_PICKER_GROUP_KEY] = null
            }
            val warningsChanged = snapshot.dataWarnings != uiState.dataWarnings
            uiState = uiState.copy(
                apps = appItems,
                groups = groupItems,
                appPickerGroupId = restoredPickerId,
                isRefreshing = false,
                allFilesGranted = snapshot.storage.granted,
                downloadCount = snapshot.storage.downloadCount,
                photoCount = snapshot.storage.photoCount,
                dataWarnings = snapshot.dataWarnings,
            )
            if (snapshot.dataWarnings.isNotEmpty() && warningsChanged) {
                showMessage("偵測到 ${snapshot.dataWarnings.size} 筆資料完整性問題；原始資料已保留")
            }
            pendingLaunchPackage?.let { packageName ->
                val pending = groupItems.asSequence()
                    .flatMap { it.apps.asSequence() }
                    .firstOrNull { it.app.packageName == packageName }
                if (pending != null) {
                    pendingLaunchPackage = null
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
                    if (uiState.appPickerGroupId == groupId) closeAppPicker()
                }
                refresh()
            }.onFailure { error ->
                showMessage("刪除群組失敗：${error.userMessage()}")
                refresh()
            }
        }
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

    fun launchFirst(packageName: String) {
        val item = uiState.groups.asSequence()
            .flatMap { it.apps.asSequence() }
            .firstOrNull { it.app.packageName == packageName }
        if (item != null) launchGroupApp(item) else pendingLaunchPackage = packageName
    }

    fun consumeMessage(messageId: Long) {
        if (uiState.messageId == messageId) uiState = uiState.copy(message = null)
    }

    private fun reconcileAndRefresh() {
        uiState = uiState.copy(isRefreshing = true)
        viewModelScope.launch {
            val (groupResult, appRemovalResult) = withContext(ioDispatcher) {
                runCatching { operations.reconcileGroups() } to
                    runCatching { operations.reconcileAppRemovals() }
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
            }
            if (notices.isNotEmpty()) showMessage(notices.joinToString("\n"))
            refresh()
        }
    }

    private fun launchStatus(packageName: String, sourceInstalled: Boolean): String = when {
        !sourceInstalled -> "來源 App 已移除"
        GroupAppRuntimeSupport.compatibility(packageName) == RuntimeCompatibility.VERIFIED ->
            "已通過實機啟動驗證"
        else -> "尚未完成實機相容驗證"
    }

    private fun showMessage(message: String) {
        uiState = uiState.copy(message = message, messageId = uiState.messageId + 1)
    }

    private companion object {
        const val DESTINATION_KEY = "main.destination"
        const val APP_PICKER_GROUP_KEY = "main.appPickerGroupId"

        fun destinationFromSavedState(value: String): MainDestination? =
            runCatching { MainDestination.valueOf(value) }.getOrNull()
    }
}

private fun Throwable.userMessage(): String = message ?: javaClass.simpleName
