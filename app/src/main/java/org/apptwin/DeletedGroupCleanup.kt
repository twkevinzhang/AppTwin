package org.apptwin

import org.apptwin.gms.runtime.GmsOperationReceiptStore
import org.apptwin.groups.Group

/** Best-effort cleanup that runs only after the durable Space deletion has committed. */
internal class DeletedGroupCleanup(
    private val receipts: GmsOperationReceiptStore,
    private val disableShortcuts: (Group) -> ShortcutReconciliationResult,
) {
    fun execute(group: Group): DeleteGroupResult {
        val warnings = buildList {
            val receiptsCleared = runCatching { receipts.clear(group.id) }.getOrDefault(false)
            if (!receiptsCleared) add("內部 Google 服務作業紀錄未能完全清除")
            when (val shortcutResult = runCatching { disableShortcuts(group) }.getOrElse {
                ShortcutReconciliationResult.Failed(it.message ?: it.javaClass.simpleName)
            }) {
                is ShortcutReconciliationResult.Failed ->
                    add("桌面捷徑未能完全停用：${shortcutResult.reason}")
                is ShortcutReconciliationResult.Reconciled -> Unit
            }
        }
        return DeleteGroupResult.Deleted(
            group = group,
            cleanupWarning = warnings.takeIf(List<String>::isNotEmpty)?.joinToString("；"),
        )
    }
}
