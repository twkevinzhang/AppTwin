package org.apptwin.permissions

/** Android permission category resolved by the platform-facing adapter. */
enum class ClonePermissionCategory {
    RUNTIME,
    SPECIAL,
    AUTOMATIC,
    UNSUPPORTED,
}

/** User action that can resolve a missing host permission. */
enum class ClonePermissionAction {
    REQUEST_RUNTIME,
    OPEN_APP_DETAILS,
    OPEN_SPECIAL_SETTINGS,
    NONE,
}

/** How a host permission is exposed to cloned apps. */
enum class ClonePermissionVirtualScope {
    CAMERA_MIC_PER_SPACE,
    HOST_SHARED,
    NOT_SUPPORTED,
}

/** A cloned app affected by a permission declared in its package manifest. */
data class ClonePermissionTarget(
    val groupId: String,
    val groupName: String,
    val packageName: String,
    val appLabel: String,
)

/** A single clone-to-permission relationship before permission-centric aggregation. */
data class ClonePermissionRequirement(
    val permission: String,
    val target: ClonePermissionTarget,
    val label: String = permission.substringAfterLast('.'),
    val category: ClonePermissionCategory,
    val virtualScope: ClonePermissionVirtualScope,
    val hostGranted: Boolean,
    /** Whether Android's runtime permission dialog is still a valid next step. */
    val canRequestRuntime: Boolean = false,
)

/** Permission-centric state rendered by settings, including all affected clones. */
data class ClonePermissionSummary(
    val permission: String,
    val label: String,
    val category: ClonePermissionCategory,
    val virtualScope: ClonePermissionVirtualScope,
    val granted: Boolean,
    val action: ClonePermissionAction,
    val affectedClones: List<ClonePermissionTarget>,
) {
    val actionRequired: Boolean
        get() = action != ClonePermissionAction.NONE
}

object ClonePermissionPolicy {
    /**
     * Aggregates clone requirements by permission while retaining the first-seen display order.
     * Permissions requiring user action are moved ahead of already-resolved/non-actionable ones.
     */
    fun aggregate(requirements: Iterable<ClonePermissionRequirement>): List<ClonePermissionSummary> =
        requirements
            .groupBy { it.permission }
            .map { (permission, matches) -> summarize(permission, matches) }
            .sortedWith(
                compareByDescending<ClonePermissionSummary> { it.actionRequired }
                    .thenBy { it.label.lowercase() },
            )

    private fun summarize(
        permission: String,
        requirements: List<ClonePermissionRequirement>,
    ): ClonePermissionSummary {
        val first = requirements.first()
        require(permission.isNotBlank()) { "Permission name must not be blank" }
        require(requirements.all { it.category == first.category }) {
            "Permission $permission has inconsistent categories"
        }
        require(requirements.all { it.virtualScope == first.virtualScope }) {
            "Permission $permission has inconsistent virtual scopes"
        }

        val granted = when (first.category) {
            ClonePermissionCategory.AUTOMATIC -> true
            ClonePermissionCategory.UNSUPPORTED -> false
            ClonePermissionCategory.RUNTIME,
            ClonePermissionCategory.SPECIAL,
            -> requirements.all(ClonePermissionRequirement::hostGranted)
        }
        val action = when {
            granted -> ClonePermissionAction.NONE
            first.category == ClonePermissionCategory.RUNTIME &&
                requirements.any(ClonePermissionRequirement::canRequestRuntime) ->
                ClonePermissionAction.REQUEST_RUNTIME
            first.category == ClonePermissionCategory.RUNTIME ->
                ClonePermissionAction.OPEN_APP_DETAILS
            first.category == ClonePermissionCategory.SPECIAL ->
                ClonePermissionAction.OPEN_SPECIAL_SETTINGS
            else -> ClonePermissionAction.NONE
        }

        return ClonePermissionSummary(
            permission = permission,
            label = first.label,
            category = first.category,
            virtualScope = first.virtualScope,
            granted = granted,
            action = action,
            affectedClones = requirements.map(ClonePermissionRequirement::target).distinct(),
        )
    }
}
