package org.apptwin.gms.runtime

import android.content.Context

data class GmsOperationReceipt(
    val groupId: String,
    val virtualUserId: Int,
    val operationId: String,
    val action: String,
    val releaseId: String?,
)

fun interface GmsOperationReceiptStore {
    fun contains(receipt: GmsOperationReceipt): Boolean

    fun record(receipt: GmsOperationReceipt) {}
}

class SharedPreferencesGmsOperationReceiptStore(context: Context) : GmsOperationReceiptStore {
    private val preferences = context.getSharedPreferences("gms-runtime-operation-receipts", 0)

    override fun contains(receipt: GmsOperationReceipt): Boolean =
        preferences.getBoolean(key(receipt), false)

    override fun record(receipt: GmsOperationReceipt) {
        preferences.edit().putBoolean(key(receipt), true).commit()
    }

    private fun key(receipt: GmsOperationReceipt): String = listOf(
        receipt.groupId,
        receipt.virtualUserId.toString(),
        receipt.operationId,
        receipt.action,
        receipt.releaseId.orEmpty(),
    ).joinToString("|") { it.replace("|", "_").take(128) }
}
