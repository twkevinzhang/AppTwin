package org.apptwin

import android.app.Application
import android.os.Build
import android.os.Environment
import org.apptwin.groups.FileGroupAppRemovalJournal
import org.apptwin.groups.FileGroupOperationJournal
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.groups.GroupAppRemovalCoordinator
import org.apptwin.groups.GroupAppRemovalResult
import org.apptwin.groups.GroupAppState
import org.apptwin.groups.GroupHealth
import org.apptwin.groups.GroupLifecycleCoordinator
import org.apptwin.groups.GroupReconciliationResult
import org.apptwin.revision.ActiveRevisionSummary
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.revision.RevisionImportResult
import org.apptwin.runtime.RuntimeLaunchResult
import org.apptwin.runtime.VirtualRuntimeController

/** Android adapters composed behind the ViewModel's blocking-operation port. */
internal class AndroidMainOperations(application: Application) : MainOperations {
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

    override suspend fun refreshSnapshot(): MainRefreshSnapshot {
        val storage = readStorageStatus()
        val entries = runCatching(importer::listCloneableApps).getOrDefault(emptyList())
        val groupSnapshot = groupStore.loadSnapshot()
        val activeRevisions = linkedMapOf<String, ActiveRevisionSummary?>()
        val warnings = groupSnapshot.issues.map { issue ->
            "Group ${issue.groupId}/${issue.metadataName} 無法讀取"
        }.toMutableList()
        entries.forEach { entry ->
            activeRevisions[entry.packageName] = runCatching { importer.active(entry.packageName) }
                .getOrElse { error ->
                    warnings += "${entry.packageName} active revision 無法讀取：${error.userMessage()}"
                    null
                }
        }
        return MainRefreshSnapshot(
            storage = storage,
            entries = entries,
            groups = groupSnapshot.groups,
            activeRevisions = activeRevisions,
            dataWarnings = warnings,
        )
    }

    override suspend fun findGroup(groupId: String): Group? = groupStore.find(groupId)

    override suspend fun createGroup(name: String): Group = lifecycle.createGroup(name)

    override suspend fun renameGroup(groupId: String, name: String): Group? =
        groupStore.rename(groupId, name)

    override suspend fun deleteGroup(groupId: String): Group? {
        val group = groupStore.find(groupId) ?: return null
        check(lifecycle.deleteGroup(groupId)) { "群組資料不存在" }
        return group
    }

    override suspend fun addAppToGroup(groupId: String, packageName: String): Group {
        val group = requireNotNull(groupStore.find(groupId)) { "找不到這個群組" }
        require(group.health == GroupHealth.HEALTHY) { "這個群組目前無法加入 App" }
        require(!group.contains(packageName)) { "$packageName 已在「${group.name}」中" }
        when (val sync = importer.sync(packageName)) {
            is RevisionImportResult.Activated,
            is RevisionImportResult.AlreadyCurrent,
            -> Unit
            is RevisionImportResult.Rejected -> error("無法同步：${sync.reason}")
            is RevisionImportResult.Failed -> error("同步失敗：${sync.reason}")
        }
        return requireNotNull(
            groupStore.addApp(groupId, packageName, System.currentTimeMillis()),
        ) { "群組不存在" }
    }

    override suspend fun launchGroupApp(item: GroupAppItem): RuntimeLaunchResult = runCatching {
        val currentGroup = groupStore.find(item.groupId)
        if (currentGroup == null || currentGroup.health != GroupHealth.HEALTHY) {
            return@runCatching RuntimeLaunchResult.Failed("群組環境目前無法使用")
        }
        when (val sync = importer.sync(item.app.packageName)) {
            is RevisionImportResult.Activated,
            is RevisionImportResult.AlreadyCurrent,
            -> Unit

            is RevisionImportResult.Rejected ->
                return@runCatching RuntimeLaunchResult.Failed("無法同步：${sync.reason}")

            is RevisionImportResult.Failed ->
                return@runCatching RuntimeLaunchResult.Failed("同步失敗：${sync.reason}")
        }
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
    }.getOrElse { error ->
        RuntimeLaunchResult.Failed(error.userMessage(), error)
    }

    override suspend fun uninstallGroupApp(item: GroupAppItem): GroupAppRemovalResult {
        val current = groupStore.find(item.groupId)
        val currentApp = current?.apps?.firstOrNull {
            it.packageName == item.app.packageName &&
                it.addedAtEpochMillis == item.app.addedAtEpochMillis
        }
        if (current == null || currentApp == null) return GroupAppRemovalResult.AlreadyAbsent
        if (current.health != GroupHealth.HEALTHY) {
            val error = IllegalStateException("「${item.groupName}」目前無法解除安裝 App")
            return GroupAppRemovalResult.Failed(requireNotNull(error.message), error)
        }
        return appRemoval.remove(item.groupId, item.app.packageName)
    }

    override suspend fun reconcileGroups(): GroupReconciliationResult = lifecycle.reconcile()

    override suspend fun reconcileAppRemovals() {
        appRemoval.reconcile()
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
}

private fun Throwable.userMessage(): String = message ?: javaClass.simpleName
