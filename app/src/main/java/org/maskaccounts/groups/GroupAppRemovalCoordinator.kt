package org.maskaccounts.groups

sealed interface RuntimeGroupAppRemovalResult {
    data object Removed : RuntimeGroupAppRemovalResult
    data object AlreadyAbsent : RuntimeGroupAppRemovalResult
}

/** Removes only one package membership and its private data from one Group environment. */
fun interface GroupAppRemovalRuntime {
    fun removeApp(
        binding: EnvironmentBinding,
        packageName: String,
    ): RuntimeGroupAppRemovalResult
}

enum class GroupAppRemovalPhase {
    STARTED,
    RUNTIME_REMOVED,
}

data class GroupAppRemovalOperation(
    val groupId: String,
    val packageName: String,
    val environmentBinding: EnvironmentBinding,
    val membershipAddedAtEpochMillis: Long,
    val phase: GroupAppRemovalPhase,
    val startedAtEpochMillis: Long,
)

interface GroupAppRemovalJournal {
    fun listAll(): List<GroupAppRemovalOperation>
    fun write(operation: GroupAppRemovalOperation)
    fun remove(groupId: String, packageName: String)
}

sealed interface GroupAppRemovalResult {
    data class Succeeded(
        val runtimeResult: RuntimeGroupAppRemovalResult,
    ) : GroupAppRemovalResult

    /** The Group or membership was already gone, so there is no remaining work. */
    data object AlreadyAbsent : GroupAppRemovalResult

    /** Metadata is deliberately retained and the journal allows a later retry. */
    data class Failed(
        val reason: String,
        val error: Throwable,
    ) : GroupAppRemovalResult
}

/**
 * Coordinates runtime-first GroupApp removal.
 *
 * Metadata is removed only after the runtime reports Removed or AlreadyAbsent. A crash at any
 * transition is converged by [reconcile]. The membership timestamp prevents a stale journal from
 * removing a newly re-added membership for the same package.
 */
class GroupAppRemovalCoordinator(
    private val store: GroupStore,
    private val runtime: GroupAppRemovalRuntime,
    private val journal: GroupAppRemovalJournal,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun remove(groupId: String, packageName: String): GroupAppRemovalResult {
        val group = store.find(groupId) ?: return GroupAppRemovalResult.AlreadyAbsent
        val app = group.apps.firstOrNull { it.packageName == packageName }
            ?: return GroupAppRemovalResult.AlreadyAbsent
        val binding = group.environmentBinding
        if (group.health != GroupHealth.HEALTHY || binding == null) {
            val error = IllegalStateException("群組環境目前無法使用")
            return GroupAppRemovalResult.Failed(requireNotNull(error.message), error)
        }
        val operation = GroupAppRemovalOperation(
            groupId = group.id,
            packageName = app.packageName,
            environmentBinding = binding,
            membershipAddedAtEpochMillis = app.addedAtEpochMillis,
            phase = GroupAppRemovalPhase.STARTED,
            startedAtEpochMillis = clock(),
        )
        return runCatching {
            journal.write(operation)
            completeRuntimeRemoval(operation)
        }.getOrElse { error ->
            GroupAppRemovalResult.Failed(
                reason = error.message ?: error.javaClass.simpleName,
                error = error,
            )
        }
    }

    /** Best-effort recovery: one failed operation must not prevent other operations from converging. */
    @Synchronized
    fun reconcile() {
        journal.listAll().forEach { operation ->
            runCatching { reconcile(operation) }
        }
    }

    private fun reconcile(operation: GroupAppRemovalOperation) {
        val group = store.find(operation.groupId) ?: run {
            journal.remove(operation.groupId, operation.packageName)
            return
        }
        val currentApp = group.apps.firstOrNull { it.packageName == operation.packageName }
            ?: run {
                journal.remove(operation.groupId, operation.packageName)
                return
            }
        if (currentApp.addedAtEpochMillis != operation.membershipAddedAtEpochMillis) {
            journal.remove(operation.groupId, operation.packageName)
            return
        }
        if (operation.phase == GroupAppRemovalPhase.RUNTIME_REMOVED) {
            completeMetadataRemoval(operation)
            return
        }
        if (group.health != GroupHealth.HEALTHY ||
            group.environmentBinding != operation.environmentBinding
        ) {
            return
        }
        completeRuntimeRemoval(operation)
    }

    private fun completeRuntimeRemoval(
        operation: GroupAppRemovalOperation,
    ): GroupAppRemovalResult.Succeeded {
        val runtimeResult = runtime.removeApp(
            operation.environmentBinding,
            operation.packageName,
        )
        val runtimeRemoved = operation.copy(phase = GroupAppRemovalPhase.RUNTIME_REMOVED)
        journal.write(runtimeRemoved)
        completeMetadataRemoval(runtimeRemoved)
        return GroupAppRemovalResult.Succeeded(runtimeResult)
    }

    private fun completeMetadataRemoval(operation: GroupAppRemovalOperation) {
        val current = store.find(operation.groupId)
        val currentApp = current?.apps?.firstOrNull { it.packageName == operation.packageName }
        if (currentApp?.addedAtEpochMillis == operation.membershipAddedAtEpochMillis) {
            check(store.removeApp(operation.groupId, operation.packageName) != null) {
                "Unable to remove GroupApp metadata"
            }
        }
        journal.remove(operation.groupId, operation.packageName)
    }
}
