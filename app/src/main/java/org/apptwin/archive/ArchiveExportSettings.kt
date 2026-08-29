package org.apptwin.archive

import android.content.Context
import android.content.SharedPreferences
import java.util.zip.Deflater

enum class SpaceArchiveCompression(val deflaterLevel: Int) {
    HIGH(Deflater.BEST_COMPRESSION),
    MEDIUM(6),
    LOW(Deflater.BEST_SPEED),
}

interface ArchiveExportSettingsStore {
    fun load(): SpaceArchiveCompression

    fun save(compression: SpaceArchiveCompression)
}

class SharedPreferencesArchiveExportSettingsStore internal constructor(
    private val preferences: SharedPreferences,
) : ArchiveExportSettingsStore {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    override fun load(): SpaceArchiveCompression {
        val stored = runCatching { preferences.getString(COMPRESSION_KEY, null) }.getOrNull()
        return SpaceArchiveCompression.entries.firstOrNull { it.name == stored }
            ?: SpaceArchiveCompression.MEDIUM
    }

    override fun save(compression: SpaceArchiveCompression) {
        preferences.edit().putString(COMPRESSION_KEY, compression.name).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "archive-export-settings"
        const val COMPRESSION_KEY = "compression"
    }
}
