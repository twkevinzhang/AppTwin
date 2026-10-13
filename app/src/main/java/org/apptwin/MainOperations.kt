package org.apptwin

import android.net.Uri
import org.apptwin.archive.SpaceArchiveCompression
import org.apptwin.groups.Group
import org.apptwin.groups.GroupAppRemovalResult
import org.apptwin.groups.GroupReconciliationResult
import org.apptwin.gms.GmsGroupProductState
import org.apptwin.gms.GmsStartupResult
import org.apptwin.operations.OperationRecord
import org.apptwin.permissions.ClonePermissionSummary
import org.apptwin.repair.RepairExecutionResult
import org.apptwin.revision.ActiveRevisionSummary
import org.apptwin.revision.InstalledAppEntry
import org.apptwin.runtime.RuntimeLaunchResult
import org.apptwin.usecases.ClearCloneStorageResult
import org.apptwin.usecases.ClearSpaceStorageResult

/** Blocking feature ports; callers choose the dispatcher and coordinate transactions. */
internal interface SpaceOperations {
    suspend fun findGroup(groupId: String): Group?
    suspend fun createGroup(name: String): Group
    suspend fun renameGroup(groupId: String, name: String): Group?
    suspend fun deleteGroup(groupId: String): DeleteGroupResult
    suspend fun clearGroupStorage(groupId: String): ClearSpaceStorageResult
}

internal interface CloneOperations {
    suspend fun addAppToGroup(groupId: String, packageName: String): Group
    suspend fun launchGroupApp(item: GroupAppItem): RuntimeLaunchResult
    suspend fun uninstallGroupApp(item: GroupAppItem): GroupAppRemovalResult
    suspend fun clearGroupAppStorage(item: GroupAppItem): ClearCloneStorageResult
    suspend fun createShortcut(item: GroupAppItem): ShortcutCreationResult
    suspend fun repairClone(item: GroupAppItem): RepairExecutionResult
    suspend fun resolveDeepLink(uri: String): List<Pair<String, String>>
    suspend fun launchDeepLink(item: GroupAppItem, uri: String): RuntimeLaunchResult
}

internal interface ClonePermissionOperations {
    suspend fun setClonePermission(
        item: GroupAppItem,
        permission: String,
        granted: Boolean,
    ): Boolean
}

internal interface SpaceAccountOperations {
    suspend fun addSpaceAccount(groupId: String, type: String) = Unit
    suspend fun synchronizeSpaceAccount(groupId: String, account: SpaceAccountEntry, authority: String): String = "同步未支援"
    suspend fun removeSpaceAccount(groupId: String, account: SpaceAccountEntry): AccountRemovalResult = AccountRemovalResult.REJECTED
}

internal interface SpaceArchiveOperations {
    suspend fun globalBackupLocation(): String? = null
    suspend fun deleteBackup(id: String) = Unit
    suspend fun setGlobalBackupLocation(uri: Uri) = Unit
    suspend fun exportToGlobalLocation(groupId: String, compression: SpaceArchiveCompression): SpaceArchiveExportResult = error("尚未設定備份位置")
    suspend fun exportSpace(
        groupId: String,
        destination: Uri,
        compression: SpaceArchiveCompression,
    ): SpaceArchiveExportResult
    suspend fun createSpaceFromBackup(source: Uri): CreateSpaceFromBackupResult
}

internal interface MainSnapshotOperations {
    suspend fun loadGroupSnapshot(): MainGroupSnapshot
    suspend fun refreshSnapshot(groups: MainGroupSnapshot): MainRefreshSnapshot
    suspend fun loadSpaceDetails(groupId: String): SpaceDetails? = null
}

internal interface DiagnosticOperations {
    suspend fun exportDiagnostics(): String
}

internal interface BackgroundRecoveryOperations {
    suspend fun loadSpaceBackgroundHealth(groupId: String): SpaceBackgroundHealth = SpaceBackgroundHealth(groupId)
    suspend fun retrySpaceBackground(groupId: String): Boolean = false
    suspend fun reconcileGroups(): GroupReconciliationResult
    suspend fun reconcileAppRemovals()
    suspend fun reconcileApplicationOperations()
    suspend fun reconcileGms(): GmsStartupResult
}

/** Compatibility aggregate for existing callers and test doubles. */
internal interface MainOperations :
    SpaceOperations,
    CloneOperations,
    ClonePermissionOperations,
    SpaceAccountOperations,
    SpaceArchiveOperations,
    MainSnapshotOperations,
    DiagnosticOperations,
    BackgroundRecoveryOperations
