package org.apptwin

import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.groups.GroupLifecycleCoordinator
import org.apptwin.runtime.VirtualRuntimeController
import org.apptwin.usecases.ClearSpaceStorageResult
import org.apptwin.usecases.ClearSpaceStorageUseCase

/** Android implementation of the SpaceOperations feature boundary. */
internal class AndroidSpaceOperations(
    private val groupStore: FileGroupStore,
    private val runtimeController: VirtualRuntimeController,
    private val lifecycle: GroupLifecycleCoordinator,
    private val deletedGroupCleanup: DeletedGroupCleanup,
    private val clearSpaceStorage: ClearSpaceStorageUseCase,
) : SpaceOperations {
    override suspend fun findGroup(groupId: String): Group? = groupStore.find(groupId)

    override suspend fun createGroup(name: String): Group = lifecycle.createGroup(name)

    override suspend fun renameGroup(groupId: String, name: String): Group? =
        groupStore.rename(groupId, name)?.also(runtimeController::syncEnvironmentLabel)

    override suspend fun deleteGroup(groupId: String): DeleteGroupResult {
        val group = groupStore.find(groupId) ?: return DeleteGroupResult.NotFound
        check(lifecycle.deleteGroup(groupId)) { "群組資料不存在" }
        return deletedGroupCleanup.execute(group)
    }

    override suspend fun clearGroupStorage(groupId: String): ClearSpaceStorageResult =
        clearSpaceStorage.execute(groupId)
}
