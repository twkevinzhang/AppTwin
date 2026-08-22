package org.apptwin.usecases

import org.apptwin.groups.GroupHealth
import org.apptwin.groups.GroupStore

/**
 * Result of clearing every user-selected clone's private runtime state in one Space.
 *
 * A Space clear is deliberately not atomic: clones are reset one at a time and a failure leaves
 * all previously cleared clones reset. [PartiallyCleared] makes that state explicit to callers.
 */
sealed interface ClearSpaceStorageResult {
    data class Cleared(val clearedCloneCount: Int) : ClearSpaceStorageResult
    data class PartiallyCleared(
        val clearedCloneCount: Int,
        val failedPackageName: String,
        val error: Throwable,
    ) : ClearSpaceStorageResult

    data object SpaceNotFound : ClearSpaceStorageResult
    data object SpaceUnavailable : ClearSpaceStorageResult
    data class Failed(val error: Throwable) : ClearSpaceStorageResult
}

/**
 * Clears only the private data of the clones recorded in a Space.
 *
 * This never targets the Space's shared virtual SD storage or Google/microG state: neither is a
 * member of the Space's user-selected clone list. The runtime is invoked sequentially and stops
 * at the first failure so callers can accurately report a non-atomic partial result.
 */
class ClearSpaceStorageUseCase(
    private val store: GroupStore,
    private val runtime: CloneStorageClearRuntime,
) {
    fun execute(spaceId: String): ClearSpaceStorageResult {
        val space = runCatching { store.find(spaceId) }
            .getOrElse { error -> return ClearSpaceStorageResult.Failed(error) }
            ?: return ClearSpaceStorageResult.SpaceNotFound
        val binding = space.environmentBinding
        if (space.health != GroupHealth.HEALTHY || binding == null) {
            return ClearSpaceStorageResult.SpaceUnavailable
        }

        var clearedCloneCount = 0
        for (clone in space.apps) {
            val failure = runCatching {
                runtime.clearStorage(binding, clone.packageName)
            }.exceptionOrNull()
            if (failure != null) {
                return ClearSpaceStorageResult.PartiallyCleared(
                    clearedCloneCount = clearedCloneCount,
                    failedPackageName = clone.packageName,
                    error = failure,
                )
            }
            clearedCloneCount += 1
        }
        return ClearSpaceStorageResult.Cleared(clearedCloneCount)
    }
}
