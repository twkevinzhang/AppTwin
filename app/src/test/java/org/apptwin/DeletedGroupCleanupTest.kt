package org.apptwin

import org.apptwin.gms.runtime.GmsOperationReceipt
import org.apptwin.gms.runtime.GmsOperationReceiptStore
import org.apptwin.groups.EnvironmentBinding
import org.apptwin.groups.Group
import org.apptwin.groups.GroupHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeletedGroupCleanupTest {
    @Test
    fun `successful cleanup clears exact group receipts and returns deleted`() {
        val receipts = RecordingReceiptStore()
        val cleanup = DeletedGroupCleanup(receipts) {
            ShortcutReconciliationResult.Reconciled(updatedCount = 2)
        }

        val result = cleanup.execute(group()) as DeleteGroupResult.Deleted

        assertEquals(listOf(GROUP_ID), receipts.clearedGroupIds)
        assertEquals(GROUP_ID, result.group.id)
        assertNull(result.cleanupWarning)
    }

    @Test
    fun `shortcut failure is a warning after receipt cleanup and does not undo deletion`() {
        val events = mutableListOf<String>()
        val receipts = RecordingReceiptStore { groupId -> events += "receipts:$groupId" }
        val cleanup = DeletedGroupCleanup(receipts) {
            events += "shortcuts:${it.id}"
            ShortcutReconciliationResult.Failed("Launcher unavailable")
        }

        val result = cleanup.execute(group()) as DeleteGroupResult.Deleted

        assertEquals(
            listOf("receipts:$GROUP_ID", "shortcuts:$GROUP_ID"),
            events,
        )
        assertEquals(
            "桌面捷徑未能完全停用：Launcher unavailable",
            result.cleanupWarning,
        )
    }

    @Test
    fun `receipt cleanup failure is reported without hiding completed deletion`() {
        val receipts = RecordingReceiptStore(clearResult = false)
        val cleanup = DeletedGroupCleanup(receipts) {
            ShortcutReconciliationResult.Reconciled(updatedCount = 0)
        }

        val result = cleanup.execute(group()) as DeleteGroupResult.Deleted

        assertEquals(
            "內部 Google 服務作業紀錄未能完全清除",
            result.cleanupWarning,
        )
    }

    private class RecordingReceiptStore(
        private val clearResult: Boolean = true,
        private val onClear: (String) -> Unit = {},
    ) : GmsOperationReceiptStore {
        val clearedGroupIds = mutableListOf<String>()

        override fun contains(receipt: GmsOperationReceipt): Boolean = false

        override fun clear(groupId: String): Boolean {
            clearedGroupIds += groupId
            onClear(groupId)
            return clearResult
        }
    }

    private fun group() = Group(
        id = GROUP_ID,
        name = "test",
        createdAtEpochMillis = 1L,
        environmentBinding = EnvironmentBinding(1),
        health = GroupHealth.HEALTHY,
    )

    private companion object {
        const val GROUP_ID = "11111111-1111-1111-1111-111111111111"
    }
}
