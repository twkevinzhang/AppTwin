package org.apptwin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apptwin.groups.GroupHealth

import kotlinx.coroutines.CoroutineScope

internal class PickerStateOwner(
    private val scope: CoroutineScope,
    private val spaces: SpaceOperations,
    private val ioDispatcher: CoroutineDispatcher,
    private val session: MainSessionCoordinator,
    initialGroupId: String?,
    private val onSelectionChanged: (String?) -> Unit,
    private val showMessage: (String) -> Unit,
    private val refresh: () -> Unit,
    private val homeStates: StateFlow<HomeUiState>,
    private val isRefreshing: () -> Boolean,
) {
    private val mutableSelection = MutableStateFlow(initialGroupId)
    private var selectedGroupId: String?
        get() = mutableSelection.value
        set(value) { mutableSelection.value = value }
    private var requestGeneration = 0L
    val state: PickerUiState get() = PickerUiState(
        appPickerGroupId = selectedGroupId,
        apps = session.state.apps,
        groups = session.state.groups,
        isRefreshing = isRefreshing(),
    )
    val states = combine(mutableSelection, session.states, homeStates) { _, _, _ -> state }
        .stateIn(scope, SharingStarted.Eagerly, state)
    fun invalidateRequests() { ++requestGeneration }
    fun openAppPicker(groupId: String) {
        if (session.state.uninstallingAppKey != null) {
            showMessage("App 正在解除安裝，請稍候")
            return
        }
        val capturedGeneration = ++requestGeneration
        scope.launch {
            val lookup = operationResult {
                withContext(ioDispatcher) { spaces.findGroup(groupId) }
            }
            if (capturedGeneration != requestGeneration) return@launch
            val group = lookup.getOrElse { error ->
                showMessage("讀取群組失敗：${error.operationMessage()}")
                return@launch
            }
            when {
                group == null -> showMessage("找不到這個群組")
                group.health != GroupHealth.HEALTHY -> showMessage("這個群組目前無法加入 App")
                else -> {
                    onSelectionChanged(groupId)
                    selectedGroupId = groupId
                }
            }
        }
    }

    fun close() {
        requestGeneration += 1
        onSelectionChanged(null)
        selectedGroupId = null
    }

    fun validateSelection(groupItems: List<GroupItem>) {
        val restoredPickerId = selectedGroupId?.takeIf { selectedId ->
            groupItems.any { it.groupId == selectedId }
        }
        if (restoredPickerId != selectedGroupId) {
            onSelectionChanged(null)
        }
        selectedGroupId = restoredPickerId
    }

    fun selectApp(app: AppItem) {
        val groupId = selectedGroupId ?: return
        session.executeAddAndLaunch(groupId, app, onAdded = ::close, refresh = refresh)
    }

}
