package org.apptwin.gms.runtime

import android.content.Context
import android.content.SharedPreferences

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

    /** Removes idempotency receipts owned by one deleted Space. */
    fun clear(groupId: String): Boolean = true
}

class SharedPreferencesGmsOperationReceiptStore internal constructor(
    private val preferences: SharedPreferences,
) : GmsOperationReceiptStore {
    constructor(context: Context) : this(
        context.getSharedPreferences("gms-runtime-operation-receipts", 0),
    )

    override fun contains(receipt: GmsOperationReceipt): Boolean =
        preferences.getBoolean(key(receipt), false)

    override fun record(receipt: GmsOperationReceipt) {
        preferences.edit().putBoolean(key(receipt), true).commit()
    }

    override fun clear(groupId: String): Boolean {
        val keys = preferences.all.keys.filter { it.belongsToGroup(groupId) }
        if (keys.isEmpty()) return true
        return preferences.edit().also { editor ->
            keys.forEach(editor::remove)
        }.commit()
    }

    private fun key(receipt: GmsOperationReceipt): String = listOf(
        receipt.groupId,
        receipt.virtualUserId.toString(),
        receipt.operationId,
        receipt.action,
        receipt.releaseId.orEmpty(),
    ).joinToString("|") { it.replace("|", "_").take(128) }

    private fun String.belongsToGroup(groupId: String): Boolean =
        startsWith("${groupId.replace("|", "_").take(128)}|")
}
