package org.apptwin

import android.app.Application
import com.lody.virtual.client.ipc.VActivityManager
import org.apptwin.archive.AndroidSpaceArchiveOperations
import org.apptwin.gms.AndroidGmsOperations
import org.apptwin.gms.GmsStartupResult
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.groups.GroupAppRemovalCoordinator
import org.apptwin.groups.GroupHealth
import org.apptwin.groups.GroupLifecycleCoordinator
import org.apptwin.groups.GroupReconciliationResult
import org.apptwin.operations.OperationReconciler

/** Android implementation of the BackgroundRecoveryOperations feature boundary. */
internal class AndroidBackgroundRecoveryOperations(
    private val application: Application,
    private val groupStore: FileGroupStore,
    private val lifecycle: GroupLifecycleCoordinator,
    private val archives: AndroidSpaceArchiveOperations,
    private val appRemoval: GroupAppRemovalCoordinator,
    private val operationReconciler: OperationReconciler,
    private val gms: AndroidGmsOperations,
) : BackgroundRecoveryOperations {
    override suspend fun loadSpaceBackgroundHealth(groupId: String): SpaceBackgroundHealth {
        val group = groupStore.find(groupId) ?: return SpaceBackgroundHealth(groupId, error = "空間不存在")
        val userId = group.environmentBinding?.internalId
            ?: return SpaceBackgroundHealth(groupId, error = "空間尚未綁定 runtime")
        val state = VActivityManager.get().getTrustedGmsCloudMessagingState(userId)
        return mapSpaceBackgroundHealth(groupId, state)
    }

    override suspend fun retrySpaceBackground(groupId: String): Boolean {
        if (!com.lody.virtual.client.env.BackgroundExecutionSettings.isEnabled(application)) return false
        val group = groupStore.find(groupId) ?: return false
        if (group.health != GroupHealth.HEALTHY) return false
        val userId = group.environmentBinding?.internalId ?: return false
        return VActivityManager.get().retryTrustedGmsCloudMessagingForUser(userId)
    }

    override suspend fun reconcileGroups(): GroupReconciliationResult = lifecycle.reconcile().also { archives.reconcileBackupKeyspaceClaims() }

    override suspend fun reconcileAppRemovals() {
        appRemoval.reconcile()
    }

    override suspend fun reconcileApplicationOperations() {
        operationReconciler.reconcile()
    }

    override suspend fun reconcileGms(): GmsStartupResult {
        val groupIds = groupStore.loadSnapshot().groups.map(Group::id)
        return gms.startupReconcile(groupIds)
    }
}
