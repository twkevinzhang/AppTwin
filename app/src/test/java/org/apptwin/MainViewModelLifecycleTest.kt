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
    fun `archive compression is loaded and persisted through ViewModel`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val settings = FakeArchiveExportSettingsStore(
            org.apptwin.archive.SpaceArchiveCompression.HIGH,
        )
        val operations = FakeOperations()
        val first = MainViewModel(
            application = Application(),
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
            application = Application(),
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
    fun `GMS enable exposes exact busy action and blocks destructive space operations`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val enableGate = CompletableDeferred<Unit>()
        val operations = FakeOperations(
            groups = listOf(
                group().copy(apps = listOf(GroupApp(APP_PACKAGE, 2L, GroupAppState.ENABLED))),
            ),
            gmsEnableGate = enableGate,
        )
        val viewModel = viewModel(SavedStateHandle(), operations, dispatcher)
        advanceUntilIdle()

        viewModel.enableGms(GROUP_ID)
        runCurrent()

        assertEquals(GROUP_ID, viewModel.uiState.gmsBusyGroupId)
        assertEquals(GmsBusyAction.ENABLE, viewModel.uiState.gmsBusyAction)

        viewModel.deleteGroup(GROUP_ID)
        viewModel.launchGroupApp(viewModel.uiState.groups.single().apps.single())
        runCurrent()

        assertTrue(operations.deletedGroupIds.isEmpty())
        assertTrue(operations.launchedApps.isEmpty())

        enableGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.gmsBusyGroupId)
        assertEquals(null, viewModel.uiState.gmsBusyAction)
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

    private fun viewModel(
        savedState: SavedStateHandle,
        operations: MainOperations,
        ioDispatcher: CoroutineDispatcher,
        launchDispatcher: CoroutineDispatcher = ioDispatcher,
    ) = MainViewModel(
        application = Application(),
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
        private val gmsEnableGate: CompletableDeferred<Unit>? = null,
        private val deleteShortcutWarning: String? = null,
        private val gmsStartupProductStates: Map<String, GmsGroupProductState> = emptyMap(),
        private val refreshWarnings: List<String> = emptyList(),
        private val clonePermissions: List<ClonePermissionSummary> = emptyList(),
        private val refreshGate: CompletableDeferred<Unit>? = null,
        private val refreshError: Throwable? = null,
        private val loadGroupError: Throwable? = null,
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
        private var currentGroups = groups
        var reconcileStarted = false
        var loadGroupCalls = 0
        var refreshCalls = 0
        var refreshRanOnIoDispatcher = false
        var refreshRanOnMainDispatcher = false
        val gmsConsentGroups = mutableListOf<String>()
        val gmsEnableGroups = mutableListOf<String>()
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
        override suspend fun importSpace(source: android.net.Uri): SpaceArchiveImportResult =
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
        override suspend fun grantGmsConsent(groupId: String) {
            gmsConsentGroups += groupId
        }
        override suspend fun enableGms(groupId: String): GmsLifecycleResult {
            gmsEnableGroups += groupId
            gmsEnableGate?.await()
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
