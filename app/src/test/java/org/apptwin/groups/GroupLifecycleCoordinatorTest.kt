package org.apptwin.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        val coordinator = coordinator(store, runtime, journal, GROUP_A)

        val created = coordinator.createGroup("工作")

        assertEquals(EnvironmentBinding(4), created.environmentBinding)
        assertEquals(GroupHealth.HEALTHY, created.health)
        assertTrue(runtime.environmentExists(EnvironmentBinding(4)))
        assertTrue(journal.listAll().isEmpty())
        try {
            store.completeProvisioning(GROUP_A, EnvironmentBinding(7))
            fail("A healthy Group must not be rebound")
        } catch (_: IllegalArgumentException) {
            // Expected: only PROVISIONING Groups may receive a binding.
        }
        assertEquals(EnvironmentBinding(4), store.find(GROUP_A)?.environmentBinding)
    }

    @Test
    fun `shared legacy binding is split and only GroupApp private data is copied`() {
        val store = InMemoryGroupStore()
        val first = legacyGroup(GROUP_A, createdAt = 1, binding = EnvironmentBinding(3))
        val second = legacyGroup(
            GROUP_B,
            createdAt = 2,
            binding = EnvironmentBinding(3),
            apps = listOf(GroupApp("jp.naver.line.android", 10)),
        )
        store.import(first)
        store.import(second)
        val runtime = FakeRuntime(nextId = 4).apply { environments += EnvironmentBinding(3) }

        coordinator(store, runtime, FakeJournal()).reconcile()

        val firstAfter = requireNotNull(store.find(GROUP_A))
        val secondAfter = requireNotNull(store.find(GROUP_B))
        assertEquals(EnvironmentBinding(3), firstAfter.environmentBinding)
        assertNotEquals(firstAfter.environmentBinding, secondAfter.environmentBinding)
        assertEquals(GroupHealth.HEALTHY, secondAfter.health)
        assertEquals(
            CopyCall(
                EnvironmentBinding(3),
                requireNotNull(secondAfter.environmentBinding),
                listOf("jp.naver.line.android"),
            ),
            runtime.copyCalls.single(),
        )
    }

    @Test
    fun `legacy environment zero always migrates to a dedicated binding`() {
        val store = InMemoryGroupStore().apply {
            import(legacyGroup(GROUP_A, binding = EnvironmentBinding(0)))
        }
        val runtime = FakeRuntime(nextId = 5).apply { environments += EnvironmentBinding(0) }

        coordinator(store, runtime, FakeJournal()).reconcile()

        assertEquals(EnvironmentBinding(5), store.find(GROUP_A)?.environmentBinding)
        assertTrue(runtime.environmentExists(EnvironmentBinding(0)))
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
    fun `damaged Group keeps ownership and legacy Group cannot adopt its binding`() {
        val store = InMemoryGroupStore()
        store.create(GROUP_A, "工作", EnvironmentBinding(8), 1)
        store.updateHealth(GROUP_A, GroupHealth.DAMAGED)
        store.import(legacyGroup(GROUP_B, createdAt = 0, binding = EnvironmentBinding(8)))
        val runtime = FakeRuntime(nextId = 9).apply { environments += EnvironmentBinding(8) }

        coordinator(store, runtime, FakeJournal()).reconcile()

        assertEquals(EnvironmentBinding(8), store.find(GROUP_A)?.environmentBinding)
        assertEquals(GroupHealth.DAMAGED, store.find(GROUP_A)?.health)
        assertEquals(EnvironmentBinding(9), store.find(GROUP_B)?.environmentBinding)
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
    fun `environment-created migration journal resumes after restart`() {
        val app = GroupApp("com.google.android.apps.maps", 10)
        val store = InMemoryGroupStore().apply {
            import(legacyGroup(GROUP_A, binding = EnvironmentBinding(0), apps = listOf(app)))
        }
        val runtime = FakeRuntime(nextId = 6).apply {
            environments += EnvironmentBinding(0)
            environments += EnvironmentBinding(5)
        }
        val journal = FakeJournal().apply {
            write(
                GroupOperation(
                    groupId = GROUP_A,
                    type = GroupOperationType.MIGRATE,
                    phase = GroupOperationPhase.ENVIRONMENT_CREATED,
                    groupName = "工作",
                    environmentBinding = EnvironmentBinding(5),
                    sourceBinding = EnvironmentBinding(0),
                    startedAtEpochMillis = 2,
                ),
            )
        }

        coordinator(store, runtime, journal).reconcile()

        assertEquals(EnvironmentBinding(5), store.find(GROUP_A)?.environmentBinding)
        assertEquals(GroupHealth.HEALTHY, store.find(GROUP_A)?.health)
        assertTrue(journal.listAll().isEmpty())
        assertEquals(listOf("com.google.android.apps.maps"), runtime.copyCalls.single().packages)
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

    private fun legacyGroup(
        id: String,
        createdAt: Long = 1,
        binding: EnvironmentBinding?,
        apps: List<GroupApp> = emptyList(),
    ) = Group(
        id = id,
        name = "Legacy",
        createdAtEpochMillis = createdAt,
        environmentBinding = binding,
        health = GroupHealth.PROVISIONING,
        apps = apps,
        schemaVersion = 1,
    )

    private class FakeJournal : GroupOperationJournal {
        private val operations = linkedMapOf<String, GroupOperation>()
        override fun listAll(): List<GroupOperation> = operations.values.toList()
        override fun write(operation: GroupOperation) {
            operations[operation.groupId] = operation
        }
        override fun remove(groupId: String) {
            operations.remove(groupId)
        }
    }

    private class FakeRuntime(private var nextId: Int) : GroupEnvironmentRuntime {
        val environments = linkedSetOf<EnvironmentBinding>()
        val created = mutableListOf<EnvironmentBinding>()
        val deleted = mutableListOf<EnvironmentBinding>()
        val copyCalls = mutableListOf<CopyCall>()
        val namedEnvironments = mutableMapOf<String, EnvironmentBinding>()

        override fun createEnvironment(groupId: String, groupName: String): EnvironmentBinding =
            EnvironmentBinding(nextId++).also {
                environments += it
                created += it
                namedEnvironments[groupId] = it
            }

        override fun findEnvironment(groupId: String): EnvironmentBinding? = namedEnvironments[groupId]

        override fun environmentExists(binding: EnvironmentBinding): Boolean = binding in environments

        override fun copyPrivateAppData(
            source: EnvironmentBinding,
            destination: EnvironmentBinding,
            apps: List<GroupApp>,
        ) {
            copyCalls += CopyCall(source, destination, apps.map(GroupApp::packageName))
        }

        override fun deleteEnvironment(binding: EnvironmentBinding) {
            environments -= binding
            namedEnvironments.entries.removeAll { it.value == binding }
            deleted += binding
        }
    }

    private data class CopyCall(
        val source: EnvironmentBinding,
        val destination: EnvironmentBinding,
        val packages: List<String>,
    )

    private companion object {
        const val GROUP_A = "00000000-0000-0000-0000-000000000001"
        const val GROUP_B = "00000000-0000-0000-0000-000000000002"
    }
}
