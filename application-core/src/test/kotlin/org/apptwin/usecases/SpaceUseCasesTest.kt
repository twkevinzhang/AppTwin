package org.apptwin.usecases

import java.util.UUID
import org.apptwin.groups.EnvironmentBinding
import org.apptwin.groups.GroupAppRemovalCoordinator
import org.apptwin.groups.GroupAppRemovalJournal
import org.apptwin.groups.GroupAppRemovalOperation
import org.apptwin.groups.GroupAppRemovalResult
import org.apptwin.groups.GroupEnvironmentRuntime
import org.apptwin.groups.GroupLifecycleCoordinator
import org.apptwin.groups.GroupOperation
import org.apptwin.groups.GroupOperationJournal
import org.apptwin.groups.InMemoryGroupStore
import org.apptwin.groups.RuntimeGroupAppRemovalResult
import org.apptwin.operations.OperationPhase
import org.apptwin.operations.OperationRecord
import org.apptwin.operations.OperationRecordStore
import org.apptwin.operations.OperationTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceUseCasesTest {
    @Test
    fun `create validates name and returns typed result`() {
        val fixture = Fixture()
        val useCase = CreateSpaceUseCase(fixture.lifecycle)

        assertEquals(
            CreateSpaceResult.Rejected(CreateSpaceRejection.BLANK_NAME),
            useCase.execute("   "),
        )
        val created = useCase.execute("  工作  ") as CreateSpaceResult.Created
        assertEquals("工作", created.space.name)
        assertTrue(fixture.runtime.environmentExists(requireNotNull(created.space.environmentBinding)))
    }

    @Test
    fun `add source missing has no membership while ready source adds exactly once`() {
        val fixture = Fixture()
        val space = fixture.createSpace()
        val records = TestOperationStore()
        var sourceResult: CloneSourcePreparationResult = CloneSourcePreparationResult.SourceMissing
        val useCase = AddCloneAppUseCase(
            fixture.store,
            CloneSourcePreparer { sourceResult },
            tracker(records),
            clock = { 50L },
        )

        assertEquals(AddCloneAppResult.SourceMissing, useCase.execute(space.id, PACKAGE))
        assertFalse(requireNotNull(fixture.store.find(space.id)).contains(PACKAGE))
        assertTrue(records.listPending().isEmpty())

        sourceResult = CloneSourcePreparationResult.Ready
        assertTrue(useCase.execute(space.id, PACKAGE) is AddCloneAppResult.Added)
        assertEquals(AddCloneAppResult.AlreadyPresent, useCase.execute(space.id, PACKAGE))
        assertEquals(1, requireNotNull(fixture.store.find(space.id)).apps.size)
    }

    @Test
    fun `launch source missing is first class and failed runtime remains reconcilable`() {
        val fixture = Fixture()
        val space = fixture.createSpace()
        fixture.store.addApp(space.id, PACKAGE, 10L)
        val records = TestOperationStore()
        var sourceResult: CloneSourcePreparationResult = CloneSourcePreparationResult.SourceMissing
        var runtimeResult: CloneRuntimeLaunchResult = CloneRuntimeLaunchResult.Started
        val useCase = LaunchCloneAppUseCase(
            fixture.store,
            CloneSourcePreparer { sourceResult },
            CloneLaunchRuntime { _, _ -> runtimeResult },
            tracker(records),
        )

        assertEquals(LaunchCloneAppResult.SourceMissing, useCase.execute(space.id, PACKAGE))
        assertEquals(
            org.apptwin.groups.GroupAppState.SOURCE_MISSING,
            requireNotNull(fixture.store.find(space.id)).apps.single().state,
        )

        sourceResult = CloneSourcePreparationResult.Ready
        runtimeResult = CloneRuntimeLaunchResult.Failed(CloneLaunchFailure.ACTIVITY_START_FAILED)
        assertEquals(
            LaunchCloneAppResult.Failed(CloneLaunchFailure.ACTIVITY_START_FAILED),
            useCase.execute(space.id, PACKAGE),
        )
        val pending = records.listPending().single()
        assertEquals(OperationPhase.FAILED, pending.phase)
        assertEquals("LAUNCH_FAILED", pending.failureCode)
    }

    @Test
    fun `already current enabled clone starts without launch journal or state transition`() {
        val fixture = Fixture()
        val space = fixture.createSpace()
        fixture.store.addApp(space.id, PACKAGE, 10L)
        fixture.store.updateAppState(space.id, PACKAGE, org.apptwin.groups.GroupAppState.ENABLED)
        val records = TestOperationStore()
        var durableLaunches = 0
        var pureLaunches = 0
        val useCase = LaunchCloneAppUseCase(
            fixture.store,
            CloneSourcePreparer { CloneSourcePreparationResult.AlreadyCurrent },
            runtime = CloneLaunchRuntime { _, _ ->
                durableLaunches += 1
                CloneRuntimeLaunchResult.Started
            },
            operations = tracker(records),
            pureSource = CloneSourceCurrentVerifier { true },
            pureRuntime = CloneLaunchRuntime { _, _ ->
                pureLaunches += 1
                CloneRuntimeLaunchResult.Started
            },
        )

        assertEquals(LaunchCloneAppResult.Started, useCase.execute(space.id, PACKAGE))
        assertEquals(1, pureLaunches)
        assertEquals(0, durableLaunches)
        assertTrue(records.listPending().isEmpty())
        assertEquals(0, records.saveCalls)
        assertEquals(
            org.apptwin.groups.GroupAppState.ENABLED,
            requireNotNull(fixture.store.find(space.id)).apps.single().state,
        )
    }

    @Test
    fun `pure launch marker miss falls back to durable repair transaction`() {
        val fixture = Fixture()
        val space = fixture.createSpace()
        fixture.store.addApp(space.id, PACKAGE, 10L)
        fixture.store.updateAppState(space.id, PACKAGE, org.apptwin.groups.GroupAppState.ENABLED)
        val records = TestOperationStore()
        var durableLaunches = 0
        val useCase = LaunchCloneAppUseCase(
            fixture.store,
            CloneSourcePreparer { CloneSourcePreparationResult.AlreadyCurrent },
            runtime = CloneLaunchRuntime { _, _ ->
                durableLaunches += 1
                CloneRuntimeLaunchResult.Started
            },
            operations = tracker(records),
            pureSource = CloneSourceCurrentVerifier { true },
            pureRuntime = CloneLaunchRuntime { _, _ -> CloneRuntimeLaunchResult.RepairRequired },
        )

        assertEquals(LaunchCloneAppResult.Started, useCase.execute(space.id, PACKAGE))
        assertEquals(1, durableLaunches)
        assertTrue(records.listPending().isEmpty())
        assertEquals(
            org.apptwin.groups.GroupAppState.ENABLED,
            requireNotNull(fixture.store.find(space.id)).apps.single().state,
        )
    }

    @Test
    fun `source failure records durable failed operation and app state`() {
        val fixture = Fixture()
        val space = fixture.createSpace()
        fixture.store.addApp(space.id, PACKAGE, 10L)
        val records = TestOperationStore()
        val useCase = LaunchCloneAppUseCase(
            fixture.store,
            CloneSourcePreparer { error("source metadata unavailable") },
            runtime = CloneLaunchRuntime { _, _ -> CloneRuntimeLaunchResult.Started },
            operations = tracker(records),
            pureRuntime = CloneLaunchRuntime { _, _ -> CloneRuntimeLaunchResult.Started },
        )

        val result = useCase.execute(space.id, PACKAGE)

        assertTrue(result is LaunchCloneAppResult.Failed)
        assertEquals(
            org.apptwin.groups.GroupAppState.FAILED,
            requireNotNull(fixture.store.find(space.id)).apps.single().state,
        )
        val failed = records.listPending().single()
        assertEquals(OperationPhase.FAILED, failed.phase)
        assertEquals("LAUNCH_FAILED", failed.failureCode)
    }

    @Test
    fun `named remove and delete use cases preserve coordinator contracts`() {
        val fixture = Fixture()
        val space = fixture.createSpace()
        fixture.store.addApp(space.id, PACKAGE, 10L)
        val removal = GroupAppRemovalCoordinator(
            fixture.store,
            runtime = { _, _ -> RuntimeGroupAppRemovalResult.Removed },
            journal = TestRemovalJournal(),
            clock = { 20L },
        )

        assertTrue(
            RemoveCloneAppUseCase(removal).execute(space.id, PACKAGE) is
                GroupAppRemovalResult.Succeeded,
        )
        assertEquals(DeleteSpaceResult.Deleted, DeleteSpaceUseCase(fixture.lifecycle).execute(space.id))
        assertEquals(
            DeleteSpaceResult.AlreadyAbsent,
            DeleteSpaceUseCase(fixture.lifecycle).execute(space.id),
        )
    }

    @Test
    fun `clear clone storage retains clone membership and reports runtime failures`() {
        val fixture = Fixture()
        val space = fixture.createSpace()
        fixture.store.addApp(space.id, PACKAGE, 10L)
        var cleared: Pair<EnvironmentBinding, String>? = null
        val useCase = ClearCloneStorageUseCase(fixture.store) { binding, packageName ->
            cleared = binding to packageName
        }

        assertEquals(ClearCloneStorageResult.Cleared, useCase.execute(space.id, PACKAGE))
        assertEquals(requireNotNull(space.environmentBinding) to PACKAGE, cleared)
        assertTrue(requireNotNull(fixture.store.find(space.id)).contains(PACKAGE))

        val failed = ClearCloneStorageUseCase(fixture.store) { _, _ -> error("storage unavailable") }
            .execute(space.id, PACKAGE)
        assertTrue(failed is ClearCloneStorageResult.Failed)
        assertTrue(requireNotNull(fixture.store.find(space.id)).contains(PACKAGE))
    }

    @Test
    fun `clear clone storage rejects absent and unavailable targets without invoking runtime`() {
        val fixture = Fixture()
        val space = fixture.createSpace()
        var invoked = false
        val useCase = ClearCloneStorageUseCase(fixture.store) { _, _ -> invoked = true }

        assertEquals(ClearCloneStorageResult.CloneNotFound, useCase.execute(space.id, PACKAGE))
        assertEquals(ClearCloneStorageResult.SpaceNotFound, useCase.execute("missing", PACKAGE))
        assertFalse(invoked)
    }

    @Test
    fun `clear space storage resets clones in order and stops at the first failure`() {
        val fixture = Fixture()
        val space = fixture.createSpace()
        val firstPackage = "com.example.first"
        val secondPackage = "com.example.second"
        val thirdPackage = "com.example.third"
        fixture.store.addApp(space.id, firstPackage, 10L)
        fixture.store.addApp(space.id, secondPackage, 11L)
        fixture.store.addApp(space.id, thirdPackage, 12L)
        val attemptedPackages = mutableListOf<String>()
        val useCase = ClearSpaceStorageUseCase(fixture.store) { binding, packageName ->
            assertEquals(requireNotNull(space.environmentBinding), binding)
            attemptedPackages += packageName
            if (packageName == secondPackage) error("storage unavailable")
        }

        val result = useCase.execute(space.id)

        assertEquals(listOf(firstPackage, secondPackage), attemptedPackages)
        assertTrue(result is ClearSpaceStorageResult.PartiallyCleared)
        result as ClearSpaceStorageResult.PartiallyCleared
        assertEquals(1, result.clearedCloneCount)
        assertEquals(secondPackage, result.failedPackageName)
        assertTrue(requireNotNull(fixture.store.find(space.id)).contains(firstPackage))
        assertTrue(requireNotNull(fixture.store.find(space.id)).contains(secondPackage))
        assertTrue(requireNotNull(fixture.store.find(space.id)).contains(thirdPackage))
    }

    @Test
    fun `clear space storage retains space metadata and reports completed clone count`() {
        val fixture = Fixture()
        val space = fixture.createSpace()
        fixture.store.addApp(space.id, PACKAGE, 10L)
        val useCase = ClearSpaceStorageUseCase(fixture.store) { _, _ -> Unit }

        assertEquals(ClearSpaceStorageResult.Cleared(1), useCase.execute(space.id))
        assertEquals(space.name, requireNotNull(fixture.store.find(space.id)).name)
        assertTrue(requireNotNull(fixture.store.find(space.id)).contains(PACKAGE))
    }

    private fun tracker(store: TestOperationStore) = OperationTracker(
        store,
        idFactory = { UUID.randomUUID().toString() },
        clock = object {
            var now = 100L
            fun next() = now++
        }::next,
    )

    private class Fixture {
        val store = InMemoryGroupStore()
        val runtime = TestEnvironmentRuntime()
        val lifecycle = GroupLifecycleCoordinator(
            store = store,
            runtime = runtime,
            journal = TestGroupJournal(),
            idFactory = { UUID.randomUUID().toString() },
            clock = { 1L },
        )

        fun createSpace() = (CreateSpaceUseCase(lifecycle).execute("工作") as
            CreateSpaceResult.Created).space
    }

    private companion object {
        const val PACKAGE = "com.example.app"
    }
}

