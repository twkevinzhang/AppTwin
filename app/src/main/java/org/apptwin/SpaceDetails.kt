package org.apptwin

import org.apptwin.archive.SpaceBackupEntry

enum class AccountRemovalResult { REMOVED, CONFIRMATION_OPENED, REJECTED }

data class SpaceStorageBreakdown(val appDataBytes: Long = 0, val cacheBytes: Long = 0, val sharedFilesBytes: Long = 0, val googleDataBytes: Long = 0) {
    val totalBytes: Long get() = appDataBytes + cacheBytes + sharedFilesBytes + googleDataBytes
}
data class SpacePermissionApp(val packageName: String, val appLabel: String, val granted: Boolean)
data class SpacePermissionEntry(val permission: String, val label: String, val hostGranted: Boolean, val controllable: Boolean, val apps: List<SpacePermissionApp>, val platformExempt: Boolean = false)
data class SpaceAccountEntry(val name: String, val type: String, val syncAuthorities: List<String> = emptyList(), val syncSupported: Boolean = false, val syncStatus: String = "目前 runtime 不支援系統帳號同步")
data class SpaceAccountType(val type: String, val label: String)
data class SpaceDetails(val groupId: String, val storage: SpaceStorageBreakdown, val permissions: List<SpacePermissionEntry>, val accounts: List<SpaceAccountEntry>, val accountTypes: List<SpaceAccountType>, val backups: List<SpaceBackupEntry>, val backupLocation: String?)
