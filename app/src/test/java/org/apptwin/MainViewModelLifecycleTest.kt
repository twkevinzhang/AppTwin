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
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupMetadataKind
import org.apptwin.groups.GroupReconciliationResult
import org.apptwin.groups.GroupStoreLoadIssue
import org.apptwin.revision.InstalledAppEntry
import org.apptwin.repair.RepairExecutionResult
import org.apptwin.permissions.ClonePermissionAction
import org.apptwin.permissions.ClonePermissionCategory
import org.apptwin.permissions.ClonePermissionSummary
import org.apptwin.permissions.ClonePermissionTarget
import org.apptwin.permissions.ClonePermissionVirtualScope
import org.apptwin.runtime.RuntimeLaunchResult
import org.apptwin.gms.GmsStartupResult
import org.apptwin.gms.usecases.GmsLifecycleResult
import org.apptwin.gms.usecases.GmsReconciliationResult
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
        assertEquals(GROUP_ID, first.uiState.selectedGroupId)
        assertEquals(GROUP_ID, first.uiState.appPickerGroupId)
        assertEquals(GROUP_ID, savedState.get<String>("main.selectedGroupId"))
        assertEquals(GROUP_ID, savedState.get<String>("main.appPickerGroupId"))

        val recreated = viewModel(savedState, operations, ioDispatcher)
        advanceUntilIdle()

        assertEquals(MainDestination.HOME, recreated.uiState.destination)
        assertEquals(GROUP_ID, recreated.uiState.selectedGroupId)
        assertEquals(GROUP_ID, recreated.uiState.appPickerGroupId)
    }

    @Test
    fun `onboarding is shown once and completion is persisted`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val onboarding = FakeOnboardingStore()
        val operations = FakeOperations()
        val first = MainViewModel(
            Application(),
            SavedStateHandle(),
            operations,
            dispatcher,
            onboarding,
        )
        advanceUntilIdle()

        assertTrue(first.uiState.showOnboarding)
        first.completeOnboarding()

        assertFalse(first.uiState.showOnboarding)
        assertTrue(onboarding.completed)

        val recreated = MainViewModel(
            Application(),
            SavedStateHandle(),
            operations,
            dispatcher,
            onboarding,
        )
        advanceUntilIdle()
        assertFalse(recreated.uiState.showOnboarding)
    }

    @Test
    fun `diagnostics report is exposed once and consumed by matching id`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val viewModel = viewModel(
            SavedStateHandle(),
            FakeOperations(diagnostics = "schema=1\nspaceCount=0\n"),
            dispatcher,
        )
        advanceUntilIdle()

        viewModel.exportDiagnostics()
        advanceUntilIdle()

        assertEquals("schema=1\nspaceCount=0\n", viewModel.uiState.diagnosticsReport)
        val reportId = viewModel.uiState.diagnosticsReportId
        viewModel.consumeDiagnostics(reportId + 1)
        assertTrue(viewModel.uiState.diagnosticsReport != null)
        viewModel.consumeDiagnostics(reportId)
        assertEquals(null, viewModel.uiState.diagnosticsReport)
    }

    @Test
    fun `deep link chooser keeps exact space and package candidates`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val packageName = "com.example.mail"
        val operations = FakeOperations(
            groups = listOf(
                group().copy(
                    apps = listOf(GroupApp(packageName, addedAtEpochMillis = 2)),
                ),
            ),
            deepLinkCandidates = listOf(GROUP_ID to packageName),
        )
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()

        viewModel.openDeepLink("https://example.com/inbox")
        advanceUntilIdle()

        assertEquals("https://example.com/inbox", viewModel.uiState.pendingDeepLink)
        assertEquals(1, viewModel.uiState.deepLinkCandidates.size)
        assertEquals(GROUP_ID, viewModel.uiState.deepLinkCandidates.single().groupId)
        assertEquals(packageName, viewModel.uiState.deepLinkCandidates.single().app.packageName)
    }

    @Test
    fun `cold start deep link waits for the first space refresh`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val packageName = "com.example.mail"
        val reconcileGate = CompletableDeferred<Unit>()
        val operations = FakeOperations(
            groups = listOf(
                group().copy(apps = listOf(GroupApp(packageName, addedAtEpochMillis = 2))),
            ),
            reconcileGate = reconcileGate,
            deepLinkCandidates = listOf(GROUP_ID to packageName),
        )
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        runCurrent()

        viewModel.openDeepLink("https://example.com/cold-start")
        runCurrent()
        assertTrue(viewModel.uiState.isResolvingDeepLink)
        assertEquals(null, viewModel.uiState.pendingDeepLink)

        reconcileGate.complete(Unit)
        advanceUntilIdle()

        assertEquals("https://example.com/cold-start", viewModel.uiState.pendingDeepLink)
        assertEquals(GROUP_ID, viewModel.uiState.deepLinkCandidates.single().groupId)
        assertFalse(viewModel.uiState.isResolvingDeepLink)
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

    @Test
    fun `GMS consent and enable action run through operations then refresh`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val operations = FakeOperations(groups = listOf(group()))
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()

        viewModel.enableGms(GROUP_ID, grantConsent = true)
        advanceUntilIdle()

        assertEquals(listOf(GROUP_ID), operations.gmsConsentGroups)
        assertEquals(listOf(GROUP_ID), operations.gmsEnableGroups)
        assertEquals(null, viewModel.uiState.gmsBusyGroupId)
        assertTrue(viewModel.uiState.message.orEmpty().contains("沒有可用且受信任"))
        assertEquals(2, operations.refreshCalls)
    }

    @Test
    fun `corrupt GMS startup state becomes warning without losing the space snapshot`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val operations = FakeOperations(
            groups = listOf(group()),
            gmsReconcileError = IllegalStateException("corrupt GMS profile"),
            refreshWarnings = listOf("Group $GROUP_ID/data/gms/profile.properties 無法讀取"),
        )

        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.isRefreshing)
        assertEquals(listOf(GROUP_ID), viewModel.uiState.groups.map(GroupItem::groupId))
        assertEquals(1, viewModel.uiState.dataWarnings.size)
        assertTrue(viewModel.uiState.message.orEmpty().contains("資料完整性問題"))
    }

    @Test
    fun `refresh exposes aggregated clone permissions in settings state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val permission = ClonePermissionSummary(
            permission = "android.permission.CAMERA",
            label = "相機",
            category = ClonePermissionCategory.RUNTIME,
            virtualScope = ClonePermissionVirtualScope.CAMERA_MIC_PER_SPACE,
            granted = false,
            action = ClonePermissionAction.REQUEST_RUNTIME,
            affectedClones = listOf(
                ClonePermissionTarget(GROUP_ID, "工作", "com.example.camera", "相機 App"),
            ),
        )
        val viewModel = viewModel(
            SavedStateHandle(),
            FakeOperations(clonePermissions = listOf(permission)),
            dispatcher,
        )

        advanceUntilIdle()

        assertEquals(listOf(permission), viewModel.uiState.clonePermissions)
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
        private val diagnostics: String? = null,
        private val deepLinkCandidates: List<Pair<String, String>>? = null,
        private val gmsReconcileError: Throwable? = null,
        private val refreshWarnings: List<String> = emptyList(),
        private val clonePermissions: List<ClonePermissionSummary> = emptyList(),
    ) : MainOperations {
        var reconcileStarted = false
        var refreshCalls = 0
        var refreshRanOnIoDispatcher = false
        var refreshRanOnMainDispatcher = false
        val gmsConsentGroups = mutableListOf<String>()
        val gmsEnableGroups = mutableListOf<String>()

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
                dataWarnings = loadIssues.map { "corrupt metadata" } + refreshWarnings,
                clonePermissions = clonePermissions,
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
        override suspend fun createShortcut(item: GroupAppItem): ShortcutCreationResult =
            error("unused")
        override suspend fun exportDiagnostics(): String = diagnostics ?: error("unused")
        override suspend fun repairClone(item: GroupAppItem): RepairExecutionResult = error("unused")
        override suspend fun setClonePermission(
            item: GroupAppItem,
            permission: String,
            granted: Boolean,
        ): Boolean = error("unused")
        override suspend fun resolveDeepLink(uri: String): List<Pair<String, String>> =
            deepLinkCandidates ?: error("unused")
        override suspend fun launchDeepLink(
            item: GroupAppItem,
            uri: String,
        ): RuntimeLaunchResult = error("unused")

        override suspend fun reconcileGroups(): GroupReconciliationResult {
            reconcileStarted = true
            reconcileGate?.await()
            return GroupReconciliationResult(loadIssues)
        }

        override suspend fun reconcileAppRemovals() = Unit
        override suspend fun reconcileApplicationOperations() = Unit
        override suspend fun reconcileGms(): GmsStartupResult {
            gmsReconcileError?.let { throw it }
            return GmsStartupResult(
                reconciliation = GmsReconciliationResult(
                    completed = emptyList(),
                    retainedForRetry = emptyList(),
                    terminalFailures = emptyList(),
                    releaseMismatches = emptyList(),
                ),
                profiles = emptyList(),
            )
        }
        override suspend fun grantGmsConsent(groupId: String) {
            gmsConsentGroups += groupId
        }
        override suspend fun enableGms(groupId: String): GmsLifecycleResult {
            gmsEnableGroups += groupId
            return GmsLifecycleResult.TrustedReleaseUnavailable
        }
        override suspend fun disableGms(groupId: String): GmsLifecycleResult =
            GmsLifecycleResult.AlreadySatisfied(
                org.apptwin.gms.model.GmsProfile.disabled(
                    org.apptwin.gms.model.GmsGroupId(groupId),
                ),
            )
        override suspend fun resetGms(
            groupId: String,
            reenable: Boolean,
        ): GmsLifecycleResult = GmsLifecycleResult.TrustedReleaseUnavailable
    }

    private class FakeOnboardingStore : OnboardingStore {
        var completed = false

        override fun isCompleted(): Boolean = completed

        override fun markCompleted() {
            completed = true
        }
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
