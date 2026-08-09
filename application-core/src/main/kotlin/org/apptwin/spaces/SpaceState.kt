package org.apptwin.spaces

import org.apptwin.groups.Group
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupAppState
import org.apptwin.groups.GroupHealth
import org.apptwin.operations.OperationKind
import org.apptwin.operations.OperationPhase
import org.apptwin.operations.OperationRecord

enum class SpaceLifecycleState {
    CREATING,
    READY,
    NEEDS_REPAIR,
    REPAIRING,
    DELETING,
}

enum class CloneLifecycleState {
    PREPARING,
    READY,
    LAUNCHING,
    UPDATING,
    SOURCE_MISSING,
    UNSUPPORTED,
    NEEDS_REPAIR,
    REPAIRING,
    REMOVING,
}

data class SpaceState(
    val id: String,
    val name: String,
    val lifecycle: SpaceLifecycleState,
    val clones: List<CloneState>,
)

data class CloneState(
    val packageName: String,
    val lifecycle: CloneLifecycleState,
)

/** Maps persisted domain and durable operation state without UI strings or Android types. */
object SpaceStatePolicy {
    fun assess(group: Group, operations: List<OperationRecord> = emptyList()): SpaceState {
        val relevant = operations.filter { it.target.spaceId == group.id }
        val spaceLifecycle = when {
            relevant.any { it.kind == OperationKind.DELETE_SPACE && it.isActive() } ->
                SpaceLifecycleState.DELETING
            relevant.any {
                it.kind in setOf(OperationKind.REPAIR_SPACE, OperationKind.REPAIR_CLONE) &&
                    it.isActive()
            } -> SpaceLifecycleState.REPAIRING
            relevant.any { it.kind == OperationKind.CREATE_SPACE && it.isActive() } ->
                SpaceLifecycleState.CREATING
            relevant.any { it.target.packageName == null && it.phase == OperationPhase.FAILED } ->
                SpaceLifecycleState.NEEDS_REPAIR
            else -> group.health.toSpaceLifecycle()
        }
        return SpaceState(
            id = group.id,
            name = group.name,
            lifecycle = spaceLifecycle,
            clones = group.apps.map { app ->
                CloneState(
                    packageName = app.packageName,
                    lifecycle = cloneLifecycle(app, relevant),
                )
            },
        )
    }

    private fun cloneLifecycle(
        app: GroupApp,
        operations: List<OperationRecord>,
    ): CloneLifecycleState {
        val active = operations.filter {
            it.target.packageName == app.packageName && it.isActive()
        }.maxByOrNull(OperationRecord::updatedAtEpochMillis)
        return when (active?.kind) {
            OperationKind.ADD_CLONE -> CloneLifecycleState.PREPARING
            OperationKind.LAUNCH_CLONE -> CloneLifecycleState.LAUNCHING
            OperationKind.UPDATE_CLONE -> CloneLifecycleState.UPDATING
            OperationKind.REMOVE_CLONE -> CloneLifecycleState.REMOVING
            OperationKind.REPAIR_CLONE -> CloneLifecycleState.REPAIRING
            else -> if (operations.any {
                    it.target.packageName == app.packageName &&
                        it.phase == OperationPhase.FAILED
                }
            ) {
                CloneLifecycleState.NEEDS_REPAIR
            } else {
                app.state.toCloneLifecycle()
            }
        }
    }

    private fun OperationRecord.isActive(): Boolean = phase in setOf(
        OperationPhase.STARTED,
        OperationPhase.APPLYING,
        OperationPhase.ROLLING_BACK,
    )

    private fun GroupHealth.toSpaceLifecycle(): SpaceLifecycleState = when (this) {
        GroupHealth.PROVISIONING -> SpaceLifecycleState.CREATING
        GroupHealth.HEALTHY -> SpaceLifecycleState.READY
        GroupHealth.DAMAGED -> SpaceLifecycleState.NEEDS_REPAIR
        GroupHealth.DELETING -> SpaceLifecycleState.DELETING
    }

    private fun GroupAppState.toCloneLifecycle(): CloneLifecycleState = when (this) {
        GroupAppState.ADDED, GroupAppState.INSTALLING -> CloneLifecycleState.PREPARING
        GroupAppState.ENABLED -> CloneLifecycleState.READY
        GroupAppState.DISABLED -> CloneLifecycleState.UNSUPPORTED
        GroupAppState.SOURCE_MISSING -> CloneLifecycleState.SOURCE_MISSING
        GroupAppState.FAILED -> CloneLifecycleState.NEEDS_REPAIR
    }
}
