package org.apptwin

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import org.apptwin.archive.ArchiveExportSettingsStore
import org.apptwin.archive.SpaceArchiveCompression
import org.apptwin.runtime.BackgroundServiceRepository
import org.apptwin.runtime.BackgroundServiceState
import org.apptwin.permissions.ClonePermissionRequestIdentity
import org.apptwin.permissions.sameClonePermissionMembership

/** Owns navigation and shell events; screen work is delegated to scoped state owners. */
class MainViewModel internal constructor(
    private val savedStateHandle: SavedStateHandle,
    private val operations: MainOperations,
    private val ioDispatcher: CoroutineDispatcher,
    private val onboardingStore: OnboardingStore = CompletedOnboardingStore,
    archiveExportSettingsStore: ArchiveExportSettingsStore = DefaultArchiveExportSettingsStore,
    launchDispatcher: CoroutineDispatcher = ioDispatcher,
    private val backgroundServiceRepository: BackgroundServiceRepository? = null,
) : ViewModel() {
    private val fallbackBackgroundState = MutableStateFlow(BackgroundServiceState()).asStateFlow()
    val backgroundServiceState = backgroundServiceRepository?.state ?: fallbackBackgroundState
    fun setBackgroundServiceEnabled(enabled: Boolean) { backgroundServiceRepository?.setEnabled(enabled) }
    fun refreshBackgroundService() { backgroundServiceRepository?.refresh() }
    /** Re-read platform policy after Settings, dialogs, or guest activities return. */
    fun onHostResumed() {
        refreshBackgroundService()
        settings.refreshBackupLocation()
        refresh()
    }

    private var permissionRequestGeneration = savedStateHandle.get<Long>(PERMISSION_GENERATION_KEY)
        ?: savedStateHandle.get<ArrayList<String>>(PERMISSION_REQUEST_KEY)?.getOrNull(6)?.toLongOrNull() ?: 0L
    private fun nextPermissionGeneration(): Long {
        permissionRequestGeneration++
        savedStateHandle[PERMISSION_GENERATION_KEY] = permissionRequestGeneration
        return permissionRequestGeneration
    }

    fun prepareClonePermissionRequest(item: GroupAppItem, permission: String, onPrepared: () -> Unit) {
        if (savedStateHandle.get<ArrayList<String>>(PERMISSION_REQUEST_KEY) != null) return
        val generation = nextPermissionGeneration()
        viewModelScope.launch {
            val group = operationResult { withContext(ioDispatcher) { operations.findGroup(item.groupId) } }.getOrNull()
                ?: return@launch
            if (generation != permissionRequestGeneration) return@launch
            val binding = group.environmentBinding?.internalId ?: return@launch
            val member = group.apps.firstOrNull { sameClonePermissionMembership(item.app, it) } ?: return@launch
            if (savedStateHandle.get<ArrayList<String>>(PERMISSION_REQUEST_KEY) != null) return@launch
            savedStateHandle[PERMISSION_REQUEST_KEY] = arrayListOf(item.groupId, item.app.packageName, permission, item.launchKey, binding.toString(), member.addedAtEpochMillis.toString(), generation.toString())
            onPrepared()
        }
    }
    fun completeClonePermissionRequest(permission: String?, granted: Boolean, requestTicket: Long? = pendingClonePermissionTicket()) {
        if (requestTicket == null || requestTicket != pendingClonePermissionTicket()) return
        val fields = savedStateHandle.remove<ArrayList<String>>(PERMISSION_REQUEST_KEY) ?: return
        nextPermissionGeneration()
        if (fields.size != 7 || (permission != null && permission != fields[2])) return
        val internalId = fields[4].toIntOrNull() ?: return
        val identity = ClonePermissionRequestIdentity(fields[0], fields[1], fields[2], fields[3], internalId, fields[5].toLongOrNull() ?: return)
        viewModelScope.launch {
            val group = operationResult { withContext(ioDispatcher) { operations.findGroup(identity.groupId) } }.getOrNull()
                ?: return@launch
            val item = session.state.groups.firstOrNull { it.groupId == identity.groupId }?.apps
                ?.firstOrNull { it.app.packageName == identity.packageName } ?: return@launch
            if (group.apps.none { it.packageName == identity.packageName } ||
                !identity.matches(item.groupId, item.app.packageName, identity.permission, item.launchKey,
                    group.environmentBinding?.internalId ?: -1, item.app.addedAtEpochMillis)) return@launch
            val updated = operationResult { withContext(ioDispatcher) {
                operations.setClonePermission(item, identity.permission, granted, identity)
            } }.getOrElse { false }
            if (!updated) showMessage("無法更新分身權限")
            refresh()
        }
    }
    fun pendingClonePermissionName(): String? = savedStateHandle.get<ArrayList<String>>(PERMISSION_REQUEST_KEY)?.getOrNull(2)
    fun pendingClonePermissionTicket(): Long? = savedStateHandle.get<ArrayList<String>>(PERMISSION_REQUEST_KEY)?.getOrNull(6)?.toLongOrNull()
    fun cancelClonePermissionRequest() {
        nextPermissionGeneration()
        savedStateHandle.remove<ArrayList<String>>(PERMISSION_REQUEST_KEY)
    }

    private var resolvingDeepLinkUri: String? = null
    private var resolvedDeepLinkIdentities: Set<Pair<String, String>>? = null
    private var deepLinkGeneration = 0L
    private val mutableNavigation = MutableStateFlow(
        MainNavigationUiState(
            destination = savedStateHandle.get<String>(DESTINATION_KEY)
                ?.let(::destinationFromSavedState) ?: MainDestination.HOME,
            selectedSpaceId = savedStateHandle.get<String>(SELECTED_SPACE_KEY),
            showOnboarding = !onboardingStore.isCompleted(),
        ),
    )
    val navigationState = mutableNavigation.asStateFlow()
    private val navigation get() = mutableNavigation.value
    private val session = MainSessionCoordinator(
        scope = viewModelScope,
        ioDispatcher = ioDispatcher,
        launchDispatcher = launchDispatcher,
        cloneOperations = operations,
        backgroundOperations = operations,
        showMessage = ::showMessage,
        appendMessage = ::appendMessage,
    )
    private val settings: SettingsStateOwner = SettingsStateOwner(
        scope = viewModelScope,
        ioDispatcher = ioDispatcher,
        archives = operations,
        diagnostics = operations,
        archiveExportSettingsStore = archiveExportSettingsStore,
        showMessage = ::showMessage,
        refreshDetails = ::refreshSpaceDetails,
    )
    private val home: HomeStateOwner = HomeStateOwner(
        scope = viewModelScope,
        spaces = operations,
        clones = operations,
        permissions = operations,
        snapshots = operations,
        background = operations,
        ioDispatcher = ioDispatcher,
        session = session,
        showMessage = ::showMessage,
        validatePickerSelection = { picker.validateSelection(it) },
        publishSettingsSnapshot = settings::acceptSnapshot,
        presentResolvedDeepLink = ::presentResolvedDeepLink,
        onGroupDeleted = ::onGroupDeleted,
        refreshDetailsIfVisible = {
            if (navigation.destination == MainDestination.SPACE_DETAILS && navigation.selectedSpaceId != null) {
                refreshSpaceDetails()
            }
        },
    )
    private val picker: PickerStateOwner = PickerStateOwner(
        scope = viewModelScope,
        spaces = operations,
        ioDispatcher = ioDispatcher,
        session = session,
        initialGroupId = savedStateHandle.get<String>(APP_PICKER_GROUP_KEY),
        onSelectionChanged = ::onPickerSelectionChanged,
        showMessage = ::showMessage,
        refresh = home::refresh,
        homeStates = home.states,
        isRefreshing = { home.state.isRefreshing },
    )
    private val details: SpaceDetailsStateOwner = SpaceDetailsStateOwner(
        scope = viewModelScope,
        ioDispatcher = ioDispatcher,
        snapshots = operations,
        background = operations,
        accounts = operations,
        archives = operations,
        permissions = operations,
        session = session,
        selectedSpaceId = { navigation.selectedSpaceId },
        isDetailsVisible = { navigation.destination == MainDestination.SPACE_DETAILS },
        showMessage = ::showMessage,
        refreshHome = home::refresh,
        navigateHome = { navigate(MainDestination.HOME) },
        clearSelectedSpace = ::clearSelectedSpace,
        archiveCompression = { settings.state.archiveCompression },
        setCreateSpaceError = { it?.let(home::showCreateSpaceError) ?: home.dismissCreateSpaceError() },
        reconcileGoogleAfterCommit = home::reconcileGoogleAfterCommit,
    )
    val homeState = home.states
    val pickerState = picker.states
    val settingsState = settings.states
    val sessionState = session.states
    val spaceDetailsState = combine(details.states, navigationState) { state, navigation ->
        state.copy(selectedSpaceId = navigation.selectedSpaceId)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, details.state)

    /** Compatibility projection for existing callers; it has no writable aggregate backing state. */
    val uiState: MainUiState get() {
        val h = home.state
        val p = picker.state
        val d = details.state
        val s = settings.state
        val shared = session.state
        val nav = navigation
        return MainUiState(
            destination = nav.destination,
            selectedSpaceId = nav.selectedSpaceId,
            spaceDetails = d.spaceDetails,
            spaceDetailsError = d.spaceDetailsError,
            spaceBackgroundHealth = d.spaceBackgroundHealth,
            isSpaceBackgroundRetrying = d.isSpaceBackgroundRetrying,
            isLoadingSpaceDetails = d.isLoadingSpaceDetails,
            createSpaceError = h.createSpaceError,
            homeError = h.error,
            isSpaceActionBusy = shared.isSpaceActionBusy,
            apps = shared.apps,
            groups = shared.groups,
            appPickerGroupId = p.appPickerGroupId,
            showOnboarding = nav.showOnboarding,
            isRefreshing = h.isRefreshing,
            isCreatingGroup = shared.isCreatingGroup,
            busyPackageName = shared.busyPackageName,
            busyGroupId = shared.busyGroupId,
            launchingAppKey = shared.launchingAppKey,
            uninstallingAppKey = shared.uninstallingAppKey,
            clearingStorageAppKey = shared.clearingStorageAppKey,
            clearingStorageGroupId = shared.clearingStorageGroupId,
            archiveBusyGroupId = shared.archiveBusyGroupId,
            isCreatingSpaceFromBackup = shared.isCreatingSpaceFromBackup,
            archiveCompression = s.archiveCompression,
            globalBackupLocation = s.globalBackupLocation,
            shortcutAppKey = shared.shortcutAppKey,
            repairingAppKey = shared.repairingAppKey,
            allFilesGranted = s.allFilesGranted,
            downloadCount = s.downloadCount,
            photoCount = s.photoCount,
            clonePermissions = s.clonePermissions,
            dataWarnings = h.dataWarnings,
            diagnosticsReport = s.diagnosticsReport,
            diagnosticsReportId = s.diagnosticsReportId,
            pendingDeepLink = nav.pendingDeepLink,
            deepLinkCandidates = nav.deepLinkCandidates,
            isResolvingDeepLink = nav.isResolvingDeepLink,
            message = nav.message,
            messageId = nav.messageId,
        )
    }

    init {
        if (navigation.destination == MainDestination.SPACE_DETAILS) {
            if (navigation.selectedSpaceId == null) navigate(MainDestination.HOME) else refreshSpaceDetails()
        }
        home.start()
    }

    fun closeSpaceDetails() = navigate(MainDestination.HOME)
    fun dismissCreateSpaceError() = home.dismissCreateSpaceError()
    fun openSpaceDetails(groupId: String) {
        details.invalidateSelection()
        savedStateHandle[SELECTED_SPACE_KEY] = groupId
        savedStateHandle[DESTINATION_KEY] = MainDestination.SPACE_DETAILS.name
        mutableNavigation.update { it.copy(selectedSpaceId = groupId, destination = MainDestination.SPACE_DETAILS) }
        refreshSpaceDetails()
    }
    fun refreshSpaceDetails() = details.refreshSpaceDetails()
    fun refreshSpaceBackgroundHealth() = details.refreshSpaceBackgroundHealth()
    fun stopSpaceBackgroundObservation() = details.stopSpaceBackgroundObservation()
    fun retrySpaceBackground() = details.retrySpaceBackground()
    fun addSpaceAccount(type: String) = details.addSpaceAccount(type)
    fun synchronizeSpaceAccount(account: SpaceAccountEntry, authority: String) = details.synchronizeSpaceAccount(account, authority)
    fun removeSpaceAccount(account: SpaceAccountEntry) = details.removeSpaceAccount(account)
    fun deleteBackup(id: String) = details.deleteBackup(id)
    fun setGlobalBackupLocation(uri: Uri) = settings.setGlobalBackupLocation(uri)
    fun exportToGlobalLocation(groupId: String) = details.exportToGlobalLocation(groupId)
    fun setSpacePermission(packageName: String, permission: String, granted: Boolean) = details.setSpacePermission(packageName, permission, granted)
    fun exportSpace(groupId: String, destination: Uri) = details.exportSpace(groupId, destination)
    fun createSpaceFromBackup(source: Uri) = details.createSpaceFromBackup(source)
    fun setArchiveCompression(compression: SpaceArchiveCompression) = settings.setArchiveCompression(compression)
    fun exportDiagnostics() = settings.exportDiagnostics()
    fun consumeDiagnostics(reportId: Long) = settings.consumeDiagnostics(reportId)
    fun refresh() = home.refresh()
    fun createGroup(name: String) = home.createGroup(name)
    fun renameGroup(groupId: String, name: String) = home.renameGroup(groupId, name)
    fun deleteGroup(groupId: String) = home.deleteGroup(groupId)
    fun uninstallGroupApp(item: GroupAppItem) = home.uninstallGroupApp(item)
    fun clearGroupAppStorage(item: GroupAppItem) = home.clearGroupAppStorage(item)
    fun clearAllGroupAppData(groupId: String) = home.clearAllGroupAppData(groupId)
    fun createShortcut(item: GroupAppItem) = home.createShortcut(item)
    fun repairClone(item: GroupAppItem) = home.repairClone(item)
    fun setClonePermission(item: GroupAppItem, permission: String, granted: Boolean) = home.setClonePermission(item, permission, granted)
    fun launchGroupApp(item: GroupAppItem) = session.launchGroupApp(item, home::refresh)
    fun launchGroupApp(groupId: String, packageName: String) = session.launchGroupApp(
        groupId, packageName, navigation.showOnboarding, home.state.isRefreshing, home::refresh,
    )
    fun launchFirst(packageName: String) = session.launchFirst(packageName, home::refresh)
    fun selectApp(app: AppItem) = picker.selectApp(app)
    fun openAppPicker(groupId: String) = picker.openAppPicker(groupId)
    fun closeAppPicker() = picker.close()

    fun navigate(destination: MainDestination) {
        details.invalidateSelection()
        if (destination == MainDestination.SETTINGS) settings.refreshBackupLocation()
        picker.close()
        savedStateHandle[DESTINATION_KEY] = destination.name
        mutableNavigation.update { it.copy(destination = destination) }
    }
    fun completeOnboarding() {
        if (!navigation.showOnboarding) return
        onboardingStore.markCompleted()
        mutableNavigation.update { it.copy(showOnboarding = false) }
        if (session.pendingLaunch != null) refresh()
    }
    private fun onPickerSelectionChanged(groupId: String?) {
        savedStateHandle[APP_PICKER_GROUP_KEY] = groupId
        if (groupId != null) {
            savedStateHandle[DESTINATION_KEY] = MainDestination.HOME.name
            mutableNavigation.update { it.copy(destination = MainDestination.HOME) }
        }
    }
    private fun clearSelectedSpace() {
        savedStateHandle.remove<String>(SELECTED_SPACE_KEY)
        mutableNavigation.update { it.copy(selectedSpaceId = null) }
    }
    private fun onGroupDeleted(groupId: String) {
        if (navigation.selectedSpaceId == groupId) {
            clearSelectedSpace()
            navigate(MainDestination.HOME)
        }
        if (picker.state.appPickerGroupId == groupId) picker.close()
    }
    fun consumeMessage(messageId: Long) {
        mutableNavigation.update { if (it.messageId == messageId) it.copy(message = null) else it }
    }
    private fun showMessage(message: String) {
        mutableNavigation.update { it.copy(message = message, messageId = it.messageId + 1) }
    }
    private fun appendMessage(message: String) {
        mutableNavigation.update {
            it.copy(message = listOfNotNull(it.message, message).joinToString("\n"), messageId = it.messageId + 1)
        }
    }

    fun openDeepLink(uri: String) {
        if (navigation.pendingDeepLink != null || navigation.isResolvingDeepLink) return
        val generation = ++deepLinkGeneration
        resolvingDeepLinkUri = uri
        mutableNavigation.update { it.copy(isResolvingDeepLink = true) }
        viewModelScope.launch {
            val result = operationResult { withContext(ioDispatcher) { operations.resolveDeepLink(uri) } }
            if (generation != deepLinkGeneration) return@launch
            result.onSuccess { identities ->
                resolvedDeepLinkIdentities = identities.toSet()
                presentResolvedDeepLink(session.state.groups, home.state.isRefreshing)
            }.onFailure { error ->
                resolvingDeepLinkUri = null
                resolvedDeepLinkIdentities = null
                mutableNavigation.update { it.copy(isResolvingDeepLink = false) }
                showMessage("無法解析連結：${error.operationMessage()}")
            }
        }
    }
    fun closeDeepLink() {
        ++deepLinkGeneration
        resolvingDeepLinkUri = null
        resolvedDeepLinkIdentities = null
        mutableNavigation.update { it.copy(pendingDeepLink = null, deepLinkCandidates = emptyList(), isResolvingDeepLink = false) }
    }
    fun launchDeepLink(item: GroupAppItem) {
        val uri = navigation.pendingDeepLink ?: return
        session.launchDeepLink(item, uri, beforeLaunch = ::closeDeepLink, refresh = home::refresh)
    }
    private fun presentResolvedDeepLink(groups: List<GroupItem>, waitForRefresh: Boolean) {
        val uri = resolvingDeepLinkUri ?: return
        val identitySet = resolvedDeepLinkIdentities ?: return
        if (waitForRefresh) return
        val candidates = groups.asSequence().flatMap { it.apps.asSequence() }
            .filter { (it.groupId to it.app.packageName) in identitySet }.toList()
        resolvingDeepLinkUri = null
        resolvedDeepLinkIdentities = null
        if (candidates.isEmpty()) {
            mutableNavigation.update { it.copy(isResolvingDeepLink = false) }
            showMessage("沒有可開啟此連結的分身 App；請先啟動並完成 App 設定")
        } else {
            mutableNavigation.update { it.copy(pendingDeepLink = uri, deepLinkCandidates = candidates, isResolvingDeepLink = false) }
        }
    }
    private companion object {
        const val PERMISSION_GENERATION_KEY = "main.permissionRequestGeneration"
        const val PERMISSION_REQUEST_KEY = "main.pendingClonePermission"
        const val SELECTED_SPACE_KEY = "main.selectedSpace"
        const val DESTINATION_KEY = "main.destination"
        const val APP_PICKER_GROUP_KEY = "main.appPickerGroupId"
        fun destinationFromSavedState(value: String): MainDestination? = runCatching { MainDestination.valueOf(value) }.getOrNull()
    }
}
