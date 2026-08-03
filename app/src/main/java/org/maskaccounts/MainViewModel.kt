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
import java.util.concurrent.Executors
import org.maskaccounts.instances.FileInstanceStore
import org.maskaccounts.instances.VirtualInstance
import org.maskaccounts.revision.AndroidPackageRevisionImporter
import org.maskaccounts.revision.InstalledAppEntry
import org.maskaccounts.revision.RevisionImportResult
import org.maskaccounts.runtime.CloneRuntimeSupport
import org.maskaccounts.runtime.RuntimeCompatibility
import org.maskaccounts.runtime.RuntimeLaunchResult
import org.maskaccounts.runtime.VirtualRuntimeController

enum class MainDestination {
    INSTANCES,
    APPS,
    SETTINGS,
}

data class AppItem(
    val entry: InstalledAppEntry,
    val isSynced: Boolean,
    val activeVersionCode: Long?,
    val instanceCount: Int,
)

data class InstanceItem(
    val instance: VirtualInstance,
    val appLabel: String,
    val versionName: String,
    val sourceInstalled: Boolean,
    val launchSupported: Boolean,
    val launchStatus: String,
)

data class CreateDraft(
    val app: InstalledAppEntry,
    val suggestedName: String,
)

data class MainUiState(
    val destination: MainDestination = MainDestination.INSTANCES,
    val apps: List<AppItem> = emptyList(),
    val instances: List<InstanceItem> = emptyList(),
    val isRefreshing: Boolean = true,
    val busyPackageName: String? = null,
    val launchingInstanceId: String? = null,
    val createDraft: CreateDraft? = null,
    val allFilesGranted: Boolean = false,
    val downloadCount: Int = 0,
    val photoCount: Int = 0,
    val message: String? = null,
    val messageId: Long = 0,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val importer = AndroidPackageRevisionImporter(application)
    private val instanceStore = FileInstanceStore(application)
    private val runtimeController = VirtualRuntimeController(application)
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingLaunchPackage: String? = null

    var uiState by mutableStateOf(MainUiState())
        private set

    init {
        refresh()
    }

    fun navigate(destination: MainDestination) {
        uiState = uiState.copy(destination = destination)
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
            val instances = runCatching(instanceStore::listAll).getOrDefault(emptyList())
            val entriesByPackage = entries.associateBy(InstalledAppEntry::packageName)
            val appItems = entries.map { entry ->
                val active = importer.active(entry.packageName)
                AppItem(
                    entry = entry,
                    isSynced = active?.versionCode == entry.versionCode,
                    activeVersionCode = active?.versionCode,
                    instanceCount = instances.count { it.packageName == entry.packageName },
                )
            }
            val instanceItems = instances
                .sortedByDescending(VirtualInstance::createdAtEpochMillis)
                .map { instance ->
                    val source = entriesByPackage[instance.packageName]
                    InstanceItem(
                        instance = instance,
                        appLabel = source?.label ?: instance.packageName,
                        versionName = source?.versionName.orEmpty(),
                        sourceInstalled = source != null,
                        launchSupported = source != null &&
                            CloneRuntimeSupport.canLaunch(instance.packageName),
                        launchStatus = when {
                            source == null -> "來源 App 已移除"
                            CloneRuntimeSupport.compatibility(instance.packageName) ==
                                RuntimeCompatibility.VERIFIED -> "已就緒 · 點一下啟動"
                            CloneRuntimeSupport.compatibility(instance.packageName) ==
                                RuntimeCompatibility.EXPERIMENTAL ->
                                "相容性實驗中 · 將準備 Google 依賴"
                            else -> "尚未完成實機相容驗證"
                        },
                    )
                }
            post {
                uiState = uiState.copy(
                    apps = appItems,
                    instances = instanceItems,
                    isRefreshing = false,
                )
                pendingLaunchPackage?.let { packageName ->
                    val pending = instanceItems.firstOrNull {
                        it.instance.packageName == packageName
                    }
                    if (pending != null) {
                        pendingLaunchPackage = null
                        launchInstance(pending)
                    }
                }
            }
        }
    }

    fun selectApp(app: AppItem) {
        if (uiState.busyPackageName != null) return
        if (app.isSynced) {
            openCreateDraft(app.entry)
            return
        }
        uiState = uiState.copy(busyPackageName = app.entry.packageName)
        worker.execute {
            val result = importer.sync(app.entry.packageName)
            post {
                uiState = uiState.copy(busyPackageName = null)
                when (result) {
                    is RevisionImportResult.Activated -> {
                        showMessage("${app.entry.label} 已同步 ${result.artifactCount} 個 APK")
                        refresh()
                        openCreateDraft(app.entry)
                    }
                    is RevisionImportResult.AlreadyCurrent -> {
                        refresh()
                        openCreateDraft(app.entry)
                    }
                    is RevisionImportResult.Rejected -> showMessage("無法同步：${result.reason}")
                    is RevisionImportResult.Failed -> showMessage("同步失敗：${result.reason}")
                }
            }
        }
    }

    fun dismissCreateDraft() {
        uiState = uiState.copy(createDraft = null)
    }

    fun createInstance(displayName: String) {
        val draft = uiState.createDraft ?: return
        val instance = runCatching {
            instanceStore.create(
                packageName = draft.app.packageName,
                displayName = displayName,
                createdAtEpochMillis = System.currentTimeMillis(),
            )
        }.getOrElse { error ->
            showMessage("建立失敗：${error.message ?: error.javaClass.simpleName}")
            return
        }
        uiState = uiState.copy(
            createDraft = null,
            destination = MainDestination.INSTANCES,
        )
        showMessage("已建立 ${instance.displayName}")
        refresh()
    }

    fun renameInstance(instanceId: String, displayName: String) {
        runCatching { instanceStore.rename(instanceId, displayName) }
            .onSuccess { renamed ->
                if (renamed == null) showMessage("找不到這個分身")
                else {
                    showMessage("已重新命名為 ${renamed.displayName}")
                    refresh()
                }
            }
            .onFailure { error ->
                showMessage("重新命名失敗：${error.message ?: error.javaClass.simpleName}")
            }
    }

    fun deleteInstance(instanceId: String) {
        runCatching { instanceStore.delete(instanceId) }
            .onSuccess { deleted ->
                showMessage(if (deleted) "已刪除分身記錄" else "找不到這個分身")
                if (deleted) refresh()
            }
            .onFailure { error ->
                showMessage("刪除失敗：${error.message ?: error.javaClass.simpleName}")
            }
    }

    fun launchInstance(item: InstanceItem) {
        if (uiState.launchingInstanceId != null) return
        if (!item.sourceInstalled) {
            showMessage("來源 App 已移除，暫時無法啟動")
            return
        }
        if (!item.launchSupported) {
            showMessage("目前實機啟動驗證僅支援 LINE、蝦皮與 YouTube")
            return
        }
        uiState = uiState.copy(launchingInstanceId = item.instance.id)
        worker.execute {
            val result = runtimeController.installAndLaunch(item.instance)
            post {
                uiState = uiState.copy(launchingInstanceId = null)
                when (result) {
                    is RuntimeLaunchResult.Started -> showMessage(
                        "${item.instance.displayName} 已交給 virtual user ${result.virtualUserId} 啟動",
                    )
                    is RuntimeLaunchResult.Failed -> showMessage("啟動失敗：${result.reason}")
                }
            }
        }
    }

    fun launchFirst(packageName: String) {
        val instance = uiState.instances.firstOrNull { it.instance.packageName == packageName }
        if (instance != null) launchInstance(instance) else pendingLaunchPackage = packageName
    }

    fun consumeMessage(messageId: Long) {
        if (uiState.messageId == messageId) uiState = uiState.copy(message = null)
    }

    private fun openCreateDraft(entry: InstalledAppEntry) {
        val ordinal = uiState.instances.count { it.instance.packageName == entry.packageName } + 1
        uiState = uiState.copy(
            createDraft = CreateDraft(entry, "${entry.label} $ordinal"),
        )
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
