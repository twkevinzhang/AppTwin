package org.apptwin.gms.runtime

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsOperationReceiptStoreTest {
    @Test
    fun `clear removes only receipts owned by requested group`() {
        val preferences = MemorySharedPreferences()
        val store = SharedPreferencesGmsOperationReceiptStore(preferences)
        val groupAReceipt = receipt(GROUP_A, "enable")
        val secondGroupAReceipt = receipt(GROUP_A, "disable")
        val groupBReceipt = receipt(GROUP_B, "enable")
        store.record(groupAReceipt)
        store.record(secondGroupAReceipt)
        store.record(groupBReceipt)

        assertTrue(store.clear(GROUP_A))

        assertFalse(store.contains(groupAReceipt))
        assertFalse(store.contains(secondGroupAReceipt))
        assertTrue(store.contains(groupBReceipt))
    }

    @Test
    fun `clear with no matching receipt preserves other groups`() {
        val preferences = MemorySharedPreferences()
        val store = SharedPreferencesGmsOperationReceiptStore(preferences)
        val groupBReceipt = receipt(GROUP_B, "enable")
        store.record(groupBReceipt)

        assertTrue(store.clear(GROUP_A))

        assertTrue(store.contains(groupBReceipt))
    }

    private fun receipt(groupId: String, action: String) = GmsOperationReceipt(
        groupId = groupId,
        virtualUserId = 1,
        operationId = "operation-$action",
        action = action,
        releaseId = "release-1",
    )

    private class MemorySharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = key in values

        override fun edit(): SharedPreferences.Editor = Editor(values)

        override fun getString(key: String, defValue: String?): String? = error("unused")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            error("unused")
        override fun getInt(key: String, defValue: Int): Int = error("unused")
        override fun getLong(key: String, defValue: Long): Long = error("unused")
        override fun getFloat(key: String, defValue: Float): Float = error("unused")
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private class Editor(
            private val values: MutableMap<String, Any?>,
        ) : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private val removals = linkedSetOf<String>()
            private var clearAll = false

            override fun putBoolean(key: String, value: Boolean) = apply {
                updates[key] = value
                removals -= key
            }

            override fun remove(key: String) = apply {
                removals += key
                updates -= key
            }

            override fun clear() = apply { clearAll = true }

            override fun commit(): Boolean {
                if (clearAll) values.clear()
                removals.forEach(values::remove)
                values.putAll(updates)
                return true
            }

            override fun apply() {
                commit()
            }

            override fun putString(key: String, value: String?) = error("unused")
            override fun putStringSet(key: String, values: MutableSet<String>?) = error("unused")
            override fun putInt(key: String, value: Int) = error("unused")
            override fun putLong(key: String, value: Long) = error("unused")
            override fun putFloat(key: String, value: Float) = error("unused")
        }
    }

    private companion object {
        const val GROUP_A = "11111111-1111-1111-1111-111111111111"
        const val GROUP_B = "22222222-2222-2222-2222-222222222222"
    }
}
