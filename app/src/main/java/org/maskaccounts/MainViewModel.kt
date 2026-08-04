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
import org.maskaccounts.groups.FileGroupOperationJournal
import org.maskaccounts.groups.FileGroupStore
import org.maskaccounts.groups.GoogleServicesState
import org.maskaccounts.groups.Group
import org.maskaccounts.groups.GroupApp
import org.maskaccounts.groups.GroupAppOrigin
import org.maskaccounts.groups.GroupAppState
import org.maskaccounts.groups.GroupHealth
import org.maskaccounts.groups.GroupLifecycleCoordinator
import org.maskaccounts.revision.AndroidPackageRevisionImporter
import org.maskaccounts.revision.InstalledAppEntry
import org.maskaccounts.revision.RevisionImportResult
import org.maskaccounts.runtime.GroupAppRuntimeSupport
import org.maskaccounts.runtime.GroupPreparationResult
import org.maskaccounts.runtime.GroupPlayStoreLaunchContract
import org.maskaccounts.runtime.GroupPlayStoreAppReconciler
import org.maskaccounts.runtime.RuntimeCompatibility
import org.maskaccounts.runtime.RuntimeLaunchResult
import org.maskaccounts.runtime.VirtualRuntimeController
import org.maskaccounts.runtime.VirtualPackageSummary

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
    val googleServicesState: GoogleServicesState,
    val app: GroupApp,
    val appLabel: String,
    val versionName: String,
    val sourceInstalled: Boolean,
    val launchSupported: Boolean,
    val launchStatus: String,
) {
    val launchKey: String = "$groupId:${app.packageName}"
}

