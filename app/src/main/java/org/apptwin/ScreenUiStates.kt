package org.apptwin

import org.apptwin.archive.SpaceArchiveCompression
import org.apptwin.permissions.ClonePermissionSummary

/** Presentation state belongs to its screen; catalog and transaction state have one session owner. */
data class HomeUiState(
    val groups: List<GroupItem> = emptyList(),
    val isRefreshing: Boolean = true,
    val dataWarnings: List<String> = emptyList(),
    val error: String? = null,
    val createSpaceError: String? = null,
    val isCreatingGroup: Boolean = false,
)

data class PickerUiState(
    val appPickerGroupId: String? = null,
    val apps: List<AppItem> = emptyList(),
    val groups: List<GroupItem> = emptyList(),
    val isRefreshing: Boolean = true,
)

data class SpaceDetailsUiState(
    val selectedSpaceId: String? = null,
    val spaceDetails: SpaceDetails? = null,
    val spaceDetailsError: String? = null,
    val spaceBackgroundHealth: SpaceBackgroundHealth? = null,
    val isSpaceBackgroundRetrying: Boolean = false,
    val isLoadingSpaceDetails: Boolean = false,
    val isSpaceActionBusy: Boolean = false,
)

data class SettingsUiState(
    val archiveCompression: SpaceArchiveCompression = SpaceArchiveCompression.MEDIUM,
    val globalBackupLocation: String? = null,
    val allFilesGranted: Boolean = false,
    val downloadCount: Int = 0,
    val photoCount: Int = 0,
    val clonePermissions: List<ClonePermissionSummary> = emptyList(),
    val diagnosticsReport: String? = null,
    val diagnosticsReportId: Long = 0,
)

/** Shared mutation lanes, read by all screens rather than copied into each writable state. */
data class MainSessionUiState(
    val isCreatingGroup: Boolean = false,
    val isSpaceActionBusy: Boolean = false,
    val apps: List<AppItem> = emptyList(),
    val groups: List<GroupItem> = emptyList(),
    val busyPackageName: String? = null,
    val busyGroupId: String? = null,
    val launchingAppKey: String? = null,
    val uninstallingAppKey: String? = null,
    val clearingStorageAppKey: String? = null,
    val clearingStorageGroupId: String? = null,
    val archiveBusyGroupId: String? = null,
    val isCreatingSpaceFromBackup: Boolean = false,
    val shortcutAppKey: String? = null,
    val repairingAppKey: String? = null,
)

/** Saved navigation and transient shell events are owned by MainViewModel. */
data class MainNavigationUiState(
    val destination: MainDestination = MainDestination.HOME,
    val selectedSpaceId: String? = null,
    val showOnboarding: Boolean = false,
    val pendingDeepLink: String? = null,
    val deepLinkCandidates: List<GroupAppItem> = emptyList(),
    val isResolvingDeepLink: Boolean = false,
    val message: String? = null,
    val messageId: Long = 0,
)
