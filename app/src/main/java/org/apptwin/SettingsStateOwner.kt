package org.apptwin

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apptwin.archive.ArchiveExportSettingsStore
import org.apptwin.archive.SpaceArchiveCompression

/** Owns preferences, diagnostic delivery and the settings portion of snapshot enrichment. */
internal class SettingsStateOwner(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val archives: SpaceArchiveOperations,
    private val diagnostics: DiagnosticOperations,
    private val archiveExportSettingsStore: ArchiveExportSettingsStore,
    private val showMessage: (String) -> Unit,
    private val refreshDetails: () -> Unit,
) {
    private val mutableState = MutableStateFlow(
        SettingsUiState(archiveCompression = archiveExportSettingsStore.load()),
    )
    val states = mutableState.asStateFlow()
    val state: SettingsUiState get() = mutableState.value
    private var locationGeneration = 0L

    fun acceptSnapshot(snapshot: MainRefreshSnapshot) {
        mutableState.value = state.copy(
            allFilesGranted = snapshot.storage.granted,
            downloadCount = snapshot.storage.downloadCount,
            photoCount = snapshot.storage.photoCount,
            clonePermissions = snapshot.clonePermissions,
        )
    }

    fun setArchiveCompression(compression: SpaceArchiveCompression) {
        if (state.archiveCompression == compression) return
        runCatching { archiveExportSettingsStore.save(compression) }
            .onSuccess { mutableState.value = state.copy(archiveCompression = compression) }
            .onFailure { showMessage(it.operationMessage()) }
    }

    fun publishBackupLocation(location: String?) {
        ++locationGeneration
        mutableState.value = state.copy(globalBackupLocation = location)
    }

    fun refreshBackupLocation() {
        val generation = ++locationGeneration
        scope.launch {
            runCatching { withContext(ioDispatcher) { archives.globalBackupLocation() } }
                .onSuccess { location ->
                    if (generation == locationGeneration) {
                        mutableState.value = state.copy(globalBackupLocation = location)
                    }
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    if (generation == locationGeneration) showMessage(it.operationMessage())
                }
        }
    }

    fun setGlobalBackupLocation(uri: Uri) {
        val generation = ++locationGeneration
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    archives.setGlobalBackupLocation(uri)
                    archives.globalBackupLocation()
                }
            }.onSuccess { location ->
                if (generation == locationGeneration) {
                    mutableState.value = state.copy(globalBackupLocation = location)
                }
            }.onFailure {
                if (it is CancellationException) throw it
                if (generation == locationGeneration) showMessage(it.operationMessage())
            }
            refreshDetails()
        }
    }

    fun exportDiagnostics() {
        scope.launch {
            runCatching { withContext(ioDispatcher) { diagnostics.exportDiagnostics() } }
                .onSuccess { report ->
                    mutableState.value = state.copy(
                        diagnosticsReport = report,
                        diagnosticsReportId = state.diagnosticsReportId + 1,
                    )
                }.onFailure {
                    if (it is CancellationException) throw it
                    showMessage("產生診斷報告失敗：${it.operationMessage()}")
                }
        }
    }

    fun consumeDiagnostics(reportId: Long) {
        if (state.diagnosticsReportId == reportId && state.diagnosticsReport != null) {
            mutableState.value = state.copy(diagnosticsReport = null)
        }
    }
}
