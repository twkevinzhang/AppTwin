package org.apptwin

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.apptwin.groups.EnvironmentBinding
import org.apptwin.groups.Group
import org.apptwin.groups.GroupAppRemovalResult
import org.apptwin.usecases.ClearCloneStorageResult
import org.apptwin.usecases.ClearSpaceStorageResult
import org.apptwin.groups.GroupHealth
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupAppState
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
import org.apptwin.gms.GmsGroupProductState
import org.apptwin.gms.GmsStartupResult
import org.apptwin.gms.model.GmsDesiredState
import org.apptwin.gms.model.GmsGroupId
import org.apptwin.gms.model.GmsNetworkConsent
import org.apptwin.gms.model.GmsObservedState
import org.apptwin.gms.model.GmsProfile
import org.apptwin.gms.ports.CloudMessagingHealth
import org.apptwin.gms.ports.CloudMessagingState
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
        assertEquals(GROUP_ID, first.uiState.appPickerGroupId)
        assertEquals(GROUP_ID, savedState.get<String>("main.appPickerGroupId"))

        val recreated = viewModel(savedState, operations, ioDispatcher)
        advanceUntilIdle()

        assertEquals(MainDestination.HOME, recreated.uiState.destination)
        assertEquals(GROUP_ID, recreated.uiState.appPickerGroupId)
    }

    @Test
    fun `onboarding is shown once and completion is persisted`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val onboarding = FakeOnboardingStore()
        val operations = FakeOperations()
        val first = MainViewModel(
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
            SavedStateHandle(),
            operations,
            dispatcher,
            onboarding,
        )
        advanceUntilIdle()
        assertFalse(recreated.uiState.showOnboarding)
    }

    @Test
    fun `archive compression is loaded and persisted through ViewModel`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val settings = FakeArchiveExportSettingsStore(
            org.apptwin.archive.SpaceArchiveCompression.HIGH,
        )
        val operations = FakeOperations()
        val first = MainViewModel(
            savedStateHandle = SavedStateHandle(),
            operations = operations,
            ioDispatcher = dispatcher,
            archiveExportSettingsStore = settings,
        )
        advanceUntilIdle()

        assertEquals(
            org.apptwin.archive.SpaceArchiveCompression.HIGH,
            first.uiState.archiveCompression,
        )
        first.setArchiveCompression(org.apptwin.archive.SpaceArchiveCompression.LOW)

        val recreated = MainViewModel(
            savedStateHandle = SavedStateHandle(),
            operations = operations,
            ioDispatcher = dispatcher,
            archiveExportSettingsStore = settings,
        )
        advanceUntilIdle()

        assertEquals(org.apptwin.archive.SpaceArchiveCompression.LOW, settings.value)
        assertEquals(
            org.apptwin.archive.SpaceArchiveCompression.LOW,
            recreated.uiState.archiveCompression,
        )
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
    fun `space cards are published before slow enrichment completes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val enrichmentGate = CompletableDeferred<Unit>()
        val packageName = "com.example.browser"
        val operations = FakeOperations(
            groups = listOf(group().copy(apps = listOf(GroupApp(packageName, addedAtEpochMillis = 2)))),
            refreshGate = enrichmentGate,
        )
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)

        runCurrent()

        assertEquals(listOf(GROUP_ID), viewModel.uiState.groups.map(GroupItem::groupId))
        assertTrue(viewModel.uiState.isRefreshing)
        assertEquals("正在同步", viewModel.uiState.groups.single().apps.singleOrNull()?.launchStatus)

        enrichmentGate.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.isRefreshing)
        assertEquals(listOf(GROUP_ID), viewModel.uiState.groups.map(GroupItem::groupId))
    }

    @Test
    fun `enrichment failure keeps already published space cards`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val viewModel = viewModel(
            SavedStateHandle(),
            FakeOperations(
                groups = listOf(group()),
                refreshError = IllegalStateException("virtual runtime unavailable"),
            ),
            dispatcher,
        )

        advanceUntilIdle()

        assertFalse(viewModel.uiState.isRefreshing)
        assertEquals(listOf(GROUP_ID), viewModel.uiState.groups.map(GroupItem::groupId))
        assertTrue(viewModel.uiState.message.orEmpty().contains("詳細資料失敗"))
    }

    @Test
    fun `failed initial group load keeps launch blocked until recovery can be retried`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val operations = FakeOperations(
            loadGroupError = IllegalStateException("group index unavailable"),
        )
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        val item = GroupAppItem(
            groupId = GROUP_ID,
            groupName = "工作",
            groupHealth = GroupHealth.HEALTHY,
            app = GroupApp(APP_PACKAGE, 2L, GroupAppState.ENABLED),
            appLabel = "測試 App",
            versionName = "1.0",
            sourceInstalled = true,
            launchStatus = "可使用",
        )

        viewModel.launchGroupApp(item.groupId, item.app.packageName)
        advanceUntilIdle()

        assertTrue(operations.launchedApps.isEmpty())
        assertTrue(viewModel.uiState.message.orEmpty().contains("讀取空間失敗"))
    }

    @Test
    fun `refresh during first recovery load does not cancel startup recovery`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val firstGroupLoadGate = CompletableDeferred<Unit>()
        val operations = FakeOperations(
            groups = listOf(
                group().copy(apps = listOf(GroupApp(APP_PACKAGE, 2L, GroupAppState.ENABLED))),
            ),
            installedEntries = listOf(appItem().entry),
            firstGroupLoadGate = firstGroupLoadGate,
        )
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        runCurrent()

        viewModel.refresh()
        runCurrent()

        assertEquals(1, operations.loadGroupCalls)
        firstGroupLoadGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, operations.loadGroupCalls)
        assertEquals(1, operations.gmsReconcileCalls)
        val item = viewModel.uiState.groups.single().apps.single()
        viewModel.launchGroupApp(item)
        advanceUntilIdle()

        assertEquals(listOf(APP_PACKAGE), operations.launchedApps.map { it.app.packageName })
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
    fun `space deletion reports shortcut cleanup warning without hiding completed deletion`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val operations = FakeOperations(
                groups = listOf(group()),
                deleteShortcutWarning = "桌面捷徑未能完全停用",
            )
            val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
            advanceUntilIdle()

            viewModel.deleteGroup(GROUP_ID)
            advanceUntilIdle()

            assertEquals(listOf(GROUP_ID), operations.deletedGroupIds)
            assertTrue(viewModel.uiState.message.orEmpty().contains("已刪除「工作」"))
            assertTrue(viewModel.uiState.message.orEmpty().contains("桌面捷徑未能完全停用"))
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
    fun `GMS reconciliation publishes current cloud messaging health`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val productState = GmsGroupProductState(
            profile = GmsProfile(
                groupId = GmsGroupId(GROUP_ID),
                desiredState = GmsDesiredState.ENABLED,
                observedState = GmsObservedState.READY_PARTIAL,
                networkConsent = GmsNetworkConsent.GRANTED,
                observedReleaseId = "microg-v0.3.15.250932",
            ),
            capabilities = emptyList(),
            cloudMessaging = CloudMessagingHealth(CloudMessagingState.CONNECTED),
        )
        val viewModel = viewModel(
            SavedStateHandle(),
            FakeOperations(
                groups = listOf(group()),
                gmsStartupProductStates = mapOf(GROUP_ID to productState),
            ),
            dispatcher,
        )

        advanceUntilIdle()

        assertEquals(
            CloudMessagingState.CONNECTED,
            viewModel.uiState.groups.single().gmsCompatibility?.cloudMessaging?.state,
        )
    }

    @Test
    fun `refresh exposes aggregated clone permissions in settings state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val permission = ClonePermissionSummary(
            permission = "android.permission.CAMERA",
            label = "相機",
            category = ClonePermissionCategory.RUNTIME,
            virtualScope = ClonePermissionVirtualScope.SPACE_SCOPED,
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

    @Test
    fun `clear space storage exposes busy state and completed clone count`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val clearGate = CompletableDeferred<Unit>()
        val operations = FakeOperations(
            groups = listOf(group().copy(apps = listOf(GroupApp("com.example.chat", 2L)))),
            clearGroupStorageGate = clearGate,
            clearGroupStorageResult = ClearSpaceStorageResult.Cleared(1),
        )
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        viewModel.clearAllGroupAppData(GROUP_ID)
        runCurrent()

        assertEquals(GROUP_ID, viewModel.uiState.clearingStorageGroupId)
        assertEquals(listOf(GROUP_ID), operations.clearedGroupIds)
        viewModel.clearAllGroupAppData(GROUP_ID)
        assertEquals(listOf(GROUP_ID), operations.clearedGroupIds)

        clearGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.clearingStorageGroupId)
        assertEquals("已清除「工作」中 1 個 App 的分身資料", viewModel.uiState.message)
    }

    @Test
    fun `clear space storage reports a non-atomic partial failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val packageName = "com.example.chat"
        val operations = FakeOperations(
            groups = listOf(group().copy(apps = listOf(GroupApp(packageName, 2L)))),
            clearGroupStorageResult = ClearSpaceStorageResult.PartiallyCleared(
                clearedCloneCount = 1,
                failedPackageName = packageName,
                error = IllegalStateException("storage unavailable"),
            ),
        )
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()

        viewModel.clearAllGroupAppData(GROUP_ID)
        advanceUntilIdle()

        assertEquals(
            "已清除「工作」中 1 個 App 的分身資料；清除 $packageName 時失敗：storage unavailable",
            viewModel.uiState.message,
        )
    }

    @Test
    fun `selecting app adds then launches the same clone before reporting completion`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val launchGate = CompletableDeferred<Unit>()
        val operations = FakeOperations(
            groups = listOf(group()),
            launchGate = launchGate,
            launchResult = RuntimeLaunchResult.Started(
                packageName = APP_PACKAGE,
                processPrefix = "org.apptwin:p7",
                dataDirectory = "/data/user/7/$APP_PACKAGE",
            ),
        )
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        viewModel.openAppPicker(GROUP_ID)
        advanceUntilIdle()

        viewModel.selectApp(appItem())
        runCurrent()

        assertEquals(listOf(GROUP_ID to APP_PACKAGE), operations.addedApps)
        assertEquals(1, operations.launchedApps.size)
        assertEquals(GROUP_ID, operations.launchedApps.single().groupId)
        assertEquals(APP_PACKAGE, operations.launchedApps.single().app.packageName)
        assertEquals(APP_PACKAGE, viewModel.uiState.busyPackageName)
        assertEquals("$GROUP_ID:$APP_PACKAGE", viewModel.uiState.launchingAppKey)
        assertFalse(viewModel.uiState.message.orEmpty().contains("並啟動"))

        launchGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.busyPackageName)
        assertEquals(null, viewModel.uiState.launchingAppKey)
        assertEquals("已將 測試 App 加入「工作」並啟動", viewModel.uiState.message)
    }

    @Test
    fun `selecting app does not launch when adding the clone fails`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val operations = FakeOperations(
            groups = listOf(group()),
            addAppError = IllegalStateException("無法加入測試 App"),
        )
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        viewModel.openAppPicker(GROUP_ID)
        advanceUntilIdle()

        viewModel.selectApp(appItem())
        advanceUntilIdle()

        assertEquals(listOf(GROUP_ID to APP_PACKAGE), operations.addedApps)
        assertTrue(operations.launchedApps.isEmpty())
        assertEquals("無法加入測試 App", viewModel.uiState.message)
        assertEquals(null, viewModel.uiState.busyPackageName)
        assertEquals(GROUP_ID, viewModel.uiState.appPickerGroupId)
    }

    @Test
    fun `failed automatic launch keeps the added clone and offers a retry`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val operations = FakeOperations(
            groups = listOf(group()),
            launchResult = RuntimeLaunchResult.Failed("guest activity 未進入前景"),
        )
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        viewModel.openAppPicker(GROUP_ID)
        advanceUntilIdle()

        viewModel.selectApp(appItem())
        advanceUntilIdle()

        assertEquals(APP_PACKAGE, viewModel.uiState.groups.single().apps.single().app.packageName)
        assertTrue(viewModel.uiState.message.orEmpty().contains("自動啟動失敗"))
        assertTrue(viewModel.uiState.message.orEmpty().contains("分身已保留，可稍後重試"))
        assertEquals(1, operations.launchedApps.size)
    }

    @Test
    fun `repeated selection while add and launch is active runs only once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val addGate = CompletableDeferred<Unit>()
        val operations = FakeOperations(
            groups = listOf(group()),
            addAppGate = addGate,
            launchResult = RuntimeLaunchResult.Started(
                packageName = APP_PACKAGE,
                processPrefix = "org.apptwin:p7",
                dataDirectory = "/data/user/7/$APP_PACKAGE",
            ),
        )
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        viewModel.openAppPicker(GROUP_ID)
        advanceUntilIdle()

        viewModel.selectApp(appItem())
        viewModel.selectApp(appItem())
        runCurrent()
        assertEquals(1, operations.addedApps.size)

        addGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, operations.addedApps.size)
        assertEquals(1, operations.launchedApps.size)
    }

    @Test
    fun `pending launch requested during add does not duplicate automatic launch`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val addGate = CompletableDeferred<Unit>()
        val operations = FakeOperations(
            groups = listOf(group()),
            addAppGate = addGate,
            launchResult = RuntimeLaunchResult.Started(
                packageName = APP_PACKAGE,
                processPrefix = "org.apptwin:p7",
                dataDirectory = "/data/user/7/$APP_PACKAGE",
            ),
        )
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        viewModel.openAppPicker(GROUP_ID)
        advanceUntilIdle()

        viewModel.selectApp(appItem())
        runCurrent()
        viewModel.launchGroupApp(GROUP_ID, APP_PACKAGE)
        addGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, operations.addedApps.size)
        assertEquals(1, operations.launchedApps.size)
    }

    @Test
    fun `warm shortcut to removed exact clone refreshes then reports it missing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val operations = FakeOperations(groups = listOf(group()))
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()

        viewModel.launchGroupApp(GROUP_ID, APP_PACKAGE)
        advanceUntilIdle()

        assertTrue(operations.launchedApps.isEmpty())
        assertEquals(
            "此捷徑對應的分身已不存在；請從 AppTwin 重新建立捷徑",
            viewModel.uiState.message,
        )
    }

    @Test
    fun `cold shortcut to removed exact clone reports it missing after initial refresh`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val refreshGate = CompletableDeferred<Unit>()
        val operations = FakeOperations(
            groups = listOf(group()),
            refreshGate = refreshGate,
        )
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        runCurrent()

        viewModel.launchGroupApp(GROUP_ID, APP_PACKAGE)
        refreshGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(operations.launchedApps.isEmpty())
        assertEquals(
            "此捷徑對應的分身已不存在；請從 AppTwin 重新建立捷徑",
            viewModel.uiState.message,
        )
    }

    @Test
    fun `cold shortcut launches on priority lane before installed app enrichment completes`() = runTest {
        val mainDispatcher = StandardTestDispatcher(testScheduler, "main")
        val ioDispatcher = StandardTestDispatcher(testScheduler, "io")
        val launchDispatcher = StandardTestDispatcher(testScheduler, "launch")
        Dispatchers.setMain(mainDispatcher)
        val secondGroupLoadGate = CompletableDeferred<Unit>()
        val refreshGate = CompletableDeferred<Unit>()
        val operations = FakeOperations(
            groups = listOf(
                group().copy(apps = listOf(GroupApp(APP_PACKAGE, 2L, GroupAppState.ENABLED))),
            ),
            installedEntries = listOf(appItem().entry),
            secondGroupLoadGate = secondGroupLoadGate,
            refreshGate = refreshGate,
            expectedLaunchDispatcher = launchDispatcher,
            launchResult = RuntimeLaunchResult.Started(
                packageName = APP_PACKAGE,
                processPrefix = "org.apptwin:p7",
                dataDirectory = "/data/user/7/$APP_PACKAGE",
            ),
        )
        val viewModel = viewModel(
            SavedStateHandle(),
            operations,
            ioDispatcher,
            launchDispatcher,
        )
        runCurrent()

        viewModel.launchGroupApp(GROUP_ID, APP_PACKAGE)
        runCurrent()

        assertTrue(operations.launchedApps.isEmpty())
        assertFalse(viewModel.uiState.groups.single().apps.single().sourceInstalled)

        secondGroupLoadGate.complete(Unit)
        runCurrent()

        assertEquals(1, operations.launchedApps.size)
        assertTrue(operations.launchRanOnExpectedDispatcher)
        assertEquals(1, operations.gmsReconcileCalls)

        refreshGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, operations.launchedApps.size)
        assertEquals(GROUP_ID, operations.launchedApps.single().groupId)
        assertEquals(APP_PACKAGE, operations.launchedApps.single().app.packageName)
        assertEquals(1, operations.gmsReconcileCalls)
    }

    @Test
    fun `home launch is available during source enrichment without unlocking source actions`() =
        runTest {
            val mainDispatcher = StandardTestDispatcher(testScheduler, "main")
            val ioDispatcher = StandardTestDispatcher(testScheduler, "io")
            val launchDispatcher = StandardTestDispatcher(testScheduler, "launch")
            Dispatchers.setMain(mainDispatcher)
            val refreshGate = CompletableDeferred<Unit>()
            val operations = FakeOperations(
                groups = listOf(
                    group().copy(
                        apps = listOf(GroupApp(APP_PACKAGE, 2L, GroupAppState.ENABLED)),
                    ),
                ),
                installedEntries = listOf(appItem().entry),
                refreshGate = refreshGate,
                expectedLaunchDispatcher = launchDispatcher,
                launchResult = RuntimeLaunchResult.Started(
                    packageName = APP_PACKAGE,
                    processPrefix = "org.apptwin:p7",
                    dataDirectory = "/data/user/7/$APP_PACKAGE",
                ),
            )
            val viewModel = viewModel(
                SavedStateHandle(),
                operations,
                ioDispatcher,
                launchDispatcher,
            )
            runCurrent()
            val item = viewModel.uiState.groups.single().apps.single()

            assertFalse(item.sourceInstalled)
            assertTrue(item.canAttemptLaunch)
            viewModel.launchGroupApp(item)
            runCurrent()

            assertEquals(1, operations.launchedApps.size)
            assertTrue(operations.launchRanOnExpectedDispatcher)
            assertFalse(viewModel.uiState.groups.single().apps.single().sourceInstalled)

            refreshGate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun `a second launch cannot overlap an active launch`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val secondPackage = "com.example.second"
        val launchGate = CompletableDeferred<Unit>()
        val operations = FakeOperations(
            groups = listOf(
                group().copy(
                    apps = listOf(
                        GroupApp(APP_PACKAGE, 2L, GroupAppState.ENABLED),
                        GroupApp(secondPackage, 3L, GroupAppState.ENABLED),
                    ),
                ),
            ),
            installedEntries = listOf(
                appItem().entry,
                InstalledAppEntry("第二個 App", secondPackage, "1.0", 1),
            ),
            launchGate = launchGate,
            launchResult = RuntimeLaunchResult.Started(
                packageName = APP_PACKAGE,
                processPrefix = "org.apptwin:p7",
                dataDirectory = "/data/user/7/$APP_PACKAGE",
            ),
        )
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        val first = viewModel.uiState.groups.single().apps.first()
        val second = viewModel.uiState.groups.single().apps.last()

        viewModel.launchGroupApp(first)
        runCurrent()
        viewModel.launchGroupApp(second)
        runCurrent()

        assertEquals(listOf(APP_PACKAGE), operations.launchedApps.map { it.app.packageName })
        assertEquals(1, operations.maxConcurrentLaunches)

        launchGate.complete(Unit)
        advanceUntilIdle()
        viewModel.launchGroupApp(second)
        advanceUntilIdle()

        assertEquals(listOf(APP_PACKAGE, secondPackage), operations.launchedApps.map {
            it.app.packageName
        })
        assertEquals(1, operations.maxConcurrentLaunches)
    }

    @Test
    fun `stale enrichment cannot overwrite state after priority launch begins`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val refreshGate = CompletableDeferred<Unit>()
        val launchGate = CompletableDeferred<Unit>()
        val operations = FakeOperations(
            groups = listOf(
                group().copy(apps = listOf(GroupApp(APP_PACKAGE, 2L, GroupAppState.ENABLED))),
            ),
            installedEntries = listOf(appItem().entry),
            refreshGate = refreshGate,
            launchGate = launchGate,
            launchResult = RuntimeLaunchResult.Started(
                packageName = APP_PACKAGE,
                processPrefix = "org.apptwin:p7",
                dataDirectory = "/data/user/7/$APP_PACKAGE",
            ),
        )
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        runCurrent()
        val item = viewModel.uiState.groups.single().apps.single()

        viewModel.launchGroupApp(item.groupId, item.app.packageName)
        runCurrent()
        refreshGate.complete(Unit)
        runCurrent()

        assertEquals(item.launchKey, viewModel.uiState.launchingAppKey)
        assertEquals("正在同步", viewModel.uiState.groups.single().apps.single().launchStatus)
        assertEquals(APP_PACKAGE, viewModel.uiState.groups.single().apps.single().appLabel)

        launchGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.launchingAppKey)
        assertEquals("測試 App", viewModel.uiState.groups.single().apps.single().appLabel)
        assertEquals(1, operations.gmsReconcileCalls)
    }

    @Test
    fun `deep link launch waits for active normal launch and preserves chooser`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val launchGate = CompletableDeferred<Unit>()
        val started = RuntimeLaunchResult.Started(
            packageName = APP_PACKAGE,
            processPrefix = "org.apptwin:p7",
            dataDirectory = "/data/user/7/$APP_PACKAGE",
        )
        val operations = FakeOperations(
            groups = listOf(
                group().copy(apps = listOf(GroupApp(APP_PACKAGE, 2L, GroupAppState.ENABLED))),
            ),
            installedEntries = listOf(appItem().entry),
            deepLinkCandidates = listOf(GROUP_ID to APP_PACKAGE),
            launchGate = launchGate,
            launchResult = started,
            deepLinkLaunchResult = started,
        )
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        viewModel.openDeepLink("https://example.com/inbox")
        advanceUntilIdle()
        val item = viewModel.uiState.deepLinkCandidates.single()

        viewModel.launchGroupApp(item)
        runCurrent()
        viewModel.launchDeepLink(item)
        runCurrent()

        assertTrue(operations.launchedDeepLinks.isEmpty())
        assertEquals("https://example.com/inbox", viewModel.uiState.pendingDeepLink)

        launchGate.complete(Unit)
        advanceUntilIdle()
        viewModel.launchDeepLink(viewModel.uiState.deepLinkCandidates.single())
        advanceUntilIdle()

        assertEquals(
            listOf(GROUP_ID to "https://example.com/inbox"),
            operations.launchedDeepLinks,
        )
        assertEquals(null, viewModel.uiState.pendingDeepLink)
    }

    @Test
    fun `shortcut to failed exact clone reports repair instead of launching another space`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val failed = group().copy(
                apps = listOf(GroupApp(APP_PACKAGE, 2L, GroupAppState.FAILED)),
            )
            val other = group().copy(
                id = "22222222-2222-2222-2222-222222222222",
                environmentBinding = EnvironmentBinding(8),
                apps = listOf(GroupApp(APP_PACKAGE, 3L, GroupAppState.ENABLED)),
            )
            val operations = FakeOperations(groups = listOf(failed, other))
            val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
            advanceUntilIdle()

            viewModel.launchGroupApp(GROUP_ID, APP_PACKAGE)
            advanceUntilIdle()

            assertTrue(operations.launchedApps.isEmpty())
            assertTrue(viewModel.uiState.message.orEmpty().contains("分身目前不可使用"))
            assertTrue(viewModel.uiState.message.orEmpty().contains("請在 AppTwin 中修復"))
        }

    @Test
    fun `resume refresh sees account after external authenticator completes without restart`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var accounts = emptyList<SpaceAccountEntry>()
        var externalLoginOpened = false
        val operations = FakeOperations(groups = listOf(group())).apply {
            detailsLoader = { id -> details(id, accounts) }
            // KEY_INTENT returns before the external login has added the account.
            accountAdder = { _, _ -> externalLoginOpened = true }
        }
        val model = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        model.openSpaceDetails(GROUP_ID)
        advanceUntilIdle()
        model.addSpaceAccount("com.google")
        advanceUntilIdle()
        assertTrue(externalLoginOpened)
        assertTrue(model.uiState.spaceDetails!!.accounts.isEmpty())
        accounts = listOf(SpaceAccountEntry("test@example.com", "com.google"))
        model.refresh()
        advanceUntilIdle()
        assertEquals(accounts, model.uiState.spaceDetails!!.accounts)
        assertEquals(MainDestination.SPACE_DETAILS, model.uiState.destination)
        assertTrue(operations.refreshCalls >= 2)
    }

    @Test
    fun `cancelled external login remains empty and accounts stay scoped when switching spaces`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val secondId = "22222222-2222-2222-2222-222222222222"
        val otherAccount = SpaceAccountEntry("other@example.com", "com.google")
        val operations = FakeOperations(groups = listOf(group())).apply {
            detailsLoader = { id -> details(id, if (id == secondId) listOf(otherAccount) else emptyList()) }
        }
        val model = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        model.openSpaceDetails(GROUP_ID)
        model.addSpaceAccount("com.google")
        advanceUntilIdle()
        model.refresh() // External login cancelled without creating an account.
        advanceUntilIdle()
        assertTrue(model.uiState.spaceDetails!!.accounts.isEmpty())
        model.openSpaceDetails(secondId)
        advanceUntilIdle()
        assertEquals(listOf(otherAccount), model.uiState.spaceDetails!!.accounts)
        model.openSpaceDetails(GROUP_ID)
        advanceUntilIdle()
        assertTrue(model.uiState.spaceDetails!!.accounts.isEmpty())
    }

    @Test
    fun `older same space read cannot replace latest details or publish stale failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val pending = CompletableDeferred<SpaceDetails?>()
        val latest = details(GROUP_ID, listOf(SpaceAccountEntry("latest@example.com", "com.google")))
        var calls = 0
        val operations = FakeOperations(groups = listOf(group())).apply {
            detailsLoader = { if (++calls == 1) pending.await() else latest }
        }
        val model = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        model.openSpaceDetails(GROUP_ID)
        runCurrent()
        model.refreshSpaceDetails()
        runCurrent()
        assertEquals(latest, model.uiState.spaceDetails)
        val messageId = model.uiState.messageId
        pending.completeExceptionally(IllegalStateException("obsolete failure"))
        advanceUntilIdle()
        assertEquals(latest, model.uiState.spaceDetails)
        assertEquals(null, model.uiState.spaceDetailsError)
        assertEquals(messageId, model.uiState.messageId)
        assertFalse(model.uiState.isLoadingSpaceDetails)
    }

    @Test
    fun `older successful same space read cannot replace newer accounts`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val pending = CompletableDeferred<SpaceDetails?>()
        val latest = details(GROUP_ID, listOf(SpaceAccountEntry("latest@example.com", "com.google")))
        var calls = 0
        val operations = FakeOperations(groups = listOf(group())).apply {
            detailsLoader = { if (++calls == 1) pending.await() else latest }
        }
        val model = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        model.openSpaceDetails(GROUP_ID)
        runCurrent()
        model.refreshSpaceDetails()
        runCurrent()
        pending.complete(details(GROUP_ID, emptyList()))
        advanceUntilIdle()
        assertEquals(latest, model.uiState.spaceDetails)
    }

    @Test
    fun `cancelled or other space read cannot override current details`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val pending = CompletableDeferred<SpaceDetails?>()
        val secondId = "22222222-2222-2222-2222-222222222222"
        val latest = details(secondId, emptyList())
        val operations = FakeOperations(groups = listOf(group())).apply {
            detailsLoader = { id -> if (id == GROUP_ID) pending.await() else latest }
        }
        val model = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        model.openSpaceDetails(GROUP_ID)
        runCurrent()
        model.openSpaceDetails(secondId)
        runCurrent()
        pending.cancel()
        advanceUntilIdle()
        assertEquals(latest, model.uiState.spaceDetails)
        assertEquals(secondId, model.uiState.selectedSpaceId)
        assertEquals(null, model.uiState.spaceDetailsError)
    }

    private fun details(id: String, accounts: List<SpaceAccountEntry>) = SpaceDetails(
        id, SpaceStorageBreakdown(), emptyList(), accounts, emptyList(), emptyList(), null,
    )

    @Test
    fun `details loading completes as successful empty accounts without an error`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val pending = CompletableDeferred<SpaceDetails?>()
        val operations = FakeOperations(groups = listOf(group())).apply {
            detailsLoader = { pending.await() }
        }
        val model = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        model.openSpaceDetails(GROUP_ID)
        runCurrent()
        assertTrue(model.uiState.isLoadingSpaceDetails)
        assertEquals(null, model.uiState.spaceDetails)
        assertEquals(null, model.uiState.spaceDetailsError)
        val loaded = details(GROUP_ID, emptyList())
        pending.complete(loaded)
        advanceUntilIdle()
        assertFalse(model.uiState.isLoadingSpaceDetails)
        assertEquals(loaded, model.uiState.spaceDetails)
        assertEquals(MainDestination.SPACE_DETAILS, model.uiState.destination)
        assertEquals(null, model.uiState.spaceDetailsError)
    }

    @Test
    fun `details read error can retry without treating a failure as a missing space`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var shouldFail = true
        val loaded = details(GROUP_ID, emptyList())
        val operations = FakeOperations(groups = listOf(group())).apply {
            detailsLoader = {
                if (shouldFail) throw IllegalStateException("fixture read failure")
                loaded
            }
        }
        val model = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        model.openSpaceDetails(GROUP_ID)
        advanceUntilIdle()
        assertFalse(model.uiState.isLoadingSpaceDetails)
        assertEquals(GROUP_ID, model.uiState.selectedSpaceId)
        assertEquals(MainDestination.SPACE_DETAILS, model.uiState.destination)
        assertTrue(model.uiState.spaceDetailsError != null)
        shouldFail = false
        model.refreshSpaceDetails()
        assertEquals(null, model.uiState.spaceDetailsError)
        advanceUntilIdle()
        assertEquals(loaded, model.uiState.spaceDetails)
        assertEquals(null, model.uiState.spaceDetailsError)
        assertFalse(model.uiState.isLoadingSpaceDetails)
    }

    @Test
    fun `background connection failure preserves successful local space details`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val loaded = details(GROUP_ID, emptyList())
        val operations = FakeOperations(groups = listOf(group())).apply {
            detailsLoader = { loaded }
            backgroundLoader = { throw IllegalStateException("fixture offline") }
        }
        val model = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        model.openSpaceDetails(GROUP_ID)
        advanceUntilIdle()
        model.refreshSpaceBackgroundHealth()
        advanceUntilIdle()
        assertEquals(loaded, model.uiState.spaceDetails)
        assertEquals(null, model.uiState.spaceDetailsError)
        assertEquals(GROUP_ID, model.uiState.spaceBackgroundHealth?.groupId)
        assertTrue(model.uiState.spaceBackgroundHealth?.error != null)
        assertFalse(model.uiState.spaceBackgroundHealth!!.healthy)
        assertEquals(listOf(GROUP_ID), model.uiState.groups.map(GroupItem::groupId))
    }

    @Test
    fun `background retry is exact space and duplicate calls are suppressed`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val gate = CompletableDeferred<Unit>()
        val retried = mutableListOf<String>()
        val operations = FakeOperations(groups = listOf(group())).apply {
            detailsLoader = { details(it, emptyList()) }
            backgroundLoader = { SpaceBackgroundHealth(it, SpaceBackgroundStatus.SUCCESS, SpaceBackgroundStatus.FAILED) }
            backgroundRetry = { retried += it; gate.await(); true }
        }
        val model = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        model.openSpaceDetails(GROUP_ID)
        advanceUntilIdle()
        model.refreshSpaceBackgroundHealth()
        advanceUntilIdle()
        model.retrySpaceBackground()
        model.retrySpaceBackground()
        runCurrent()
        assertEquals(listOf(GROUP_ID), retried)
        assertTrue(model.uiState.isSpaceBackgroundRetrying)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.uiState.isSpaceBackgroundRetrying)
    }

    @Test
    fun `late background observation cannot overwrite another space`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val gate = CompletableDeferred<Unit>()
        val second = "22222222-2222-2222-2222-222222222222"
        val operations = FakeOperations(groups = listOf(group())).apply {
            detailsLoader = { details(it, emptyList()) }
            backgroundLoader = { id -> if (id == GROUP_ID) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() }; SpaceBackgroundHealth(id) }
        }
        val model = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        model.openSpaceDetails(GROUP_ID)
        advanceUntilIdle()
        model.refreshSpaceBackgroundHealth()
        runCurrent()
        model.openSpaceDetails(second)
        model.refreshSpaceBackgroundHealth()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(second, model.uiState.spaceBackgroundHealth?.groupId)
    }

    @Test
    fun `home state distinguishes unreadable empty index from a valid empty space list`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val unreadable = viewModel(SavedStateHandle(), FakeOperations(refreshWarnings = listOf("corrupt metadata")), dispatcher)
        val empty = viewModel(SavedStateHandle(), FakeOperations(), dispatcher)
        advanceUntilIdle()
        assertFalse(unreadable.homeState.value.isRefreshing)
        assertTrue(unreadable.homeState.value.groups.isEmpty())
        assertTrue(unreadable.homeState.value.error != null)
        assertEquals(unreadable.homeState.value.error, unreadable.uiState.homeError)
        assertFalse(empty.homeState.value.isRefreshing)
        assertTrue(empty.homeState.value.groups.isEmpty())
        assertEquals(null, empty.homeState.value.error)
    }

    @Test
    fun `home index read error retries into a successful published catalog`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val operations = FakeOperations(groups = listOf(group()), loadGroupError = IllegalStateException("unavailable"))
        val model = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        assertFalse(model.homeState.value.isRefreshing)
        assertTrue(model.homeState.value.error?.contains("unavailable") == true)
        operations.loadGroupError = null
        model.refresh()
        assertTrue(model.uiState.isRefreshing)
        advanceUntilIdle()
        assertEquals(listOf(GROUP_ID), model.homeState.value.groups.map { it.groupId })
        assertEquals(null, model.homeState.value.error)
        assertEquals(model.homeState.value.groups, model.sessionState.value.groups)
        assertEquals(model.sessionState.value.groups, model.pickerState.value.groups)
    }

    @Test
    fun `typed home enrichment error retains fast local cards and settings snapshot does not mask it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val model = viewModel(SavedStateHandle(), FakeOperations(groups = listOf(group()), refreshError = IllegalStateException("enrichment unavailable")), dispatcher)
        advanceUntilIdle()
        assertEquals(listOf(GROUP_ID), model.homeState.value.groups.map { it.groupId })
        assertFalse(model.homeState.value.isRefreshing)
        assertTrue(model.homeState.value.error?.contains("enrichment unavailable") == true)
        assertEquals(model.homeState.value.groups, model.pickerState.value.groups)
    }

    @Test
    fun `typed picker latest request wins while earlier lookup arrives late`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val pending = CompletableDeferred<Group?>()
        val other = group().copy(id = "00000000-0000-0000-0000-000000000002")
        val operations = object : MainOperations by FakeOperations(groups = listOf(group(), other)) {
            override suspend fun findGroup(groupId: String): Group? =
                if (groupId == GROUP_ID) pending.await() else other
        }
        val model = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        model.openAppPicker(GROUP_ID)
        runCurrent()
        model.openAppPicker(other.id)
        runCurrent()
        assertEquals(other.id, model.uiState.appPickerGroupId)
        pending.complete(group())
        advanceUntilIdle()
        assertEquals(other.id, model.pickerState.value.appPickerGroupId)
        assertEquals(other.id, model.uiState.appPickerGroupId)
    }

    @Test
    fun `closed deep link resolution cannot republish a chooser after late IO completion`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val pending = CompletableDeferred<List<Pair<String, String>>>()
        val cloned = group().copy(apps = listOf(GroupApp(APP_PACKAGE, addedAtEpochMillis = 2)))
        val operations = object : MainOperations by FakeOperations(groups = listOf(cloned)) {
            override suspend fun resolveDeepLink(uri: String) = pending.await()
        }
        val model = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        model.openDeepLink("fixture://accepted")
        runCurrent()
        model.closeDeepLink()
        pending.complete(listOf(GROUP_ID to APP_PACKAGE))
        advanceUntilIdle()
        assertFalse(model.navigationState.value.isResolvingDeepLink)
        assertEquals(null, model.navigationState.value.pendingDeepLink)
        assertTrue(model.navigationState.value.deepLinkCandidates.isEmpty())
    }

    @Test
    fun `cancelled home index read cannot publish a late catalog or enter durable recovery`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val gate = CompletableDeferred<Unit>()
        val operations = FakeOperations(groups = listOf(group()), firstGroupLoadGate = gate)
        val model = viewModel(SavedStateHandle(), operations, dispatcher)
        val store = ViewModelStore().apply { put("model", model) }
        runCurrent()
        assertEquals(1, operations.loadGroupCalls)
        store.clear()
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(operations.reconcileStarted)
        assertEquals(0, operations.refreshCalls)
        assertTrue(model.uiState.groups.isEmpty())
        assertEquals(null, model.uiState.message)
        assertEquals(null, model.uiState.homeError)
    }

    @Test
    fun `cancelled picker lookup cannot select a space or publish a failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val gate = CompletableDeferred<Unit>()
        val operations = FakeOperations(groups = listOf(group()), findGroupGate = gate)
        val savedState = SavedStateHandle()
        val model = viewModel(savedState, operations, dispatcher)
        val store = ViewModelStore().apply { put("model", model) }
        advanceUntilIdle()
        model.openAppPicker(GROUP_ID)
        runCurrent()
        store.clear()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(null, model.uiState.appPickerGroupId)
        assertEquals(null, savedState.get<String>("main.appPickerGroupId"))
        assertEquals(null, model.uiState.message)
    }

    @Test
    fun `cancelled launch releases the shared lease without failure publication or refreshing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val gate = CompletableDeferred<Unit>()
        val operations = FakeOperations(launchGate = gate)
        val messages = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = MainSessionCoordinator(scope, dispatcher, dispatcher, operations, operations, messages::add, messages::add)
        session.launchBlockedByRecovery = false
        val item = GroupAppItem(GROUP_ID, "工作", GroupHealth.HEALTHY, GroupApp(APP_PACKAGE, 2), "測試", "1", true, "可使用")
        var refreshes = 0
        session.launchGroupApp(item) { ++refreshes }
        runCurrent()
        assertEquals(item.launchKey, session.state.launchingAppKey)
        assertFalse(session.appLaunchMutex.tryLock())
        scope.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(null, session.state.launchingAppKey)
        assertTrue(session.appLaunchMutex.tryLock())
        session.appLaunchMutex.unlock()
        assertTrue(messages.isEmpty())
        assertEquals(0, refreshes)
    }

    @Test
    fun `cancelled durable recovery stops before remaining reconciliations and enrichment`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val gate = CompletableDeferred<Unit>()
        val fake = FakeOperations(groups = listOf(group()), reconcileGate = gate)
        val followup = mutableListOf<String>()
        val operations = object : MainOperations by fake {
            override suspend fun reconcileAppRemovals() { followup += "removals" }
            override suspend fun reconcileApplicationOperations() { followup += "applications" }
            override suspend fun reconcileGms(): GmsStartupResult {
                followup += "gms"
                return fake.reconcileGms()
            }
        }
        val messages = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = MainSessionCoordinator(scope, dispatcher, dispatcher, operations, operations, messages::add, messages::add)
        session.reconcile(
            onStart = {},
            loadAndPublishGroups = { operations.loadGroupSnapshot() },
            applyFullRefresh = { _, _ -> followup += "enrichment" },
            validatePickerSelection = {},
            refresh = { followup += "refresh" },
        )
        runCurrent()
        assertTrue(fake.reconcileStarted)
        scope.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(session.recoveryInProgress)
        assertTrue(session.launchBlockedByRecovery)
        assertEquals(1, fake.loadGroupCalls)
        assertTrue(followup.isEmpty())
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `permission result updates captured space once after navigation changes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val original = group().copy(apps = listOf(GroupApp(APP_PACKAGE, 2L, GroupAppState.ENABLED)))
        val fake = FakeOperations(groups = listOf(original), installedEntries = listOf(appItem().entry))
        val writes = mutableListOf<String>()
        val operations = object : MainOperations by fake {
            override suspend fun setClonePermission(item: GroupAppItem, permission: String, granted: Boolean, expectedIdentity: org.apptwin.permissions.ClonePermissionRequestIdentity): Boolean {
                writes += item.groupId
                return true
            }
        }
        val model = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        var prepared = false
        model.prepareClonePermissionRequest(model.sessionState.value.groups.single().apps.single(), "android.permission.CAMERA") { prepared = true }
        advanceUntilIdle()
        assertTrue(prepared)
        model.navigate(MainDestination.SETTINGS)
        model.completeClonePermissionRequest(null, true)
        model.completeClonePermissionRequest(null, true)
        advanceUntilIdle()
        assertEquals(listOf(GROUP_ID), writes)
    }

    @Test
    fun `saved permission request restores original binding and rejects replaced space`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        var original = group().copy(apps = listOf(GroupApp(APP_PACKAGE, 2L, GroupAppState.ENABLED)))
        val fake = FakeOperations(groups = listOf(original), installedEntries = listOf(appItem().entry))
        val writes = mutableListOf<String>()
        val operations = object : MainOperations by fake {
            override suspend fun findGroup(groupId: String): Group = original
            override suspend fun setClonePermission(item: GroupAppItem, permission: String, granted: Boolean, expectedIdentity: org.apptwin.permissions.ClonePermissionRequestIdentity): Boolean {
                writes += item.groupId
                return true
            }
        }
        val handle = SavedStateHandle()
        val first = viewModel(handle, operations, dispatcher)
        advanceUntilIdle()
        first.prepareClonePermissionRequest(first.sessionState.value.groups.single().apps.single(), "android.permission.CAMERA") {}
        advanceUntilIdle()
        val restored = viewModel(handle, operations, dispatcher)
        advanceUntilIdle()
        assertEquals("android.permission.CAMERA", restored.pendingClonePermissionName())
        original = original.copy(environmentBinding = EnvironmentBinding(99))
        restored.completeClonePermissionRequest(null, true)
        advanceUntilIdle()
        assertTrue(writes.isEmpty())
        assertEquals(null, restored.pendingClonePermissionName())
    }

    @Test
    fun `cancel invalidates pending lookup and late prepare cannot reopen dialog`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val original = group().copy(apps = listOf(GroupApp(APP_PACKAGE, 2L, GroupAppState.ENABLED)))
        val gate = CompletableDeferred<Group>()
        val fake = FakeOperations(groups = listOf(original), installedEntries = listOf(appItem().entry))
        val operations = object : MainOperations by fake {
            override suspend fun findGroup(groupId: String): Group = gate.await()
        }
        val model = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        var dialogs = 0
        model.prepareClonePermissionRequest(model.sessionState.value.groups.single().apps.single(), "android.permission.CAMERA") { dialogs++ }
        runCurrent()
        model.cancelClonePermissionRequest()
        gate.complete(original)
        advanceUntilIdle()
        assertEquals(0, dialogs)
        assertEquals(null, model.pendingClonePermissionTicket())
    }

    @Test
    fun `late lookup and old callback cannot replace or consume newer request`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val original = group().copy(apps = listOf(GroupApp(APP_PACKAGE, 2L, GroupAppState.ENABLED)))
        val oldLookup = CompletableDeferred<Group>()
        val fake = FakeOperations(groups = listOf(original), installedEntries = listOf(appItem().entry))
        var lookups = 0
        val operations = object : MainOperations by fake {
            override suspend fun findGroup(groupId: String): Group = if (++lookups == 1) oldLookup.await() else original
        }
        val model = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        val item = model.sessionState.value.groups.single().apps.single()
        var dialogs = 0
        model.prepareClonePermissionRequest(item, "android.permission.CAMERA") { dialogs++ }
        runCurrent()
        model.prepareClonePermissionRequest(item, "android.permission.RECORD_AUDIO") { dialogs++ }
        runCurrent()
        val newerTicket = model.pendingClonePermissionTicket()
        oldLookup.complete(original)
        advanceUntilIdle()
        assertEquals(1, dialogs)
        assertEquals("android.permission.RECORD_AUDIO", model.pendingClonePermissionName())
        model.completeClonePermissionRequest("android.permission.CAMERA", true, newerTicket!! - 1L)
        assertEquals(newerTicket, model.pendingClonePermissionTicket())
        model.cancelClonePermissionRequest()
    }

    @Test
    fun `permission prepare accepts launched member state but rejects replacement timestamp`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val originalMember = GroupApp(APP_PACKAGE, 2L, GroupAppState.ADDED)
        val originalGroup = group().copy(apps = listOf(originalMember))
        var currentGroup = originalGroup
        val fake = FakeOperations(groups = listOf(originalGroup), installedEntries = listOf(appItem().entry))
        val operations = object : MainOperations by fake {
            override suspend fun findGroup(groupId: String): Group = currentGroup
        }
        val model = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()
        val capturedItem = model.sessionState.value.groups.single().apps.single()
        currentGroup = originalGroup.copy(apps = listOf(originalMember.copy(state = GroupAppState.ENABLED)))
        var dialogs = 0
        model.prepareClonePermissionRequest(capturedItem, "android.permission.CAMERA") { dialogs++ }
        advanceUntilIdle()
        assertEquals(1, dialogs)
        assertEquals("android.permission.CAMERA", model.pendingClonePermissionName())
        model.cancelClonePermissionRequest()
        currentGroup = originalGroup.copy(apps = listOf(originalMember.copy(addedAtEpochMillis = 3L, state = GroupAppState.ENABLED)))
        model.prepareClonePermissionRequest(capturedItem, "android.permission.CAMERA") { dialogs++ }
        advanceUntilIdle()
        assertEquals(1, dialogs)
        assertEquals(null, model.pendingClonePermissionName())
    }

    private fun viewModel(
        savedState: SavedStateHandle,
        operations: MainOperations,
        ioDispatcher: CoroutineDispatcher,
        launchDispatcher: CoroutineDispatcher = ioDispatcher,
    ) = MainViewModel(
        savedStateHandle = savedState,
        operations = operations,
        ioDispatcher = ioDispatcher,
        launchDispatcher = launchDispatcher,
    )

    private class FakeOperations(
        private val groups: List<Group> = emptyList(),
        private val installedEntries: List<InstalledAppEntry> = emptyList(),
        private val reconcileGate: CompletableDeferred<Unit>? = null,
        private val expectedIoDispatcher: CoroutineDispatcher? = null,
        private val loadIssues: List<GroupStoreLoadIssue> = emptyList(),
        private val findGroupGate: CompletableDeferred<Unit>? = null,
        private val diagnostics: String? = null,
        private val deepLinkCandidates: List<Pair<String, String>>? = null,
        private val gmsReconcileError: Throwable? = null,
        private val deleteShortcutWarning: String? = null,
        private val gmsStartupProductStates: Map<String, GmsGroupProductState> = emptyMap(),
        private val refreshWarnings: List<String> = emptyList(),
        private val clonePermissions: List<ClonePermissionSummary> = emptyList(),
        private val refreshGate: CompletableDeferred<Unit>? = null,
        private val refreshError: Throwable? = null,
        var loadGroupError: Throwable? = null,
        private val firstGroupLoadGate: CompletableDeferred<Unit>? = null,
        private val secondGroupLoadGate: CompletableDeferred<Unit>? = null,
        private val clearGroupStorageGate: CompletableDeferred<Unit>? = null,
        private val clearGroupStorageResult: ClearSpaceStorageResult =
            ClearSpaceStorageResult.Cleared(0),
        private val addAppGate: CompletableDeferred<Unit>? = null,
        private val addAppError: Throwable? = null,
        private val launchGate: CompletableDeferred<Unit>? = null,
        private val launchResult: RuntimeLaunchResult = RuntimeLaunchResult.Failed("unused"),
        private val deepLinkLaunchResult: RuntimeLaunchResult = RuntimeLaunchResult.Failed("unused"),
        private val expectedLaunchDispatcher: CoroutineDispatcher? = null,
    ) : MainOperations {
        var backgroundLoader: suspend (String) -> SpaceBackgroundHealth = { SpaceBackgroundHealth(it) }
        var backgroundRetry: suspend (String) -> Boolean = { false }
        override suspend fun loadSpaceBackgroundHealth(groupId: String) = backgroundLoader(groupId)
        override suspend fun retrySpaceBackground(groupId: String) = backgroundRetry(groupId)
        var detailsLoader: suspend (String) -> SpaceDetails? = { null }
        var accountAdder: suspend (String, String) -> Unit = { _, _ -> }
        override suspend fun loadSpaceDetails(groupId: String): SpaceDetails? = detailsLoader(groupId)
        override suspend fun addSpaceAccount(groupId: String, type: String) = accountAdder(groupId, type)
        private var currentGroups = groups
        var reconcileStarted = false
        var loadGroupCalls = 0
        var refreshCalls = 0
        var refreshRanOnIoDispatcher = false
        var refreshRanOnMainDispatcher = false
        val clearedGroupIds = mutableListOf<String>()
        val deletedGroupIds = mutableListOf<String>()
        val addedApps = mutableListOf<Pair<String, String>>()
        val launchedApps = mutableListOf<GroupAppItem>()
        val launchedDeepLinks = mutableListOf<Pair<String, String>>()
        var gmsReconcileCalls = 0
        var launchRanOnExpectedDispatcher = false
        var maxConcurrentLaunches = 0
        private var activeLaunches = 0

        override suspend fun loadGroupSnapshot(): MainGroupSnapshot {
            loadGroupCalls += 1
            if (loadGroupCalls == 1) firstGroupLoadGate?.await()
            if (loadGroupCalls == 2) secondGroupLoadGate?.await()
            loadGroupError?.let { throw it }
            return MainGroupSnapshot(
                groups = currentGroups,
                operations = emptyList(),
                dataWarnings = loadIssues.map { "corrupt metadata" } + refreshWarnings,
            )
        }

        override suspend fun refreshSnapshot(groups: MainGroupSnapshot): MainRefreshSnapshot {
            refreshCalls++
            val interceptor = currentCoroutineContext()[ContinuationInterceptor]
            refreshRanOnIoDispatcher = interceptor == expectedIoDispatcher
            refreshRanOnMainDispatcher = interceptor == Dispatchers.Main
            refreshGate?.await()
            refreshError?.let { throw it }
            return MainRefreshSnapshot(
                storage = StorageStatus(false, 0, 0),
                entries = installedEntries,
                groups = groups.groups,
                activeRevisions = emptyMap(),
                dataWarnings = groups.dataWarnings,
                clonePermissions = clonePermissions,
            )
        }

        override suspend fun findGroup(groupId: String): Group? {
            findGroupGate?.await()
            return currentGroups.firstOrNull { it.id == groupId }
        }

        override suspend fun createGroup(name: String): Group = error("unused")
        override suspend fun renameGroup(groupId: String, name: String): Group? = error("unused")
        override suspend fun deleteGroup(groupId: String): DeleteGroupResult {
            deletedGroupIds += groupId
            return currentGroups.firstOrNull { it.id == groupId }
                ?.let { DeleteGroupResult.Deleted(it, deleteShortcutWarning) }
                ?: DeleteGroupResult.NotFound
        }
        override suspend fun exportSpace(
            groupId: String,
            destination: android.net.Uri,
            compression: org.apptwin.archive.SpaceArchiveCompression,
        ): SpaceArchiveExportResult = error("unused")
        override suspend fun createSpaceFromBackup(source: android.net.Uri): CreateSpaceFromBackupResult =
            error("unused")
        override suspend fun addAppToGroup(groupId: String, packageName: String): Group {
            addedApps += groupId to packageName
            addAppGate?.await()
            addAppError?.let { throw it }
            val group = currentGroups.first { it.id == groupId }
            val updated = group.copy(
                apps = group.apps + GroupApp(packageName, addedAtEpochMillis = 2),
            )
            currentGroups = currentGroups.map { if (it.id == groupId) updated else it }
            return updated
        }
        override suspend fun launchGroupApp(item: GroupAppItem): RuntimeLaunchResult {
            launchedApps += item
            launchRanOnExpectedDispatcher =
                currentCoroutineContext()[ContinuationInterceptor] == expectedLaunchDispatcher
            activeLaunches += 1
            maxConcurrentLaunches = maxOf(maxConcurrentLaunches, activeLaunches)
            return try {
                launchGate?.await()
                launchResult
            } finally {
                activeLaunches -= 1
            }
        }
        override suspend fun uninstallGroupApp(item: GroupAppItem): GroupAppRemovalResult =
            error("unused")
        override suspend fun clearGroupAppStorage(item: GroupAppItem): ClearCloneStorageResult =
            error("unused")
        override suspend fun clearGroupStorage(groupId: String): ClearSpaceStorageResult {
            clearedGroupIds += groupId
            clearGroupStorageGate?.await()
            return clearGroupStorageResult
        }
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
        ): RuntimeLaunchResult {
            launchedDeepLinks += item.groupId to uri
            return deepLinkLaunchResult
        }

        override suspend fun reconcileGroups(): GroupReconciliationResult {
            reconcileStarted = true
            reconcileGate?.await()
            return GroupReconciliationResult(loadIssues)
        }

        override suspend fun reconcileAppRemovals() = Unit
        override suspend fun reconcileApplicationOperations() = Unit
        override suspend fun reconcileGms(): GmsStartupResult {
            gmsReconcileCalls += 1
            gmsReconcileError?.let { throw it }
            return GmsStartupResult(
                reconciliation = GmsReconciliationResult(
                    completed = emptyList(),
                    retainedForRetry = emptyList(),
                    terminalFailures = emptyList(),
                    releaseMismatches = emptyList(),
                ),
                profiles = gmsStartupProductStates.values.map(GmsGroupProductState::profile),
                productStates = gmsStartupProductStates,
            )
        }

    }

    private class FakeOnboardingStore : OnboardingStore {
        var completed = false

        override fun isCompleted(): Boolean = completed

        override fun markCompleted() {
            completed = true
        }
    }

    private class FakeArchiveExportSettingsStore(
        var value: org.apptwin.archive.SpaceArchiveCompression,
    ) : org.apptwin.archive.ArchiveExportSettingsStore {
        override fun load(): org.apptwin.archive.SpaceArchiveCompression = value

        override fun save(compression: org.apptwin.archive.SpaceArchiveCompression) {
            value = compression
        }
    }

    private companion object {
        const val GROUP_ID = "00000000-0000-0000-0000-000000000001"
        const val APP_PACKAGE = "com.example.test"

        fun appItem() = AppItem(
            entry = InstalledAppEntry(
                label = "測試 App",
                packageName = APP_PACKAGE,
                versionName = "1.0",
                versionCode = 1,
            ),
            isSynced = false,
            activeVersionCode = null,
            groupCount = 0,
        )

        fun group() = Group(
            id = GROUP_ID,
            name = "工作",
            createdAtEpochMillis = 1,
            environmentBinding = EnvironmentBinding(7),
            health = GroupHealth.HEALTHY,
        )
    }
}
