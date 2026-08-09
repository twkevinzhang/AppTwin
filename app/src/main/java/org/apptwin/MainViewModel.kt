package org.apptwin

import android.app.Application
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.util.concurrent.Executors
import org.apptwin.groups.FileGroupAppRemovalJournal
import org.apptwin.groups.FileGroupOperationJournal
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupAppRemovalCoordinator
import org.apptwin.groups.GroupAppRemovalResult
import org.apptwin.groups.GroupAppState
import org.apptwin.groups.GroupHealth
import org.apptwin.groups.GroupLifecycleCoordinator
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.revision.InstalledAppEntry
import org.apptwin.revision.RevisionImportResult
import org.apptwin.runtime.GroupAppRuntimeSupport
import org.apptwin.runtime.RuntimeCompatibility
import org.apptwin.runtime.RuntimeLaunchResult
import org.apptwin.runtime.VirtualRuntimeController

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
    val message: String? = null,
    val messageId: Long = 0,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val importer = AndroidPackageRevisionImporter(application)
    private val groupStore = FileGroupStore(application)
    private val runtimeController = VirtualRuntimeController(application)
    private val lifecycle = GroupLifecycleCoordinator(
        store = groupStore,
        runtime = runtimeController,
        journal = FileGroupOperationJournal(application),
    )
    private val appRemoval = GroupAppRemovalCoordinator(
        store = groupStore,
        runtime = runtimeController,
        journal = FileGroupAppRemovalJournal(application),
    )
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingLaunchPackage: String? = null

    var uiState by mutableStateOf(MainUiState())
        private set

    init {
        reconcileAndRefresh()
    }

    fun navigate(destination: MainDestination) {
        uiState = uiState.copy(destination = destination, appPickerGroupId = null)
    }

    fun openAppPicker(groupId: String) {
        if (uiState.uninstallingAppKey != null) {
            showMessage("App 正在解除安裝，請稍候")
            return
        }
        val group = groupStore.find(groupId)
        if (group == null) {
            showMessage("找不到這個群組")
        } else if (group.health != GroupHealth.HEALTHY) {
            showMessage("這個群組目前無法加入 App")
        } else {
            uiState = uiState.copy(destination = MainDestination.HOME, appPickerGroupId = groupId)
        }
    }

    fun closeAppPicker() {
        uiState = uiState.copy(appPickerGroupId = null)
    }

    fun refresh() {
        val storage = readStorageStatus()
        uiState = uiState.copy(
            isRefreshing = true,
            allFilesGranted = storage.granted,
            downloadCount = storage.downloadCount,
            photoCount = storage.photoCount,
        )
        worker.execute {
            val entries = runCatching(importer::listCloneableApps).getOrDefault(emptyList())
            val groups = runCatching(groupStore::listAll).getOrDefault(emptyList())
            val entriesByPackage = entries.associateBy(InstalledAppEntry::packageName)
            val appItems = entries.map { entry ->
                val active = importer.active(entry.packageName)
                AppItem(
                    entry = entry,
                    isSynced = active?.versionCode == entry.versionCode,
                    activeVersionCode = active?.versionCode,
                    groupCount = groups.count { it.contains(entry.packageName) },
                )
            }
            val groupItems = groups.sortedByDescending(Group::createdAtEpochMillis).map { group ->
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
            post {
                uiState = uiState.copy(apps = appItems, groups = groupItems, isRefreshing = false)
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
    }

    fun createGroup(name: String) {
        if (uiState.isCreatingGroup) return
        uiState = uiState.copy(isCreatingGroup = true)
        worker.execute {
            val result = runCatching { lifecycle.createGroup(name) }
            post {
                uiState = uiState.copy(isCreatingGroup = false)
                result.onSuccess { group ->
                    showMessage("已建立「${group.name}」")
                    refresh()
                }.onFailure { error -> showMessage("建立群組失敗：${error.userMessage()}") }
            }
        }
    }

    fun renameGroup(groupId: String, name: String) {
        runCatching { groupStore.rename(groupId, name) }
            .onSuccess { renamed ->
                if (renamed == null) showMessage("找不到這個群組")
                else {
                    showMessage("已重新命名為「${renamed.name}」")
                    refresh()
                }
            }
            .onFailure { error -> showMessage("重新命名失敗：${error.userMessage()}") }
    }

    fun deleteGroup(groupId: String) {
        if (uiState.busyGroupId != null || uiState.uninstallingAppKey != null) return
        val group = groupStore.find(groupId) ?: run {
            showMessage("找不到這個群組")
            return
        }
        uiState = uiState.copy(busyGroupId = groupId)
        worker.execute {
            val result = runCatching { lifecycle.deleteGroup(groupId) }
            post {
                uiState = uiState.copy(busyGroupId = null)
                result.onSuccess {
                    showMessage("已刪除「${group.name}」及其中的所有資料")
                    refresh()
                }.onFailure { error ->
                    showMessage("刪除群組失敗：${error.userMessage()}")
                    refresh()
                }
            }
        }
    }

    fun selectApp(app: AppItem) {
        if (uiState.uninstallingAppKey != null) return
        val groupId = uiState.appPickerGroupId ?: return
        val group = groupStore.find(groupId) ?: run {
            showMessage("找不到這個群組")
            closeAppPicker()
            return
        }
        if (group.health != GroupHealth.HEALTHY) {
            showMessage("這個群組目前無法加入 App")
            return
        }
        if (group.contains(app.entry.packageName)) {
            showMessage("${app.entry.label} 已在「${group.name}」中")
            return
        }
        if (uiState.busyPackageName != null) return
        uiState = uiState.copy(busyPackageName = app.entry.packageName)
        worker.execute {
            val sync = importer.sync(app.entry.packageName)
            val added = when (sync) {
                is RevisionImportResult.Activated,
                is RevisionImportResult.AlreadyCurrent,
                -> runCatching {
                    requireNotNull(
                        groupStore.addApp(
                            groupId,
                            app.entry.packageName,
                            System.currentTimeMillis(),
                        ),
                    ) { "群組不存在" }
                }
                is RevisionImportResult.Rejected -> Result.failure(
                    IllegalStateException("無法同步：${sync.reason}"),
                )
                is RevisionImportResult.Failed -> Result.failure(
                    IllegalStateException("同步失敗：${sync.reason}"),
                )
            }
            post {
                uiState = uiState.copy(busyPackageName = null)
                added.onSuccess {
                    uiState = uiState.copy(appPickerGroupId = null)
                    showMessage("已將 ${app.entry.label} 加入「${group.name}」")
                    refresh()
                }.onFailure { error -> showMessage(error.userMessage()) }
            }
        }
    }

    fun launchGroupApp(item: GroupAppItem) {
        if (
            uiState.launchingAppKey != null ||
            uiState.uninstallingAppKey != null
        ) return
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
        worker.execute {
            val currentGroup = groupStore.find(item.groupId)
            val result = if (currentGroup == null || currentGroup.health != GroupHealth.HEALTHY) {
                RuntimeLaunchResult.Failed("群組環境目前無法使用")
            } else {
                groupStore.updateAppState(
                    currentGroup.id,
                    item.app.packageName,
                    GroupAppState.INSTALLING,
                )
                val launch = runtimeController.installAndLaunch(currentGroup, item.app)
                groupStore.updateAppState(
                    currentGroup.id,
                    item.app.packageName,
                    if (launch is RuntimeLaunchResult.Started) {
                        GroupAppState.ENABLED
                    } else {
                        GroupAppState.FAILED
                    },
                )
                launch
            }
            post {
                uiState = uiState.copy(launchingAppKey = null)
                when (result) {
                    is RuntimeLaunchResult.Started ->
                        showMessage("${item.appLabel} 已從「${item.groupName}」啟動")
                    is RuntimeLaunchResult.Failed -> showMessage("啟動失敗：${result.reason}")
                }
                refresh()
            }
        }
    }

    fun uninstallGroupApp(item: GroupAppItem) {
        if (
            uiState.uninstallingAppKey != null ||
            uiState.launchingAppKey != null ||
            uiState.busyGroupId != null ||
            uiState.busyPackageName != null
        ) return
        val current = groupStore.find(item.groupId)
        val currentApp = current?.apps?.firstOrNull {
            it.packageName == item.app.packageName &&
                it.addedAtEpochMillis == item.app.addedAtEpochMillis
        }
        if (current == null || currentApp == null) {
            showMessage("${item.appLabel} 已不在「${item.groupName}」中")
            refresh()
            return
        }
        if (current.health != GroupHealth.HEALTHY) {
            showMessage("「${item.groupName}」目前無法解除安裝 App")
            return
        }

        uiState = uiState.copy(uninstallingAppKey = item.launchKey)
        worker.execute {
            val result = appRemoval.remove(item.groupId, item.app.packageName)
            post {
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
        worker.execute {
            val lifecycleResult = runCatching(lifecycle::reconcile)
            val appRemovalResult = runCatching(appRemoval::reconcile)
            post {
                lifecycleResult.onFailure { error ->
                    showMessage("部分群組環境需要處理：${error.userMessage()}")
                }
                appRemovalResult.onFailure { error ->
                    showMessage("部分 App 解除安裝作業需要處理：${error.userMessage()}")
                }
                refresh()
            }
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

    private fun post(action: () -> Unit) {
        mainHandler.post(action)
    }

    private fun readStorageStatus(): StorageStatus {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
        if (!granted) return StorageStatus(false, 0, 0)
        return StorageStatus(
            granted = true,
            downloadCount = publicDirectoryEntryCount(Environment.DIRECTORY_DOWNLOADS),
            photoCount = publicDirectoryEntryCount(Environment.DIRECTORY_DCIM) +
                publicDirectoryEntryCount(Environment.DIRECTORY_PICTURES),
        )
    }

    private fun publicDirectoryEntryCount(directoryType: String): Int = runCatching {
        @Suppress("DEPRECATION")
        Environment.getExternalStoragePublicDirectory(directoryType).listFiles()?.size ?: 0
    }.getOrDefault(0)

    override fun onCleared() {
        worker.shutdown()
        super.onCleared()
    }

    private data class StorageStatus(
        val granted: Boolean,
        val downloadCount: Int,
        val photoCount: Int,
    )
}

private fun Throwable.userMessage(): String = message ?: javaClass.simpleName
