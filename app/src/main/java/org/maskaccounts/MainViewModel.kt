package org.maskaccounts

import android.app.Application
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.maskaccounts.groups.AppGroup
import org.maskaccounts.groups.FileGroupStore
import org.maskaccounts.groups.GroupApp
import org.maskaccounts.groups.GroupRuntimeState
import org.maskaccounts.revision.AndroidPackageRevisionImporter
import org.maskaccounts.revision.InstalledAppEntry
import org.maskaccounts.revision.RevisionImportResult
import org.maskaccounts.runtime.GroupAppRuntimeSupport
import org.maskaccounts.runtime.GroupPreparationResult
import org.maskaccounts.runtime.RuntimeCompatibility
import org.maskaccounts.runtime.RuntimeLaunchResult
import org.maskaccounts.runtime.VirtualRuntimeController

enum class MainDestination {
    HOME,
    SETTINGS,
}

data class AppItem(
    val entry: InstalledAppEntry,
    val isSynced: Boolean,
    val activeVersionCode: Long?,
    val groupCount: Int,
)

data class GroupAppItem(
    val group: AppGroup,
    val app: GroupApp,
    val appLabel: String,
    val versionName: String,
    val sourceInstalled: Boolean,
    val launchSupported: Boolean,
    val launchStatus: String,
) {
    val launchKey: String = "${group.id}:${app.packageName}"
}

data class GroupItem(
    val group: AppGroup,
    val apps: List<GroupAppItem>,
)

