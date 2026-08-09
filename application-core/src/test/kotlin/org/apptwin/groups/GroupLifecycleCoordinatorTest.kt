package org.apptwin.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GroupLifecycleCoordinatorTest {
    @Test
    fun `creation allocates and permanently binds a dedicated environment`() {
        val store = InMemoryGroupStore()
        val runtime = FakeRuntime(nextId = 4)
        val journal = FakeJournal()

        val created = coordinator(store, runtime, journal, GROUP_A).createGroup("工作")

        assertEquals(EnvironmentBinding(4), created.environmentBinding)
        assertEquals(GroupHealth.HEALTHY, created.health)
        assertTrue(runtime.environmentExists(EnvironmentBinding(4)))
        assertTrue(journal.listAll().isEmpty())
    }

    @Test
    fun `missing healthy environment marks Group damaged without replacement`() {
        val store = InMemoryGroupStore()
        store.create(GROUP_A, "工作", EnvironmentBinding(8), 1)
        val runtime = FakeRuntime(nextId = 9)

        coordinator(store, runtime, FakeJournal()).reconcile()

        assertEquals(GroupHealth.DAMAGED, store.find(GROUP_A)?.health)
        assertEquals(EnvironmentBinding(8), store.find(GROUP_A)?.environmentBinding)
        assertTrue(runtime.created.isEmpty())
    }

    @Test
    fun `reconcile reports corrupt metadata while still processing healthy Groups`() {
        val backing = InMemoryGroupStore().apply {
            create(GROUP_A, "工作", EnvironmentBinding(8), 1)
        }
        val issue = GroupStoreLoadIssue.CorruptMetadata(
            groupId = GROUP_B,
            metadataKind = GroupMetadataKind.GROUP,
            metadataName = "group.properties",
            reason = "invalid timestamp",
        )
        val store = object : GroupStore by backing {
            override fun loadSnapshot() = GroupStoreSnapshot(backing.listAll(), listOf(issue))
        }

        val result = coordinator(store, FakeRuntime(nextId = 9), FakeJournal()).reconcile()

        assertEquals(listOf(issue), result.loadIssues)
        assertEquals(GroupHealth.DAMAGED, backing.find(GROUP_A)?.health)
    }

    @Test
    fun `metadata failure rolls back newly allocated environment`() {
        val backing = InMemoryGroupStore()
        val failingStore = object : GroupStore by backing {
            override fun create(
                id: String,
                name: String,
                environmentBinding: EnvironmentBinding,
                createdAtEpochMillis: Long,
            ): Group = error("disk full")
        }
        val runtime = FakeRuntime(nextId = 6)
        val journal = FakeJournal()

        try {
            coordinator(failingStore, runtime, journal, GROUP_A).createGroup("工作")
            fail("Creation should fail")
        } catch (_: IllegalStateException) {
            // Expected.
        }

        assertFalse(runtime.environmentExists(EnvironmentBinding(6)))
        assertEquals(listOf(EnvironmentBinding(6)), runtime.deleted)
        assertTrue(journal.listAll().isEmpty())
    }

    @Test
    fun `journal cleanup failure after metadata commit keeps healthy Group and environment`() {
        val store = InMemoryGroupStore()
        val runtime = FakeRuntime(nextId = 6)
        val journal = FakeJournal(failRemoveCount = 1)
        val coordinator = coordinator(store, runtime, journal, GROUP_A)

        val created = coordinator.createGroup("工作")

        assertEquals(created, store.find(GROUP_A))
        assertTrue(runtime.environmentExists(EnvironmentBinding(6)))
        assertEquals(GroupHealth.HEALTHY, store.find(GROUP_A)?.health)
        assertEquals(GroupOperationPhase.ENVIRONMENT_CREATED, journal.listAll().single().phase)

        coordinator.reconcile()

        assertEquals(created, store.find(GROUP_A))
        assertTrue(runtime.environmentExists(EnvironmentBinding(6)))
        assertTrue(journal.listAll().isEmpty())
        assertTrue(runtime.deleted.isEmpty())
    }

    @Test
    fun `rollback failure preserves journal for restart recovery`() {
        val backing = InMemoryGroupStore()
        val failingStore = object : GroupStore by backing {
            override fun create(
                id: String,
                name: String,
                environmentBinding: EnvironmentBinding,
                createdAtEpochMillis: Long,
            ): Group = error("disk full")
        }
        val runtime = FakeRuntime(nextId = 6, failDeleteCount = 1)
        val journal = FakeJournal()

        val failure = runCatching {
            coordinator(failingStore, runtime, journal, GROUP_A).createGroup("工作")
        }.exceptionOrNull()!!

        assertEquals("disk full", failure.message)
        assertEquals("environment delete failed", failure.suppressed.single().message)
        assertTrue(runtime.environmentExists(EnvironmentBinding(6)))
        assertEquals(GroupOperationPhase.ENVIRONMENT_CREATED, journal.listAll().single().phase)

        coordinator(failingStore, runtime, journal, GROUP_A).reconcile()

        assertFalse(runtime.environmentExists(EnvironmentBinding(6)))
        assertTrue(journal.listAll().isEmpty())
    }

    @Test
    fun `started creation journal removes environment created just before a crash`() {
        val runtime = FakeRuntime(nextId = 6).apply {
            namedEnvironments[GROUP_A] = EnvironmentBinding(5)
            environments += EnvironmentBinding(5)
        }
        val journal = FakeJournal().apply {
            write(
                GroupOperation(
                    groupId = GROUP_A,
                    type = GroupOperationType.CREATE,
                    phase = GroupOperationPhase.STARTED,
                    groupName = "工作",
                    startedAtEpochMillis = 2,
                ),
            )
        }

        coordinator(InMemoryGroupStore(), runtime, journal).reconcile()

        assertFalse(runtime.environmentExists(EnvironmentBinding(5)))
        assertTrue(journal.listAll().isEmpty())
    }

    @Test
    fun `deletion removes only the Group owned environment and metadata`() {
        val store = InMemoryGroupStore().apply {
            create(GROUP_A, "工作", EnvironmentBinding(4), 1)
            create(GROUP_B, "生活", EnvironmentBinding(5), 2)
        }
        val runtime = FakeRuntime(nextId = 6).apply {
            environments += EnvironmentBinding(4)
            environments += EnvironmentBinding(5)
        }

        assertTrue(coordinator(store, runtime, FakeJournal()).deleteGroup(GROUP_A))

        assertNull(store.find(GROUP_A))
        assertFalse(runtime.environmentExists(EnvironmentBinding(4)))
        assertTrue(runtime.environmentExists(EnvironmentBinding(5)))
        assertEquals(EnvironmentBinding(5), store.find(GROUP_B)?.environmentBinding)
    }

    private fun coordinator(
        store: GroupStore,
        runtime: FakeRuntime,
        journal: FakeJournal,
        id: String = GROUP_A,
    ) = GroupLifecycleCoordinator(
        store = store,
        runtime = runtime,
        journal = journal,
        idFactory = { id },
        clock = { 100 },
    )

    private class FakeJournal(
        private var failRemoveCount: Int = 0,
    ) : GroupOperationJournal {
        private val operations = linkedMapOf<String, GroupOperation>()
        override fun listAll(): List<GroupOperation> = operations.values.toList()
        override fun write(operation: GroupOperation) {
            operations[operation.groupId] = operation
        }
        override fun remove(groupId: String) {
            if (failRemoveCount > 0) {
                failRemoveCount -= 1
                error("journal remove failed")
            }
            operations.remove(groupId)
        }
    }

    private class FakeRuntime(
        private var nextId: Int,
        private var failDeleteCount: Int = 0,
    ) : GroupEnvironmentRuntime {
        val environments = linkedSetOf<EnvironmentBinding>()
        val created = mutableListOf<EnvironmentBinding>()
        val deleted = mutableListOf<EnvironmentBinding>()
        val namedEnvironments = mutableMapOf<String, EnvironmentBinding>()

        override fun createEnvironment(groupId: String, groupName: String): EnvironmentBinding =
            EnvironmentBinding(nextId++).also {
                environments += it
                created += it
                namedEnvironments[groupId] = it
            }

        override fun findEnvironment(groupId: String): EnvironmentBinding? = namedEnvironments[groupId]

        override fun environmentExists(binding: EnvironmentBinding): Boolean = binding in environments

        override fun deleteEnvironment(binding: EnvironmentBinding) {
            if (failDeleteCount > 0) {
                failDeleteCount -= 1
                error("environment delete failed")
            }
            environments -= binding
            namedEnvironments.entries.removeAll { it.value == binding }
            deleted += binding
        }
    }

    private companion object {
        const val GROUP_A = "00000000-0000-0000-0000-000000000001"
        const val GROUP_B = "00000000-0000-0000-0000-000000000002"
    }
}
