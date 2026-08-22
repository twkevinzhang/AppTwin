package org.apptwin.usecases

import org.apptwin.groups.EnvironmentBinding
import org.apptwin.groups.GroupHealth
import org.apptwin.groups.GroupStore

/** Result of clearing a clone's mutable runtime state while retaining its membership and package. */
sealed interface ClearCloneStorageResult {
    data object Cleared : ClearCloneStorageResult
    data object SpaceNotFound : ClearCloneStorageResult
    data object CloneNotFound : ClearCloneStorageResult
    data object SpaceUnavailable : ClearCloneStorageResult
    data class Failed(val error: Throwable) : ClearCloneStorageResult
}

/**
 * Runtime port for the destructive portion of a clone data reset.
 *
 * Implementations must leave the virtual package installed for [binding]. This makes a successful
 * clear equivalent to first launch data without removing the clone from its Space.
 */
fun interface CloneStorageClearRuntime {
    fun clearStorage(binding: EnvironmentBinding, packageName: String)
}

/** Clears one clone's runtime-owned data without changing durable Group metadata. */
class ClearCloneStorageUseCase(
    private val store: GroupStore,
    private val runtime: CloneStorageClearRuntime,
) {
    fun execute(spaceId: String, packageName: String): ClearCloneStorageResult {
        val space = runCatching { store.find(spaceId) }
            .getOrElse { return ClearCloneStorageResult.Failed(it) }
            ?: return ClearCloneStorageResult.SpaceNotFound
        if (space.health != GroupHealth.HEALTHY || space.environmentBinding == null) {
            return ClearCloneStorageResult.SpaceUnavailable
        }
        if (!space.contains(packageName)) return ClearCloneStorageResult.CloneNotFound

        return runCatching {
            runtime.clearStorage(requireNotNull(space.environmentBinding), packageName)
            ClearCloneStorageResult.Cleared
        }.getOrElse(ClearCloneStorageResult::Failed)
    }
}