data class MainUiState(
    val destination: MainDestination = MainDestination.HOME,
    val apps: List<AppItem> = emptyList(),
    val groups: List<GroupItem> = emptyList(),
    val appPickerGroupId: String? = null,
    val isRefreshing: Boolean = true,
    val busyPackageName: String? = null,
    val busyGroupId: String? = null,
    val launchingAppKey: String? = null,
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
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activePreparations = ConcurrentHashMap.newKeySet<String>()
    private var pendingLaunchPackage: String? = null

    var uiState by mutableStateOf(MainUiState())
        private set

    init {
        refresh()
    }

    fun navigate(destination: MainDestination) {
        uiState = uiState.copy(destination = destination, appPickerGroupId = null)
    }

    fun openAppPicker(groupId: String) {
        if (groupStore.find(groupId) == null) {
            showMessage("找不到這個群組")
            return
        }
        uiState = uiState.copy(
            destination = MainDestination.HOME,
            appPickerGroupId = groupId,
        )
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
            val groups = runCatching(groupStore::listAll).getOrDefault(emptyList()).map { group ->
                if (
                    group.runtimeState == GroupRuntimeState.PREPARING &&
                    group.id !in activePreparations
                ) {
                    groupStore.updateRuntimeState(group.id, GroupRuntimeState.FAILED) ?: group
                } else {
                    group
                }
            }
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
            val groupItems = groups
                .sortedByDescending(AppGroup::createdAtEpochMillis)
                .map { group ->
                    GroupItem(
                        group = group,
                        apps = group.apps.map { app ->
                            val source = entriesByPackage[app.packageName]
                            GroupAppItem(
                                group = group,
                                app = app,
                                appLabel = source?.label ?: app.packageName,
                                versionName = source?.versionName.orEmpty(),
                                sourceInstalled = source != null,
                                launchSupported = source != null &&
                                    GroupAppRuntimeSupport.canLaunch(app.packageName),
                                launchStatus = launchStatus(app.packageName, source != null),
                            )
                        },
                    )
                }
            post {
                uiState = uiState.copy(
                    apps = appItems,
                    groups = groupItems,
                    isRefreshing = false,
                )
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
        runCatching { groupStore.create(name, System.currentTimeMillis()) }
            .onSuccess { group ->
                showMessage("已建立 ${group.name}，Google 服務會在需要時準備")
                refresh()
            }
            .onFailure { error -> showMessage("建立群組失敗：${error.userMessage()}") }
    }

    fun renameGroup(groupId: String, name: String) {
        runCatching { groupStore.rename(groupId, name) }
            .onSuccess { renamed ->
                if (renamed == null) showMessage("找不到這個群組")
                else {
                    showMessage("已重新命名為 ${renamed.name}")
                    refresh()
                }
            }
            .onFailure { error -> showMessage("重新命名失敗：${error.userMessage()}") }
    }

    fun deleteGroup(groupId: String) {
        if (uiState.busyGroupId != null) return
        val group = groupStore.find(groupId) ?: run {
            showMessage("找不到這個群組")
            return
        }
        uiState = uiState.copy(busyGroupId = groupId)
        worker.execute {
            val result = runCatching {
                runtimeController.deleteGroupRuntime(group)
                check(groupStore.delete(groupId)) { "群組資料夾不存在" }
            }
            post {
                uiState = uiState.copy(busyGroupId = null)
                result.onSuccess {
                    showMessage("已刪除 ${group.name} 的 App、帳戶與 Google 服務資料")
                    refresh()
                }.onFailure { error ->
                    showMessage("刪除群組失敗：${error.userMessage()}")
                }
            }
        }
    }

    fun selectApp(app: AppItem) {
        val groupId = uiState.appPickerGroupId ?: return
        val group = groupStore.find(groupId) ?: run {
            showMessage("找不到這個群組")
            closeAppPicker()
            return
        }
        if (group.contains(app.entry.packageName)) {
            showMessage("${app.entry.label} 已在 ${group.name} 中")
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
                    showMessage("已將 ${app.entry.label} 加入 ${group.name}")
                    refresh()
                    prepareGroup(groupId)
                }.onFailure { error -> showMessage(error.userMessage()) }
            }
        }
    }

    fun prepareGroup(groupId: String) {
        if (groupId in activePreparations) return
        val group = groupStore.find(groupId) ?: return
        activePreparations += groupId
        groupStore.updateRuntimeState(groupId, GroupRuntimeState.PREPARING)
        refresh()
        worker.execute {
            val current = groupStore.find(groupId) ?: group
            val result = runtimeController.prepareGroup(current)
            val state = when (result) {
                is GroupPreparationResult.Ready -> GroupRuntimeState.READY
                is GroupPreparationResult.Failed -> GroupRuntimeState.FAILED
            }
            groupStore.updateRuntimeState(groupId, state)
            activePreparations -= groupId
            post {
                when (result) {
                    is GroupPreparationResult.Ready ->
                        showMessage("${current.name} 的 Google 服務已就緒")
                    is GroupPreparationResult.Failed ->
                        showMessage("Google 服務準備失敗：${result.reason}")
                }
                refresh()
            }
        }
    }

    fun launchGroupApp(item: GroupAppItem) {
        if (uiState.launchingAppKey != null) return
        if (!item.sourceInstalled) {
            showMessage("來源 App 已移除，暫時無法啟動")
            return
        }
        if (!item.launchSupported) {
            showMessage("目前實機啟動驗證僅支援 LINE、蝦皮、YouTube 與 Maps")
            return
        }
        activePreparations += item.group.id
        uiState = uiState.copy(launchingAppKey = item.launchKey)
        worker.execute {
            val currentGroup = groupStore.find(item.group.id) ?: item.group
            val preparation = if (currentGroup.runtimeState == GroupRuntimeState.READY) {
                GroupPreparationResult.Ready(-1)
            } else {
                groupStore.updateRuntimeState(currentGroup.id, GroupRuntimeState.PREPARING)
                runtimeController.prepareGroup(currentGroup)
            }
            val result = when (preparation) {
                is GroupPreparationResult.Ready -> {
                    groupStore.updateRuntimeState(currentGroup.id, GroupRuntimeState.READY)
                    runtimeController.installAndLaunch(currentGroup, item.app)
                }
                is GroupPreparationResult.Failed -> {
                    groupStore.updateRuntimeState(currentGroup.id, GroupRuntimeState.FAILED)
                    RuntimeLaunchResult.Failed(preparation.reason, preparation.error)
                }
            }
            post {
                activePreparations -= currentGroup.id
                uiState = uiState.copy(launchingAppKey = null)
                when (result) {
                    is RuntimeLaunchResult.Started -> showMessage(
                        "${item.appLabel} 已從 ${currentGroup.name} 啟動 · virtual user ${result.virtualUserId}",
                    )
                    is RuntimeLaunchResult.Failed -> showMessage("啟動失敗：${result.reason}")
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

    private fun launchStatus(packageName: String, sourceInstalled: Boolean): String = when {
        !sourceInstalled -> "來源 App 已移除"
        GroupAppRuntimeSupport.compatibility(packageName) == RuntimeCompatibility.VERIFIED ->
            "已通過實機啟動驗證"
        GroupAppRuntimeSupport.compatibility(packageName) == RuntimeCompatibility.EXPERIMENTAL ->
            "相容性實驗中"
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
