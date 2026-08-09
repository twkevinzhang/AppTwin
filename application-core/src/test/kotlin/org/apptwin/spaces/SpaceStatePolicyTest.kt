package org.apptwin.spaces

import java.util.UUID
import org.apptwin.groups.EnvironmentBinding
import org.apptwin.groups.Group
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupAppState
import org.apptwin.groups.GroupHealth
import org.apptwin.operations.OperationKind
import org.apptwin.operations.OperationPhase
import org.apptwin.operations.OperationRecord
import org.apptwin.operations.OperationTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceStatePolicyTest {
    @Test
    fun `durable operations override persisted technical state`() {
        val group = group(GroupHealth.HEALTHY, GroupAppState.ENABLED)
        val operations = listOf(
            operation(group.id, OperationKind.REPAIR_SPACE),
            operation(group.id, OperationKind.UPDATE_CLONE, PACKAGE),
        )

        val state = SpaceStatePolicy.assess(group, operations)

        assertEquals(SpaceLifecycleState.REPAIRING, state.lifecycle)
        assertEquals(CloneLifecycleState.UPDATING, state.clones.single().lifecycle)
    }

    @Test
    fun `source missing remains a first class clone state`() {
        val state = SpaceStatePolicy.assess(group(GroupHealth.HEALTHY, GroupAppState.SOURCE_MISSING))

        assertEquals(SpaceLifecycleState.READY, state.lifecycle)
        assertEquals(CloneLifecycleState.SOURCE_MISSING, state.clones.single().lifecycle)
    }

    private fun group(health: GroupHealth, appState: GroupAppState) = Group(
        id = UUID.randomUUID().toString(),
        name = "工作",
        createdAtEpochMillis = 1L,
        environmentBinding = EnvironmentBinding(2),
        health = health,
        apps = listOf(GroupApp(PACKAGE, 2L, appState)),
    )

    private fun operation(spaceId: String, kind: OperationKind, packageName: String? = null) =
        OperationRecord(
            id = UUID.randomUUID().toString(),
            kind = kind,
            target = OperationTarget(spaceId, packageName),
            phase = OperationPhase.APPLYING,
            startedAtEpochMillis = 3L,
            updatedAtEpochMillis = 3L,
        )

    private companion object {
        const val PACKAGE = "com.example.app"
    }
}
