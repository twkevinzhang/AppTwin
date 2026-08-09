package org.apptwin.repair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RepairContractTest {
    @Test
    fun `preview separates preserving repair from destructive reset`() {
        val preview = RepairPreviewPolicy.preview(
            target = cloneTarget(),
            issues = setOf(
                RepairIssueCode.CODE_OUT_OF_SYNC,
                RepairIssueCode.RUNTIME_METADATA_CORRUPT,
                RepairIssueCode.PRIVATE_DATA_CORRUPT,
            ),
            sourceAvailable = true,
        )

        assertEquals(
            listOf(
                RepairDataImpact.PRESERVES_PRIVATE_DATA,
                RepairDataImpact.PRESERVES_PRIVATE_DATA,
                RepairDataImpact.DELETES_TARGET_PRIVATE_DATA,
            ),
            preview.options.map(RepairOption::impact),
        )
    }

    @Test
    fun `missing source disables resync without hiding it`() {
        val preview = RepairPreviewPolicy.preview(
            cloneTarget(),
            setOf(RepairIssueCode.SOURCE_MISSING),
            sourceAvailable = false,
        )

        val option = preview.options.single()
        assertFalse(option.enabled)
        assertEquals(RepairIssueCode.SOURCE_MISSING, option.blockingIssue)
    }

    @Test
    fun `private data reset requires explicit destructive confirmation`() {
        val preview = RepairPreviewPolicy.preview(
            cloneTarget(),
            setOf(RepairIssueCode.PRIVATE_DATA_CORRUPT),
            sourceAvailable = true,
        )
        var executions = 0
        val useCase = ExecuteRepairUseCase { _, _ ->
            executions += 1
            RepairExecutionResult.Completed
        }

        assertEquals(
            RepairExecutionResult.DestructiveConfirmationRequired,
            useCase.execute(preview, RepairAction.RESET_PRIVATE_DATA),
        )
        assertEquals(
            RepairExecutionResult.Completed,
            useCase.execute(
                preview,
                RepairAction.RESET_PRIVATE_DATA,
                destructiveConfirmed = true,
            ),
        )
        assertEquals(1, executions)
    }

    private fun cloneTarget() = RepairTarget(
        RepairTargetKind.CLONE,
        "space-1",
        "com.example.app",
    )
}
