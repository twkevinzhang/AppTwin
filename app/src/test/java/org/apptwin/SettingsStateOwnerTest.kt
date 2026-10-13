package org.apptwin

import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.apptwin.archive.ArchiveExportSettingsStore
import org.apptwin.archive.SpaceArchiveCompression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStateOwnerTest {
    @Test
    fun `compression persists across owners and failed save retains previous selection`() = runTest {
        val preferences = Preferences()
        val messages = mutableListOf<String>()
        fun owner() = SettingsStateOwner(backgroundScope, StandardTestDispatcher(testScheduler), Archives(), Reports(), preferences, messages::add, {})
        val first = owner()
        first.setArchiveCompression(SpaceArchiveCompression.HIGH)
        assertEquals(SpaceArchiveCompression.HIGH, owner().state.archiveCompression)
        preferences.fail = true
        first.setArchiveCompression(SpaceArchiveCompression.LOW)
        assertEquals(SpaceArchiveCompression.HIGH, first.state.archiveCompression)
        assertEquals(SpaceArchiveCompression.HIGH, preferences.value)
        assertTrue(messages.single().contains("save failed"))
    }

    @Test
    fun `late settings location read cannot replace location published by completed space action`() = runTest {
        val pending = CompletableDeferred<String?>()
        val archives = Archives().apply { read = { pending.await() } }
        val owner = SettingsStateOwner(backgroundScope, StandardTestDispatcher(testScheduler), archives, Reports(), Preferences(), {}, {})
        owner.refreshBackupLocation()
        runCurrent()
        owner.publishBackupLocation("new-location")
        pending.complete("old-location")
        runCurrent()
        assertEquals("new-location", owner.state.globalBackupLocation)
    }

    @Test
    fun `failed location refresh preserves prior location and reports error`() = runTest {
        val messages = mutableListOf<String>()
        val archives = Archives().apply { read = { error("location unavailable") } }
        val owner = SettingsStateOwner(backgroundScope, StandardTestDispatcher(testScheduler), archives, Reports(), Preferences(), messages::add, {})
        owner.publishBackupLocation("existing")
        owner.refreshBackupLocation()
        runCurrent()
        assertEquals("existing", owner.state.globalBackupLocation)
        assertTrue(messages.single().contains("location unavailable"))
    }

    @Test
    fun `old diagnostic consumer cannot clear a later report and failures retain pending report`() = runTest {
        val reports = Reports()
        val messages = mutableListOf<String>()
        val owner = SettingsStateOwner(backgroundScope, StandardTestDispatcher(testScheduler), Archives(), reports, Preferences(), messages::add, {})
        owner.exportDiagnostics()
        runCurrent()
        val firstId = owner.state.diagnosticsReportId
        owner.exportDiagnostics()
        runCurrent()
        val secondId = owner.state.diagnosticsReportId
        owner.consumeDiagnostics(firstId)
        assertEquals("report-2", owner.state.diagnosticsReport)
        reports.fail = true
        owner.exportDiagnostics()
        runCurrent()
        assertEquals(secondId, owner.state.diagnosticsReportId)
        assertEquals("report-2", owner.state.diagnosticsReport)
        assertTrue(messages.single().startsWith("產生診斷報告失敗："))
        owner.consumeDiagnostics(secondId)
        owner.consumeDiagnostics(secondId)
        assertNull(owner.state.diagnosticsReport)
        assertEquals(secondId, owner.state.diagnosticsReportId)
    }

    @Test
    fun `storage enrichment leaves preferences and pending diagnostic untouched`() = runTest {
        val owner = SettingsStateOwner(backgroundScope, StandardTestDispatcher(testScheduler), Archives(), Reports(), Preferences(), {}, {})
        owner.setArchiveCompression(SpaceArchiveCompression.LOW)
        owner.publishBackupLocation("selected")
        owner.exportDiagnostics()
        runCurrent()
        owner.acceptSnapshot(MainRefreshSnapshot(StorageStatus(true, 4, 7), emptyList(), emptyList(), emptyMap(), emptyList()))
        assertTrue(owner.state.allFilesGranted)
        assertEquals(4, owner.state.downloadCount)
        assertEquals(7, owner.state.photoCount)
        assertEquals(SpaceArchiveCompression.LOW, owner.state.archiveCompression)
        assertEquals("selected", owner.state.globalBackupLocation)
        assertEquals("report-1", owner.state.diagnosticsReport)
    }

    @Test
    fun `cancelled location refresh retains location without reporting failure or refreshing details`() = runTest {
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val pending = CompletableDeferred<String?>()
        val messages = mutableListOf<String>()
        var detailRefreshes = 0
        val owner = SettingsStateOwner(scope, StandardTestDispatcher(testScheduler),
            Archives().apply { read = { pending.await() } }, Reports(), Preferences(),
            messages::add, { detailRefreshes++ })
        owner.publishBackupLocation("existing")
        owner.refreshBackupLocation()
        runCurrent()
        scope.cancel()
        runCurrent()
        assertEquals("existing", owner.state.globalBackupLocation)
        assertTrue(messages.isEmpty())
        assertEquals(0, detailRefreshes)
    }

    @Test
    fun `cancelled diagnostic export preserves unconsumed report without reporting failure`() = runTest {
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val pending = CompletableDeferred<String>()
        var exports = 0
        val reports = object : DiagnosticOperations {
            override suspend fun exportDiagnostics(): String =
                if (++exports == 1) "ready-report" else pending.await()
        }
        val messages = mutableListOf<String>()
        val owner = SettingsStateOwner(scope, StandardTestDispatcher(testScheduler),
            Archives(), reports, Preferences(), messages::add, {})
        owner.exportDiagnostics()
        runCurrent()
        val reportId = owner.state.diagnosticsReportId
        owner.exportDiagnostics()
        runCurrent()
        scope.cancel()
        runCurrent()
        assertEquals("ready-report", owner.state.diagnosticsReport)
        assertEquals(reportId, owner.state.diagnosticsReportId)
        assertTrue(messages.isEmpty())
    }

    private class Preferences : ArchiveExportSettingsStore {
        var value = SpaceArchiveCompression.MEDIUM
        var fail = false
        override fun load() = value
        override fun save(compression: SpaceArchiveCompression) {
            check(!fail) { "save failed" }
            value = compression
        }
    }

    private class Reports : DiagnosticOperations {
        var count = 0
        var fail = false
        override suspend fun exportDiagnostics(): String {
            check(!fail) { "report failed" }
            return "report-${++count}"
        }
    }

    private class Archives : SpaceArchiveOperations {
        var read: suspend () -> String? = { null }
        override suspend fun globalBackupLocation() = read()
        override suspend fun exportSpace(groupId: String, destination: Uri, compression: SpaceArchiveCompression): SpaceArchiveExportResult = error("not used")
        override suspend fun createSpaceFromBackup(source: Uri): CreateSpaceFromBackupResult = error("not used")
    }
}
