package org.apptwin

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apptwin.archive.SpaceArchiveCompression

/** Owns detail content and bounded observation; transaction lanes remain session-owned. */
internal class SpaceDetailsStateOwner(
    private val snapshots: MainSnapshotOperations,
    private val background: BackgroundRecoveryOperations,
    private val accounts: SpaceAccountOperations,
    private val archives: SpaceArchiveOperations,
    private val permissions: ClonePermissionOperations,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val session: MainSessionCoordinator,
    private val selectedSpaceId: () -> String?,
    private val isDetailsVisible: () -> Boolean,
    private val showMessage: (String) -> Unit,
    private val refreshHome: () -> Unit,
    private val navigateHome: () -> Unit,
    private val clearSelectedSpace: () -> Unit,
    private val archiveCompression: () -> SpaceArchiveCompression,
    private val setCreateSpaceError: (String?) -> Unit,
    private val reconcileGoogleAfterCommit: () -> Unit,
) {
    private data class Content(
        val spaceDetails: SpaceDetails? = null,
        val spaceDetailsError: String? = null,
        val spaceBackgroundHealth: SpaceBackgroundHealth? = null,
        val isSpaceBackgroundRetrying: Boolean = false,
        val isLoadingSpaceDetails: Boolean = false,
    ) {
        fun project(selected: String?, busy: Boolean) = SpaceDetailsUiState(
            selected, spaceDetails, spaceDetailsError, spaceBackgroundHealth,
            isSpaceBackgroundRetrying, isLoadingSpaceDetails, busy,
        )
    }
    private val content = MutableStateFlow(Content())
    private var detailsGeneration = 0L
    private var backgroundGeneration = 0L
    private var backgroundJob: Job? = null
    val state: SpaceDetailsUiState get() = content.value.project(selectedSpaceId(), session.state.isSpaceActionBusy)
    val states = combine(content, session.states) { detail, shared ->
        detail.project(selectedSpaceId(), shared.isSpaceActionBusy)
    }.stateIn(scope, SharingStarted.Eagerly, state)

    private fun current(id: String, generation: Long) = generation == detailsGeneration &&
        selectedSpaceId() == id && isDetailsVisible()
    private fun backgroundCurrent(id: String, generation: Long) = generation == backgroundGeneration &&
        selectedSpaceId() == id && isDetailsVisible()
    private fun <T> Result<T>.propagateCancellation() {
        (exceptionOrNull() as? CancellationException)?.let { throw it }
    }

    fun invalidateSelection() {
        ++detailsGeneration
        stopSpaceBackgroundObservation()
        content.value = Content()
    }

    fun refreshSpaceDetails() {
        val id = selectedSpaceId() ?: return
        val generation = ++detailsGeneration
        content.value = content.value.copy(isLoadingSpaceDetails = true, spaceDetailsError = null)
        scope.launch {
            val result = runCatching { withContext(ioDispatcher) { snapshots.loadSpaceDetails(id) } }
            result.propagateCancellation()
            if (!current(id, generation)) return@launch
            if (result.isSuccess && result.getOrNull() == null) {
                clearSelectedSpace()
                content.value = Content()
                navigateHome()
            } else {
                content.value = content.value.copy(spaceDetails = result.getOrNull(), isLoadingSpaceDetails = false,
                    spaceDetailsError = result.exceptionOrNull()?.operationMessage())
                result.onFailure { showMessage(it.operationMessage()) }
            }
        }
    }

    fun refreshSpaceBackgroundHealth() {
        val id = selectedSpaceId() ?: return
        if (!isDetailsVisible() || state.isSpaceBackgroundRetrying) return
        backgroundJob?.cancel()
        val generation = ++backgroundGeneration
        backgroundJob = scope.launch {
            val result = runCatching { withContext(ioDispatcher) { background.loadSpaceBackgroundHealth(id) } }
            result.propagateCancellation()
            if (!backgroundCurrent(id, generation)) return@launch
            content.value = content.value.copy(spaceBackgroundHealth = result.getOrElse {
                SpaceBackgroundHealth(id, error = "無法讀取背景服務狀態")
            })
        }
    }

    fun stopSpaceBackgroundObservation() {
        ++backgroundGeneration
        backgroundJob?.cancel()
        backgroundJob = null
        content.value = content.value.copy(isSpaceBackgroundRetrying = false)
    }

    fun retrySpaceBackground() {
        val id = selectedSpaceId() ?: return
        if (!isDetailsVisible() || state.isSpaceBackgroundRetrying) return
        val health = state.spaceBackgroundHealth?.takeIf { it.groupId == id } ?: return
        if (!health.retryable) return
        backgroundJob?.cancel()
        val generation = ++backgroundGeneration
        content.value = content.value.copy(isSpaceBackgroundRetrying = true)
        backgroundJob = scope.launch {
            val result = runCatching { withContext(ioDispatcher) {
                val accepted = background.retrySpaceBackground(id)
                var updated = background.loadSpaceBackgroundHealth(id)
                if (accepted) {
                    var checks = 0
                    while ((updated.microg == SpaceBackgroundStatus.STARTING || updated.tcp == SpaceBackgroundStatus.STARTING) && checks++ < 20) {
                        delay(2_000L)
                        updated = background.loadSpaceBackgroundHealth(id)
                    }
                    if (updated.microg == SpaceBackgroundStatus.STARTING || updated.tcp == SpaceBackgroundStatus.STARTING)
                        updated.copy(error = "連線尚未恢復，請稍後檢查") else updated
                } else updated.copy(error = "重試未被接受，請確認背景服務已開啟")
            } }
            result.propagateCancellation()
            if (!backgroundCurrent(id, generation)) return@launch
            content.value = content.value.copy(isSpaceBackgroundRetrying = false,
                spaceBackgroundHealth = result.getOrElse { SpaceBackgroundHealth(id, error = "重試失敗，請再試一次") })
        }
    }

    fun addSpaceAccount(type: String) = spaceAction { id -> accounts.addSpaceAccount(id, type) }
    fun synchronizeSpaceAccount(account: SpaceAccountEntry, authority: String) =
        spaceAction(onSuccess = showMessage) { id -> accounts.synchronizeSpaceAccount(id, account, authority) }
    fun removeSpaceAccount(account: SpaceAccountEntry) = spaceAction(onSuccess = { result ->
        when (result) {
            AccountRemovalResult.REMOVED -> showMessage("帳號已移除")
            AccountRemovalResult.CONFIRMATION_OPENED -> showMessage("已開啟帳號移除確認")
            AccountRemovalResult.REJECTED -> showMessage("帳號服務未允許移除")
        }
    }) { id -> accounts.removeSpaceAccount(id, account) }
    fun deleteBackup(id: String) = spaceAction { archives.deleteBackup(id) }
    fun setSpacePermission(packageName: String, permission: String, granted: Boolean) {
        val item = session.state.groups.firstOrNull { it.groupId == selectedSpaceId() }
            ?.apps?.firstOrNull { it.app.packageName == packageName } ?: return
        spaceAction { check(permissions.setClonePermission(item, permission, granted)) { "權限設定失敗" } }
    }
    private fun <T> spaceAction(onSuccess: (T) -> Unit = {}, action: suspend (String) -> T) {
        val id = selectedSpaceId() ?: return
        if (session.state.isSpaceActionBusy) return
        session.update { it.copy(isSpaceActionBusy = true) }
        scope.launch {
            try {
                val result = runCatching { withContext(ioDispatcher) { action(id) } }
                result.propagateCancellation()
                result.onSuccess(onSuccess).onFailure { showMessage(it.operationMessage()) }
                refreshSpaceDetails()
            } finally { session.update { it.copy(isSpaceActionBusy = false) } }
        }
    }
    fun exportToGlobalLocation(groupId: String) {
        if (session.state.isSpaceActionBusy) return
        session.update { it.copy(isSpaceActionBusy = true) }
        scope.launch {
            try {
                val result = runCatching { withContext(ioDispatcher) { archives.exportToGlobalLocation(groupId, archiveCompression()) } }
                result.propagateCancellation()
                result.onSuccess { showMessage("已建立備份"); refreshSpaceDetails() }.onFailure { showMessage(it.operationMessage()) }
            } finally { session.update { it.copy(isSpaceActionBusy = false) } }
        }
    }
    fun exportSpace(groupId: String, destination: Uri) {
        if (session.state.archiveBusyGroupId != null || session.state.isCreatingSpaceFromBackup || session.state.busyGroupId != null) return
        val compression = archiveCompression()
        session.update { it.copy(archiveBusyGroupId = groupId) }
        scope.launch {
            try {
                val result = runCatching { withContext(ioDispatcher) { archives.exportSpace(groupId, destination, compression) } }
                result.propagateCancellation()
                session.update { it.copy(archiveBusyGroupId = null) }
                result.onSuccess { showMessage("已匯出「${it.groupName}」存檔（${it.bytesWritten / (1024 * 1024)} MB）") }
                    .onFailure { showMessage("匯出存檔失敗：${it.operationMessage()}") }
                refreshHome()
            } finally { session.update { it.copy(archiveBusyGroupId = null) } }
        }
    }
    fun createSpaceFromBackup(source: Uri) {
        val shared = session.state
        if (shared.isCreatingGroup || shared.isSpaceActionBusy || shared.archiveBusyGroupId != null || shared.isCreatingSpaceFromBackup || shared.busyGroupId != null) return
        session.update { it.copy(isCreatingSpaceFromBackup = true) }
        scope.launch {
            try {
                val result = runCatching { withContext(ioDispatcher) { archives.createSpaceFromBackup(source) } }
                result.propagateCancellation()
                session.update { it.copy(isCreatingSpaceFromBackup = false) }
                result.onSuccess {
                    navigateHome()
                    showMessage("已從備份建立新的「${it.group.name}」")
                    reconcileGoogleAfterCommit()
                }.onFailure { setCreateSpaceError("從備份建立空間失敗：${it.operationMessage()}") }
                refreshHome()
            } finally { session.update { it.copy(isCreatingSpaceFromBackup = false) } }
        }
    }
}
