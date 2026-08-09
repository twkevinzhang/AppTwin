package org.apptwin.repair

enum class RepairTargetKind { SPACE, CLONE }

data class RepairTarget(
    val kind: RepairTargetKind,
    val spaceId: String,
    val packageName: String? = null,
) {
    init {
        require(spaceId.isNotBlank()) { "spaceId must not be blank" }
        require(kind == RepairTargetKind.SPACE || !packageName.isNullOrBlank()) {
            "Clone repair requires packageName"
        }
    }
}

enum class RepairIssueCode {
    SOURCE_MISSING,
    CODE_OUT_OF_SYNC,
    RUNTIME_METADATA_MISSING,
    RUNTIME_METADATA_CORRUPT,
    PRIVATE_DATA_CORRUPT,
    ENVIRONMENT_MISSING,
}

enum class RepairAction {
    RESYNC_CODE,
    REBUILD_RUNTIME_METADATA,
    RESET_PRIVATE_DATA,
}

enum class RepairDataImpact {
    PRESERVES_PRIVATE_DATA,
    DELETES_TARGET_PRIVATE_DATA,
}

data class RepairOption(
    val action: RepairAction,
    val impact: RepairDataImpact,
    val enabled: Boolean,
    val blockingIssue: RepairIssueCode? = null,
)

data class RepairPreview(
    val target: RepairTarget,
    val issues: Set<RepairIssueCode>,
    val options: List<RepairOption>,
)

object RepairPreviewPolicy {
    fun preview(
        target: RepairTarget,
        issues: Set<RepairIssueCode>,
        sourceAvailable: Boolean,
    ): RepairPreview {
        val options = buildList {
            if (RepairIssueCode.CODE_OUT_OF_SYNC in issues ||
                RepairIssueCode.SOURCE_MISSING in issues
            ) {
                add(
                    RepairOption(
                        action = RepairAction.RESYNC_CODE,
                        impact = RepairDataImpact.PRESERVES_PRIVATE_DATA,
                        enabled = sourceAvailable,
                        blockingIssue = RepairIssueCode.SOURCE_MISSING.takeUnless {
                            sourceAvailable
                        },
                    ),
                )
            }
            if (RepairIssueCode.RUNTIME_METADATA_MISSING in issues ||
                RepairIssueCode.RUNTIME_METADATA_CORRUPT in issues
            ) {
                add(
                    RepairOption(
                        action = RepairAction.REBUILD_RUNTIME_METADATA,
                        impact = RepairDataImpact.PRESERVES_PRIVATE_DATA,
                        enabled = true,
                    ),
                )
            }
            if (target.kind == RepairTargetKind.CLONE &&
                RepairIssueCode.PRIVATE_DATA_CORRUPT in issues
            ) {
                add(
                    RepairOption(
                        action = RepairAction.RESET_PRIVATE_DATA,
                        impact = RepairDataImpact.DELETES_TARGET_PRIVATE_DATA,
                        enabled = true,
                    ),
                )
            }
        }
        return RepairPreview(target, issues, options)
    }
}

sealed interface RepairExecutionResult {
    data object Completed : RepairExecutionResult
    data object ActionUnavailable : RepairExecutionResult
    data object DestructiveConfirmationRequired : RepairExecutionResult
    data class Failed(val code: String, val error: Throwable? = null) : RepairExecutionResult
}

fun interface RepairExecutor {
    fun execute(target: RepairTarget, action: RepairAction): RepairExecutionResult
}

/** Executes only an action from the current preview and never infers destructive consent. */
class ExecuteRepairUseCase(
    private val executor: RepairExecutor,
) {
    fun execute(
        preview: RepairPreview,
        action: RepairAction,
        destructiveConfirmed: Boolean = false,
    ): RepairExecutionResult {
        val option = preview.options.firstOrNull { it.action == action }
            ?: return RepairExecutionResult.ActionUnavailable
        if (!option.enabled) return RepairExecutionResult.ActionUnavailable
        if (option.impact == RepairDataImpact.DELETES_TARGET_PRIVATE_DATA &&
            !destructiveConfirmed
        ) {
            return RepairExecutionResult.DestructiveConfirmationRequired
        }
        return executor.execute(preview.target, action)
    }
}
