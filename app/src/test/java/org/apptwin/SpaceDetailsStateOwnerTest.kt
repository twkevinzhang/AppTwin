package org.apptwin

import android.net.Uri
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.apptwin.archive.SpaceArchiveCompression
import org.apptwin.groups.Group
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpaceDetailsStateOwnerTest {
    @Test fun lateDetailsFromPreviousSelectionCannotReplaceNewSpace() = runTest {
        val h = Harness(this)
        val first = CompletableDeferred<SpaceDetails?>()
        h.loader = { if (it == "A") first.await() else details(it) }
        h.owner.refreshSpaceDetails(); runCurrent()
        h.selected = "B"; h.owner.invalidateSelection(); h.owner.refreshSpaceDetails(); runCurrent()
        first.complete(details("A")); runCurrent()
        assertEquals("B", h.owner.state.spaceDetails?.groupId)
        assertFalse(h.owner.state.isLoadingSpaceDetails)
    }
    @Test fun leavingDetailsDiscardsLateFailureAndItsMessage() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<Unit>()
        h.loader = { gate.await(); error("late failure") }
        h.owner.refreshSpaceDetails(); runCurrent()
        h.visible = false; h.owner.invalidateSelection()
        gate.complete(Unit); runCurrent()
        assertNull(h.owner.state.spaceDetailsError)
        assertTrue(h.messages.isEmpty())
    }
    @Test fun failedReadCanRetryAndMissingSpaceReturnsHome() = runTest {
        val h = Harness(this)
        h.loader = { error("read failed") }
        h.owner.refreshSpaceDetails(); runCurrent()
        assertEquals("read failed", h.owner.state.spaceDetailsError)
        h.loader = { details(it) }; h.owner.refreshSpaceDetails(); runCurrent()
        assertNull(h.owner.state.spaceDetailsError)
        assertEquals("A", h.owner.state.spaceDetails?.groupId)
        h.loader = { null }; h.owner.refreshSpaceDetails(); runCurrent()
        assertNull(h.selected)
        assertEquals(1, h.homeNavigations)
    }
    @Test fun offlineObservationKeepsLoadedDetailsAndStopCancelsRetryOnly() = runTest {
        val h = Harness(this)
        h.owner.refreshSpaceDetails(); runCurrent()
        h.healthLoader = { SpaceBackgroundHealth(it, tcp = SpaceBackgroundStatus.FAILED) }
        h.owner.refreshSpaceBackgroundHealth(); runCurrent()
        assertEquals("A", h.owner.state.spaceDetails?.groupId)
        val retryGate = CompletableDeferred<Boolean>()
        h.retry = { retryGate.await() }
        h.owner.retrySpaceBackground(); runCurrent()
        assertTrue(h.owner.state.isSpaceBackgroundRetrying)
        h.owner.stopSpaceBackgroundObservation(); runCurrent()
        retryGate.complete(true); runCurrent()
        assertFalse(h.owner.state.isSpaceBackgroundRetrying)
        assertEquals(SpaceBackgroundStatus.FAILED, h.owner.state.spaceBackgroundHealth?.tcp)
        assertEquals("A", h.owner.state.spaceDetails?.groupId)
        assertEquals(1, h.retryCalls)
    }
    @Test fun accountAndArchiveShareOneBusyLaneAndReleaseItAfterFailure() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<Unit>()
        h.accountAdder = { _, _ -> gate.await(); error("account failed") }
        h.owner.addSpaceAccount("type"); runCurrent()
        assertTrue(h.owner.state.isSpaceActionBusy)
        h.owner.addSpaceAccount("duplicate")
        h.owner.exportToGlobalLocation("A")
        runCurrent()
        assertEquals(1, h.accountCalls)
        assertEquals(0, h.exportCalls)
        gate.complete(Unit); runCurrent()
        assertFalse(h.owner.state.isSpaceActionBusy)
        assertTrue(h.messages.contains("account failed"))
        h.owner.exportToGlobalLocation("A"); runCurrent()
        assertEquals(1, h.exportCalls)
    }

    @Test fun accountResultsArePublishedOnOwnerDispatcherAfterIoCompletes() = runTest {
        val base = StandardTestDispatcher(testScheduler)
        var ioActive = false
        var ioDispatches = 0
        var publications = 0
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                base.dispatch(context, Runnable {
                    ioActive = true
                    ++ioDispatches
                    try { block.run() } finally { ioActive = false }
                })
            }
        }
        val h = Harness(this, io) {
            assertFalse("UI event published from the IO lane", ioActive)
            ++publications
        }
        h.owner.synchronizeSpaceAccount(SpaceAccountEntry("fixture", "type"), "authority")
        runCurrent()
        h.owner.removeSpaceAccount(SpaceAccountEntry("fixture", "type"))
        runCurrent()
        assertEquals(listOf("同步未支援", "帳號服務未允許移除"), h.messages)
        assertEquals(2, publications)
        assertTrue(ioDispatches >= 2)
    }

    private class Harness(scope: TestScope, io: CoroutineDispatcher? = null, onMessage: (String) -> Unit = {}) {
        var selected: String? = "A"
        var visible = true
        var homeNavigations = 0
        val messages = mutableListOf<String>()
        var loader: suspend (String) -> SpaceDetails? = { details(it) }
        var healthLoader: suspend (String) -> SpaceBackgroundHealth = { SpaceBackgroundHealth(it) }
        var retry: suspend (String) -> Boolean = { false }
        var retryCalls = 0
        var accountAdder: suspend (String, String) -> Unit = { _, _ -> }
        var accountCalls = 0
        var exportCalls = 0
        private val snapshots = object : MainSnapshotOperations {
            override suspend fun loadGroupSnapshot(): MainGroupSnapshot = error("unused")
            override suspend fun refreshSnapshot(groups: MainGroupSnapshot): MainRefreshSnapshot = error("unused")
            override suspend fun loadSpaceDetails(groupId: String) = loader(groupId)
        }
        private val background = object : BackgroundRecoveryOperations {
            override suspend fun loadSpaceBackgroundHealth(groupId: String) = healthLoader(groupId)
            override suspend fun retrySpaceBackground(groupId: String): Boolean { ++retryCalls; return retry(groupId) }
            override suspend fun reconcileGroups(): org.apptwin.groups.GroupReconciliationResult = error("unused")
            override suspend fun reconcileAppRemovals() = Unit
            override suspend fun reconcileApplicationOperations() = Unit
            override suspend fun reconcileGms(): org.apptwin.gms.GmsStartupResult = error("unused")
        }
        private val clone = object : CloneOperations {
            override suspend fun addAppToGroup(groupId: String, packageName: String): Group = error("unused")
            override suspend fun launchGroupApp(item: GroupAppItem): org.apptwin.runtime.RuntimeLaunchResult = error("unused")
            override suspend fun uninstallGroupApp(item: GroupAppItem): org.apptwin.groups.GroupAppRemovalResult = error("unused")
            override suspend fun clearGroupAppStorage(item: GroupAppItem): org.apptwin.usecases.ClearCloneStorageResult = error("unused")
            override suspend fun createShortcut(item: GroupAppItem): ShortcutCreationResult = error("unused")
            override suspend fun repairClone(item: GroupAppItem): org.apptwin.repair.RepairExecutionResult = error("unused")
            override suspend fun resolveDeepLink(uri: String): List<Pair<String, String>> = error("unused")
            override suspend fun launchDeepLink(item: GroupAppItem, uri: String): org.apptwin.runtime.RuntimeLaunchResult = error("unused")
        }
        private val dispatcher = io ?: StandardTestDispatcher(scope.testScheduler)
        private val session = MainSessionCoordinator(scope.backgroundScope, dispatcher, dispatcher, clone, background, messages::add, messages::add)
        val owner = SpaceDetailsStateOwner(
            snapshots, background,
            object : SpaceAccountOperations {
                override suspend fun addSpaceAccount(groupId: String, type: String) { ++accountCalls; accountAdder(groupId, type) }
            },
            object : SpaceArchiveOperations {
                override suspend fun exportToGlobalLocation(groupId: String, compression: SpaceArchiveCompression): SpaceArchiveExportResult {
                    ++exportCalls; return SpaceArchiveExportResult("space", 0)
                }
                override suspend fun exportSpace(groupId: String, destination: Uri, compression: SpaceArchiveCompression): SpaceArchiveExportResult = error("unused")
                override suspend fun createSpaceFromBackup(source: Uri): CreateSpaceFromBackupResult = error("unused")
            },
            object : ClonePermissionOperations {
                override suspend fun setClonePermission(item: GroupAppItem, permission: String, granted: Boolean) = false
            },
            scope.backgroundScope, dispatcher, session, { selected }, { visible }, { message -> onMessage(message); messages.add(message) },
            {}, { ++homeNavigations; visible = false }, { selected = null },
            { SpaceArchiveCompression.MEDIUM }, {}, {},
        )
    }
    companion object {
        private fun details(id: String) = SpaceDetails(id, SpaceStorageBreakdown(), emptyList(), emptyList(), emptyList(), emptyList(), null)
    }
}
