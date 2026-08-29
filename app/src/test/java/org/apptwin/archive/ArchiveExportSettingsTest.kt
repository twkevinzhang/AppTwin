package org.apptwin.archive

import android.content.SharedPreferences
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveExportSettingsTest {
    @Test
    fun `compression levels map to requested deflater levels and default is medium`() {
        assertEquals(9, SpaceArchiveCompression.HIGH.deflaterLevel)
        assertEquals(6, SpaceArchiveCompression.MEDIUM.deflaterLevel)
        assertEquals(1, SpaceArchiveCompression.LOW.deflaterLevel)
        assertEquals(Deflater.BEST_COMPRESSION, SpaceArchiveCompression.HIGH.deflaterLevel)
        assertEquals(Deflater.BEST_SPEED, SpaceArchiveCompression.LOW.deflaterLevel)

        assertEquals(
            SpaceArchiveCompression.MEDIUM,
            SharedPreferencesArchiveExportSettingsStore(MemorySharedPreferences()).load(),
        )
    }

    @Test
    fun `store persists every compression level`() {
        val preferences = MemorySharedPreferences()
        val store = SharedPreferencesArchiveExportSettingsStore(preferences)

        SpaceArchiveCompression.entries.forEach { compression ->
            store.save(compression)
            assertEquals(compression, SharedPreferencesArchiveExportSettingsStore(preferences).load())
        }
    }

    @Test
    fun `store falls back to medium for unknown or invalid preference values`() {
        val preferences = MemorySharedPreferences()
        val store = SharedPreferencesArchiveExportSettingsStore(preferences)

        preferences.edit().putString("compression", "ULTRA").apply()
        assertEquals(SpaceArchiveCompression.MEDIUM, store.load())

        preferences.edit().putInt("compression", 9).apply()
        assertEquals(SpaceArchiveCompression.MEDIUM, store.load())
    }

    private class MemorySharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String, defValue: String?): String? =
            when (val value = values[key]) {
                null -> defValue
                is String -> value
                else -> throw ClassCastException("Preference $key is not a String")
            }

        override fun contains(key: String): Boolean = key in values

        override fun edit(): SharedPreferences.Editor = Editor(values)

        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            error("unused")
        override fun getInt(key: String, defValue: Int): Int = error("unused")
        override fun getLong(key: String, defValue: Long): Long = error("unused")
        override fun getFloat(key: String, defValue: Float): Float = error("unused")
        override fun getBoolean(key: String, defValue: Boolean): Boolean = error("unused")
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

            override fun putString(key: String, value: String?) = apply {
                updates[key] = value
                removals -= key
            }

            override fun putInt(key: String, value: Int) = apply {
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

            override fun putStringSet(key: String, values: MutableSet<String>?) = error("unused")
            override fun putLong(key: String, value: Long) = error("unused")
            override fun putFloat(key: String, value: Float) = error("unused")
            override fun putBoolean(key: String, value: Boolean) = error("unused")
        }
    }
}
