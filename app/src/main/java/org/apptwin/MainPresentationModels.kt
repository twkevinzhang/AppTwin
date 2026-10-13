package org.apptwin

import org.apptwin.archive.SpaceArchiveCompression
import org.apptwin.compatibility.CompatibilityLevel
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupHealth
import org.apptwin.gms.GmsGroupProductState
import org.apptwin.permissions.ClonePermissionSummary
import org.apptwin.revision.InstalledAppEntry
import org.apptwin.spaces.CloneLifecycleState
import org.apptwin.spaces.SpaceLifecycleState

enum class MainDestination { HOME, SETTINGS, SPACE_DETAILS }

internal const val SOURCE_SYNC_PENDING = "正在同步"

data class AppItem(
    val entry: InstalledAppEntry,
    val isSynced: Boolean,
    val activeVersionCode: Long?,
    val groupCount: Int,
    val compatibility: CompatibilityLevel = CompatibilityLevel.UNTESTED,
)

data class GroupAppItem(
    val groupId: String,
    val groupName: String,
    val groupHealth: GroupHealth,
    val app: GroupApp,
    val appLabel: String,
    val versionName: String,
    val sourceInstalled: Boolean,
    val launchStatus: String,
    val lifecycle: CloneLifecycleState = CloneLifecycleState.READY,
    val cameraGranted: Boolean = false,
    val microphoneGranted: Boolean = false,
) {
    val launchKey: String = GroupAppLaunchContract.launchKey(groupId, app.packageName)
    val canAttemptLaunch: Boolean
        get() = sourceInstalled || launchStatus == SOURCE_SYNC_PENDING
}

data class GroupItem(
    val groupId: String,
    val name: String,
    val health: GroupHealth,
    val apps: List<GroupAppItem>,
    val lifecycle: SpaceLifecycleState = SpaceLifecycleState.READY,
    val gmsCompatibility: GmsGroupProductState? = null,
) {
    fun contains(packageName: String): Boolean = apps.any { it.app.packageName == packageName }
}

data class MainUiState(
    val destination: MainDestination = MainDestination.HOME,
    val selectedSpaceId: String? = null,
    val spaceDetails: SpaceDetails? = null,
    val spaceDetailsError: String? = null,
    val spaceBackgroundHealth: SpaceBackgroundHealth? = null,
    val isSpaceBackgroundRetrying: Boolean = false,
    val isLoadingSpaceDetails: Boolean = false,
    val createSpaceError: String? = null,
    val homeError: String? = null,
    val isSpaceActionBusy: Boolean = false,
    val apps: List<AppItem> = emptyList(),
    val groups: List<GroupItem> = emptyList(),
    val appPickerGroupId: String? = null,
    val showOnboarding: Boolean = false,
    val isRefreshing: Boolean = true,
    val isCreatingGroup: Boolean = false,
    val busyPackageName: String? = null,
    val busyGroupId: String? = null,
    val launchingAppKey: String? = null,
    val uninstallingAppKey: String? = null,
    val clearingStorageAppKey: String? = null,
    val clearingStorageGroupId: String? = null,
    val archiveBusyGroupId: String? = null,
    val isCreatingSpaceFromBackup: Boolean = false,
    val archiveCompression: SpaceArchiveCompression = SpaceArchiveCompression.MEDIUM,
    val globalBackupLocation: String? = null,
    val shortcutAppKey: String? = null,
    val repairingAppKey: String? = null,
    val allFilesGranted: Boolean = false,
    val downloadCount: Int = 0,
    val photoCount: Int = 0,
    val clonePermissions: List<ClonePermissionSummary> = emptyList(),
    val dataWarnings: List<String> = emptyList(),
    val diagnosticsReport: String? = null,
    val diagnosticsReportId: Long = 0,
    val pendingDeepLink: String? = null,
    val deepLinkCandidates: List<GroupAppItem> = emptyList(),
    val isResolvingDeepLink: Boolean = false,
    val message: String? = null,
    val messageId: Long = 0,
)
