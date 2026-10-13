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

internal data class ClonePermissionState(
    val cameraGranted: Boolean,
    val microphoneGranted: Boolean,
)
