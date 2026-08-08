package org.apptwin.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAppRemovalCoordinatorTest {
    @Test
    fun `removal targets only the selected Group environment`() {
        val store = InMemoryGroupStore().apply {
            group(GROUP_A, EnvironmentBinding(4), GroupAppOrigin.SYSTEM_IMPORT)
            group(GROUP_B, EnvironmentBinding(5), GroupAppOrigin.SYSTEM_IMPORT)
        }
        val runtime = FakeRuntime().apply {
            installed += EnvironmentBinding(4) to LINE
            installed += EnvironmentBinding(5) to LINE
        }

        val result = coordinator(store, runtime).remove(GROUP_A, LINE)

        assertEquals(
            GroupAppRemovalResult.Succeeded(RuntimeGroupAppRemovalResult.Removed),
            result,
        )
        assertFalse(store.find(GROUP_A)!!.contains(LINE))
        assertTrue(store.find(GROUP_B)!!.contains(LINE))
        assertFalse(EnvironmentBinding(4) to LINE in runtime.installed)
        assertTrue(EnvironmentBinding(5) to LINE in runtime.installed)
        assertEquals(listOf(EnvironmentBinding(4) to LINE), runtime.calls)
    }

    @Test
    fun `runtime absence is success and then removes metadata`() {
        val store = storeWithApp(GroupAppOrigin.PLAY_STORE)
        val runtime = FakeRuntime()

        val result = coordinator(store, runtime).remove(GROUP_A, LINE)

        assertEquals(
            GroupAppRemovalResult.Succeeded(RuntimeGroupAppRemovalResult.AlreadyAbsent),
            result,
        )
        assertFalse(store.find(GROUP_A)!!.contains(LINE))
    }

    @Test
    fun `system import and Play Store memberships use the same removal path`() {
        GroupAppOrigin.entries.forEach { origin ->
            val store = storeWithApp(origin)
            val runtime = FakeRuntime().apply {
                installed += EnvironmentBinding(4) to LINE
            }

            assertTrue(coordinator(store, runtime).remove(GROUP_A, LINE) is GroupAppRemovalResult.Succeeded)
            assertFalse(store.find(GROUP_A)!!.contains(LINE))
            assertEquals(listOf(EnvironmentBinding(4) to LINE), runtime.calls)
        }
    }

    @Test
    fun `repeated removal is idempotent`() {
        val store = storeWithApp(GroupAppOrigin.SYSTEM_IMPORT)
        val runtime = FakeRuntime().apply {
            installed += EnvironmentBinding(4) to LINE
        }
        val coordinator = coordinator(store, runtime)

        assertTrue(coordinator.remove(GROUP_A, LINE) is GroupAppRemovalResult.Succeeded)
        assertEquals(GroupAppRemovalResult.AlreadyAbsent, coordinator.remove(GROUP_A, LINE))
        assertEquals(1, runtime.calls.size)
    }

    @Test
    fun `runtime failure preserves metadata and pending journal`() {
        val store = storeWithApp(GroupAppOrigin.SYSTEM_IMPORT)
        val runtime = FakeRuntime().apply { failure = IllegalStateException("engine failed") }
        val journal = FakeJournal()

        val result = coordinator(store, runtime, journal).remove(GROUP_A, LINE)

        assertTrue(result is GroupAppRemovalResult.Failed)
        assertTrue(store.find(GROUP_A)!!.contains(LINE))
        assertEquals(GroupAppRemovalPhase.STARTED, journal.listAll().single().phase)
    }

    @Test
    fun `started operation converges when runtime already removed the package before crash`() {
        val store = storeWithApp(GroupAppOrigin.SYSTEM_IMPORT)
        val runtime = FakeRuntime()
        val journal = FakeJournal().apply { write(startedOperation()) }

        coordinator(store, runtime, journal).reconcile()

        assertFalse(store.find(GROUP_A)!!.contains(LINE))
        assertTrue(journal.listAll().isEmpty())
        assertEquals(listOf(EnvironmentBinding(4) to LINE), runtime.calls)
    }

    @Test
    fun `runtime-removed operation completes metadata without touching runtime again`() {
        val backing = storeWithApp(GroupAppOrigin.PLAY_STORE)
        val store = FailingRemoveStore(backing)
        val runtime = FakeRuntime().apply {
            installed += EnvironmentBinding(4) to LINE
        }
        val journal = FakeJournal()
        val coordinator = coordinator(store, runtime, journal)

        assertTrue(coordinator.remove(GROUP_A, LINE) is GroupAppRemovalResult.Failed)
        assertTrue(backing.find(GROUP_A)!!.contains(LINE))
        assertEquals(GroupAppRemovalPhase.RUNTIME_REMOVED, journal.listAll().single().phase)
        assertEquals(1, runtime.calls.size)

        store.failRemove = false
        coordinator.reconcile()

        assertFalse(backing.find(GROUP_A)!!.contains(LINE))
        assertTrue(journal.listAll().isEmpty())
        assertEquals(1, runtime.calls.size)
    }

    @Test
    fun `failed reconcile leaves metadata and continues with another operation`() {
        val store = InMemoryGroupStore().apply {
            group(GROUP_A, EnvironmentBinding(4), GroupAppOrigin.SYSTEM_IMPORT)
            group(GROUP_B, EnvironmentBinding(5), GroupAppOrigin.PLAY_STORE)
        }
        val runtime = FakeRuntime().apply {
            installed += EnvironmentBinding(4) to LINE
            failureBindings += EnvironmentBinding(4)
        }
        val journal = FakeJournal().apply {
            write(startedOperation(groupId = GROUP_A, binding = EnvironmentBinding(4)))
            write(startedOperation(groupId = GROUP_B, binding = EnvironmentBinding(5)))
        }

        coordinator(store, runtime, journal).reconcile()

        assertTrue(store.find(GROUP_A)!!.contains(LINE))
        assertFalse(store.find(GROUP_B)!!.contains(LINE))
        assertEquals(listOf(GROUP_A), journal.listAll().map(GroupAppRemovalOperation::groupId))
    }

    @Test
    fun `stale journal never removes a newly re-added membership`() {
        val store = InMemoryGroupStore().apply {
            create(GROUP_A, "工作", EnvironmentBinding(4), 1)
            addApp(GROUP_A, LINE, 200, GroupAppOrigin.PLAY_STORE)
        }
        val runtime = FakeRuntime().apply {
            installed += EnvironmentBinding(4) to LINE
        }
        val journal = FakeJournal().apply {
            write(startedOperation(membershipAddedAt = 100))
        }

        coordinator(store, runtime, journal).reconcile()

        assertTrue(store.find(GROUP_A)!!.contains(LINE))
        assertTrue(EnvironmentBinding(4) to LINE in runtime.installed)
        assertTrue(runtime.calls.isEmpty())
        assertTrue(journal.listAll().isEmpty())
    }

    private fun coordinator(
        store: GroupStore,
        runtime: FakeRuntime,
        journal: FakeJournal = FakeJournal(),
    ) = GroupAppRemovalCoordinator(store, runtime, journal, clock = { 500 })

    private fun storeWithApp(origin: GroupAppOrigin) = InMemoryGroupStore().apply {
        group(GROUP_A, EnvironmentBinding(4), origin)
    }

    private fun InMemoryGroupStore.group(
        id: String,
        binding: EnvironmentBinding,
        origin: GroupAppOrigin,
    ) {
        create(id, "Group $id", binding, 1)
        addApp(id, LINE, 100, origin)
    }

    private fun startedOperation(
        groupId: String = GROUP_A,
        binding: EnvironmentBinding = EnvironmentBinding(4),
        membershipAddedAt: Long = 100,
    ) = GroupAppRemovalOperation(
        groupId = groupId,
        packageName = LINE,
        environmentBinding = binding,
        membershipAddedAtEpochMillis = membershipAddedAt,
        phase = GroupAppRemovalPhase.STARTED,
        startedAtEpochMillis = 50,
    )

    private class FakeRuntime : GroupAppRemovalRuntime {
        val installed = linkedSetOf<Pair<EnvironmentBinding, String>>()
        val calls = mutableListOf<Pair<EnvironmentBinding, String>>()
        val failureBindings = linkedSetOf<EnvironmentBinding>()
        var failure: Throwable? = null

        override fun removeApp(
            binding: EnvironmentBinding,
            packageName: String,
        ): RuntimeGroupAppRemovalResult {
            calls += binding to packageName
            failure?.let { throw it }
            if (binding in failureBindings) error("engine failed for ${binding.internalId}")
            return if (installed.remove(binding to packageName)) {
                RuntimeGroupAppRemovalResult.Removed
            } else {
                RuntimeGroupAppRemovalResult.AlreadyAbsent
            }
        }
    }

    private class FakeJournal : GroupAppRemovalJournal {
        private val operations = linkedMapOf<Pair<String, String>, GroupAppRemovalOperation>()
        override fun listAll(): List<GroupAppRemovalOperation> = operations.values.toList()
        override fun write(operation: GroupAppRemovalOperation) {
            operations[operation.groupId to operation.packageName] = operation
        }
        override fun remove(groupId: String, packageName: String) {
            operations.remove(groupId to packageName)
        }
    }

    private class FailingRemoveStore(
        private val backing: GroupStore,
    ) : GroupStore by backing {
        var failRemove = true
        override fun removeApp(groupId: String, packageName: String): Group? {
            if (failRemove) error("disk full")
            return backing.removeApp(groupId, packageName)
        }
    }

    private companion object {
        const val GROUP_A = "00000000-0000-0000-0000-000000000001"
        const val GROUP_B = "00000000-0000-0000-0000-000000000002"
        const val LINE = "jp.naver.line.android"
    }
}
