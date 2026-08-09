package org.apptwin

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.apptwin.groups.EnvironmentBinding
import org.apptwin.groups.Group
import org.apptwin.groups.GroupAppRemovalResult
import org.apptwin.groups.GroupHealth
import org.apptwin.groups.GroupMetadataKind
import org.apptwin.groups.GroupReconciliationResult
import org.apptwin.groups.GroupStoreLoadIssue
import org.apptwin.revision.InstalledAppEntry
import org.apptwin.runtime.RuntimeLaunchResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelLifecycleTest {
    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `navigation and picker survive ViewModel recreation through SavedStateHandle`() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler, "main")
        val ioDispatcher = StandardTestDispatcher(testScheduler, "io")
        Dispatchers.setMain(mainDispatcher)
        val savedState = SavedStateHandle()
        val operations = FakeOperations(groups = listOf(group()))
        val first = viewModel(savedState, operations, ioDispatcher)
        advanceUntilIdle()

        first.navigate(MainDestination.SETTINGS)
        assertEquals("SETTINGS", savedState.get<String>("main.destination"))
        first.openAppPicker(GROUP_ID)
        advanceUntilIdle()

        assertEquals(MainDestination.HOME, first.uiState.destination)
        assertEquals(GROUP_ID, first.uiState.appPickerGroupId)
        assertEquals(GROUP_ID, savedState.get<String>("main.appPickerGroupId"))

        val recreated = viewModel(savedState, operations, ioDispatcher)
        advanceUntilIdle()

        assertEquals(MainDestination.HOME, recreated.uiState.destination)
        assertEquals(GROUP_ID, recreated.uiState.appPickerGroupId)
    }

    @Test
    fun `clearing ViewModel cancels pending reconciliation without scheduling refresh`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val gate = CompletableDeferred<Unit>()
        val operations = FakeOperations(reconcileGate = gate)
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        val store = ViewModelStore().apply { put("main", viewModel) }
        runCurrent()
        assertTrue(operations.reconcileStarted)

        store.clear()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, operations.refreshCalls)
        assertTrue(viewModel.uiState.isRefreshing)
    }

    @Test
    fun `refresh performs blocking operations on injected IO dispatcher`() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler, "main")
        val ioDispatcher = StandardTestDispatcher(testScheduler, "io")
        Dispatchers.setMain(mainDispatcher)
        val operations = FakeOperations(expectedIoDispatcher = ioDispatcher)
        viewModel(SavedStateHandle(), operations, ioDispatcher)

        advanceUntilIdle()

        assertTrue(operations.refreshRanOnIoDispatcher)
        assertFalse(operations.refreshRanOnMainDispatcher)
    }

    @Test
    fun `late picker lookup cannot override newer navigation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val lookupGate = CompletableDeferred<Unit>()
        val operations = FakeOperations(groups = listOf(group()), findGroupGate = lookupGate)
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()

        viewModel.openAppPicker(GROUP_ID)
        runCurrent()
        viewModel.navigate(MainDestination.SETTINGS)
        lookupGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(MainDestination.SETTINGS, viewModel.uiState.destination)
        assertEquals(null, viewModel.uiState.appPickerGroupId)
    }

    @Test
    fun `reconciliation load issues are surfaced without discarding refresh results`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val issue = GroupStoreLoadIssue.CorruptMetadata(
            groupId = GROUP_ID,
            metadataKind = GroupMetadataKind.GROUP,
            metadataName = "group.properties",
            reason = "invalid schemaVersion",
        )
        val operations = FakeOperations(loadIssues = listOf(issue))
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)

        advanceUntilIdle()

        assertTrue(viewModel.uiState.message.orEmpty().contains("1 筆資料完整性問題"))
        assertEquals(listOf("corrupt metadata"), viewModel.uiState.dataWarnings)
        assertFalse(viewModel.uiState.isRefreshing)
        assertEquals(1, operations.refreshCalls)
    }

    private fun viewModel(
        savedState: SavedStateHandle,
        operations: MainOperations,
        ioDispatcher: CoroutineDispatcher,
    ) = MainViewModel(Application(), savedState, operations, ioDispatcher)

    private class FakeOperations(
        private val groups: List<Group> = emptyList(),
        private val reconcileGate: CompletableDeferred<Unit>? = null,
        private val expectedIoDispatcher: CoroutineDispatcher? = null,
        private val loadIssues: List<GroupStoreLoadIssue> = emptyList(),
        private val findGroupGate: CompletableDeferred<Unit>? = null,
    ) : MainOperations {
        var reconcileStarted = false
        var refreshCalls = 0
        var refreshRanOnIoDispatcher = false
        var refreshRanOnMainDispatcher = false

        override suspend fun refreshSnapshot(): MainRefreshSnapshot {
            refreshCalls++
            val interceptor = currentCoroutineContext()[ContinuationInterceptor]
            refreshRanOnIoDispatcher = interceptor == expectedIoDispatcher
            refreshRanOnMainDispatcher = interceptor == Dispatchers.Main
            return MainRefreshSnapshot(
                storage = StorageStatus(false, 0, 0),
                entries = emptyList<InstalledAppEntry>(),
                groups = groups,
                activeRevisions = emptyMap(),
                dataWarnings = loadIssues.map { "corrupt metadata" },
            )
        }

        override suspend fun findGroup(groupId: String): Group? {
            findGroupGate?.await()
            return groups.firstOrNull { it.id == groupId }
        }

        override suspend fun createGroup(name: String): Group = error("unused")
        override suspend fun renameGroup(groupId: String, name: String): Group? = error("unused")
        override suspend fun deleteGroup(groupId: String): Group? = error("unused")
        override suspend fun addAppToGroup(groupId: String, packageName: String): Group =
            error("unused")
        override suspend fun launchGroupApp(item: GroupAppItem): RuntimeLaunchResult = error("unused")
        override suspend fun uninstallGroupApp(item: GroupAppItem): GroupAppRemovalResult =
            error("unused")

        override suspend fun reconcileGroups(): GroupReconciliationResult {
            reconcileStarted = true
            reconcileGate?.await()
            return GroupReconciliationResult(loadIssues)
        }

        override suspend fun reconcileAppRemovals() = Unit
    }

    private companion object {
        const val GROUP_ID = "00000000-0000-0000-0000-000000000001"

        fun group() = Group(
            id = GROUP_ID,
            name = "工作",
            createdAtEpochMillis = 1,
            environmentBinding = EnvironmentBinding(7),
            health = GroupHealth.HEALTHY,
        )
    }
}
