package org.apptwin.permissions

import org.apptwin.groups.GroupApp

/** Launch state changes are metadata updates, not a new package membership. */
internal fun sameClonePermissionMembership(expected: GroupApp, current: GroupApp): Boolean =
    expected.packageName == current.packageName && expected.addedAtEpochMillis == current.addedAtEpochMillis

/** Captured request scope; an asynchronous result must never borrow a new selection. */
data class ClonePermissionRequestIdentity(
    val groupId: String,
    val packageName: String,
    val permission: String,
    val launchKey: String,
    val internalId: Int,
    val memberAddedAtEpochMillis: Long,
) {
    fun matches(
        groupId: String,
        packageName: String,
        permission: String,
        launchKey: String,
        internalId: Int,
        memberAddedAtEpochMillis: Long,
    ): Boolean = this.groupId == groupId && this.packageName == packageName &&
        this.permission == permission && this.launchKey == launchKey && this.internalId == internalId &&
        this.memberAddedAtEpochMillis == memberAddedAtEpochMillis
}

/** Platform-exempt permissions retain their compatibility semantics. */
internal fun canApplyClonePermission(granted: Boolean, platformExempt: Boolean, hostGranted: Boolean): Boolean =
    if (platformExempt) granted else !granted || hostGranted