private class TestOperationStore : OperationRecordStore {
    private val records = linkedMapOf<String, OperationRecord>()
    var saveCalls = 0
        private set
    override fun listPending(): List<OperationRecord> = records.values.toList()
    override fun find(id: String): OperationRecord? = records[id]
    override fun save(record: OperationRecord) {
        saveCalls += 1
        records[record.id] = record
    }
    override fun remove(id: String) {
        records.remove(id)
    }
}

private class TestEnvironmentRuntime : GroupEnvironmentRuntime {
    private val owners = linkedMapOf<String, EnvironmentBinding>()
    private val existing = linkedSetOf<EnvironmentBinding>()

    override fun createEnvironment(groupId: String, groupName: String): EnvironmentBinding =
        EnvironmentBinding(existing.size + 1).also {
            owners[groupId] = it
            existing += it
        }

    override fun findEnvironment(groupId: String): EnvironmentBinding? = owners[groupId]
    override fun environmentExists(binding: EnvironmentBinding): Boolean = binding in existing
    override fun deleteEnvironment(binding: EnvironmentBinding) {
        existing -= binding
    }
}

private class TestGroupJournal : GroupOperationJournal {
    private val operations = linkedMapOf<String, GroupOperation>()
    override fun listAll(): List<GroupOperation> = operations.values.toList()
    override fun write(operation: GroupOperation) {
        operations[operation.groupId] = operation
    }
    override fun remove(groupId: String) {
        operations.remove(groupId)
    }
}

private class TestRemovalJournal : GroupAppRemovalJournal {
    private val operations = linkedMapOf<Pair<String, String>, GroupAppRemovalOperation>()
    override fun listAll(): List<GroupAppRemovalOperation> = operations.values.toList()
    override fun write(operation: GroupAppRemovalOperation) {
        operations[operation.groupId to operation.packageName] = operation
    }
    override fun remove(groupId: String, packageName: String) {
        operations.remove(groupId to packageName)
    }
}