data class GroupItem(
    val groupId: String,
    val name: String,
    val health: GroupHealth,
    val googleServicesState: GoogleServicesState,
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
    val launchingPlayStoreGroupId: String? = null,
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
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activePreparations = ConcurrentHashMap.newKeySet<String>()
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
                .filterNot { GroupPlayStoreLaunchContract.isReservedGroupService(it.packageName) }
            val storedGroups = runCatching(groupStore::listAll).getOrDefault(emptyList())
            val virtualPackagesByGroup = reconcilePlayStoreApps(storedGroups)
            val groups = runCatching(groupStore::listAll).getOrDefault(storedGroups)
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
                    googleServicesState = group.googleServicesState,
                    apps = group.apps
                        .filterNot {
                            GroupPlayStoreLaunchContract.isReservedGroupService(it.packageName)
                        }
                        .map { app ->
                            val source = entriesByPackage[app.packageName]
                            val virtual = virtualPackagesByGroup[group.id]?.get(app.packageName)
                            val isPlayStoreApp = app.origin == GroupAppOrigin.PLAY_STORE
                            GroupAppItem(
                                groupId = group.id,
                                groupName = group.name,
                                groupHealth = group.health,
                                googleServicesState = group.googleServicesState,
                                app = app,
                                appLabel = if (isPlayStoreApp) {
                                    virtual?.label ?: app.packageName
                                } else {
                                    source?.label ?: app.packageName
                                },
                                versionName = if (isPlayStoreApp) {
                                    virtual?.versionName.orEmpty()
                                } else {
                                    source?.versionName.orEmpty()
                                },
                                sourceInstalled = isPlayStoreApp || source != null,
                                launchSupported = isPlayStoreApp || (
                                    source != null &&
                                        GroupAppRuntimeSupport.canLaunch(app.packageName)
                                    ),
                                launchStatus = if (isPlayStoreApp) {
                                    "由此群組的 Play 商店安裝"
                                } else {
                                    launchStatus(app.packageName, source != null)
                                },
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
        if (uiState.busyGroupId != null) return
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
        if (GroupPlayStoreLaunchContract.isReservedGroupService(app.entry.packageName)) {
            showMessage("Play 商店是群組服務，請直接從群組卡片開啟")
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
                    prepareGroup(groupId)
                }.onFailure { error -> showMessage(error.userMessage()) }
            }
        }
    }

    fun prepareGroup(groupId: String) {
        if (groupId in activePreparations) return
        val group = groupStore.find(groupId) ?: return
        if (group.health != GroupHealth.HEALTHY) {
            showMessage("群組環境目前無法準備 Google 服務")
            return
        }
        activePreparations += groupId
        groupStore.updateGoogleServicesState(groupId, GoogleServicesState.PREPARING)
        refresh()
        worker.execute {
            val current = groupStore.find(groupId) ?: group
            val result = runtimeController.prepareGroup(current)
            groupStore.updateGoogleServicesState(
                groupId,
                if (result is GroupPreparationResult.Ready) {
                    GoogleServicesState.READY
                } else {
                    GoogleServicesState.FAILED
                },
            )
            activePreparations -= groupId
            post {
                when (result) {
                    is GroupPreparationResult.Ready ->
                        showMessage("「${current.name}」的 Google 服務已就緒")
                    is GroupPreparationResult.Failed ->
                        showMessage("Google 服務準備失敗：${result.reason}")
                }
                refresh()
            }
        }
    }

    fun launchGroupApp(item: GroupAppItem) {
        if (uiState.launchingAppKey != null || uiState.launchingPlayStoreGroupId != null) return
        if (item.groupId in activePreparations) {
            showMessage("「${item.groupName}」正在準備，請稍候")
            return
        }
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
        if (item.app.origin == GroupAppOrigin.SYSTEM_IMPORT && !item.sourceInstalled) {
            showMessage("來源 App 已移除，暫時無法啟動")
            return
        }
        if (item.app.origin == GroupAppOrigin.SYSTEM_IMPORT && !item.launchSupported) {
            showMessage("目前實機啟動驗證僅支援 LINE、蝦皮、YouTube 與 Maps")
            return
        }
        activePreparations += item.groupId
        uiState = uiState.copy(launchingAppKey = item.launchKey)
        worker.execute {
            val currentGroup = groupStore.find(item.groupId)
            val result = if (currentGroup == null || currentGroup.health != GroupHealth.HEALTHY) {
                RuntimeLaunchResult.Failed("群組環境目前無法使用")
            } else {
                val preparation = if (
                    currentGroup.googleServicesState == GoogleServicesState.READY
                ) {
                    GroupPreparationResult.Ready
                } else {
                    groupStore.updateGoogleServicesState(
                        currentGroup.id,
                        GoogleServicesState.PREPARING,
                    )
                    runtimeController.prepareGroup(currentGroup)
                }
                when (preparation) {
                    is GroupPreparationResult.Ready -> {
                        groupStore.updateGoogleServicesState(
                            currentGroup.id,
                            GoogleServicesState.READY,
                        )
                        if (item.app.origin == GroupAppOrigin.SYSTEM_IMPORT) {
                            groupStore.updateAppState(
                                currentGroup.id,
                                item.app.packageName,
                                GroupAppState.INSTALLING,
                            )
                        }
                        val launch = if (item.app.origin == GroupAppOrigin.PLAY_STORE) {
                            runtimeController.launchInstalledPackage(
                                currentGroup,
                                item.app.packageName,
                            )
                        } else {
                            runtimeController.installAndLaunch(currentGroup, item.app)
                        }
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
                    is GroupPreparationResult.Failed -> {
                        groupStore.updateGoogleServicesState(
                            currentGroup.id,
                            GoogleServicesState.FAILED,
                        )
                        RuntimeLaunchResult.Failed(preparation.reason, preparation.error)
                    }
                }
            }
            post {
                activePreparations -= item.groupId
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

    fun launchPlayStore(groupId: String) {
        if (uiState.launchingAppKey != null || uiState.launchingPlayStoreGroupId != null) return
        if (groupId in activePreparations) {
            val groupName = uiState.groups.firstOrNull { it.groupId == groupId }?.name ?: "群組"
            showMessage("「$groupName」正在準備，請稍候")
            return
        }
        val group = groupStore.find(groupId) ?: run {
            showMessage("找不到這個群組")
            return
        }
        if (group.health != GroupHealth.HEALTHY) {
            showMessage(
                if (group.health == GroupHealth.DAMAGED) {
                    "「${group.name}」的隔離環境已損毀"
                } else {
                    "「${group.name}」目前無法開啟 Play 商店"
                },
            )
            return
        }

        activePreparations += groupId
        uiState = uiState.copy(launchingPlayStoreGroupId = groupId)
        worker.execute {
            val result = runCatching {
                val currentGroup = groupStore.find(groupId)
                if (currentGroup == null || currentGroup.health != GroupHealth.HEALTHY) {
                    RuntimeLaunchResult.Failed("群組環境目前無法使用")
                } else {
                    val preparation = if (
                        currentGroup.googleServicesState == GoogleServicesState.READY
                    ) {
                        GroupPreparationResult.Ready
                    } else {
                        groupStore.updateGoogleServicesState(
                            currentGroup.id,
                            GoogleServicesState.PREPARING,
                        )
                        runtimeController.prepareGroup(currentGroup)
                    }
                    when (preparation) {
                        is GroupPreparationResult.Ready -> {
                            groupStore.updateGoogleServicesState(
                                currentGroup.id,
                                GoogleServicesState.READY,
                            )
                            runtimeController.launchPlayStore(currentGroup)
                        }
                        is GroupPreparationResult.Failed -> {
                            groupStore.updateGoogleServicesState(
                                currentGroup.id,
                                GoogleServicesState.FAILED,
                            )
                            RuntimeLaunchResult.Failed(preparation.reason, preparation.error)
                        }
                    }
                }
            }.getOrElse { error ->
                RuntimeLaunchResult.Failed(error.userMessage(), error)
            }
            post {
                activePreparations -= groupId
                uiState = uiState.copy(launchingPlayStoreGroupId = null)
                when (result) {
                    is RuntimeLaunchResult.Started ->
                        showMessage("已從「${group.name}」開啟 Play 商店")
                    is RuntimeLaunchResult.Failed ->
                        showMessage("Play 商店啟動失敗：${result.reason}")
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
            val result = runCatching(lifecycle::reconcile)
            post {
                result.onFailure { error ->
                    showMessage("部分群組環境需要處理：${error.userMessage()}")
                }
                refresh()
            }
        }
    }

    private fun reconcilePlayStoreApps(
        groups: List<Group>,
    ): Map<String, Map<String, VirtualPackageSummary>> {
        val snapshots = linkedMapOf<String, Map<String, VirtualPackageSummary>>()
        groups.filter { it.health == GroupHealth.HEALTHY }.forEach { group ->
            val installed = runtimeController.installedPackages(group).getOrNull()
                ?: return@forEach
            snapshots[group.id] = installed.associateBy(VirtualPackageSummary::packageName)
            runCatching {
                val plan = GroupPlayStoreAppReconciler.plan(
                    existingApps = group.apps,
                    installedPackages = installed.map(VirtualPackageSummary::packageName),
                )
                plan.removals.forEach { packageName ->
                    groupStore.removeApp(group.id, packageName)
                }
                plan.additions.forEachIndexed { index, packageName ->
                    groupStore.addApp(
                        groupId = group.id,
                        packageName = packageName,
                        addedAtEpochMillis = System.currentTimeMillis() + index,
                        origin = GroupAppOrigin.PLAY_STORE,
                    )
                    groupStore.updateAppState(group.id, packageName, GroupAppState.ENABLED)
                }
            }
        }
        return snapshots
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
