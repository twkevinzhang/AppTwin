package org.apptwin

import android.app.Application
import android.content.Context
import org.apptwin.archive.ArchiveExportSettingsStore
import org.apptwin.archive.SpaceArchiveCompression

internal interface OnboardingStore {
    fun isCompleted(): Boolean
    fun markCompleted()
}

internal class AndroidOnboardingStore(application: Application) : OnboardingStore {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun isCompleted(): Boolean = preferences.getBoolean(COMPLETED_KEY, false)

    override fun markCompleted() {
        preferences.edit().putBoolean(COMPLETED_KEY, true).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "product-onboarding"
        const val COMPLETED_KEY = "completed"
    }
}

internal object CompletedOnboardingStore : OnboardingStore {
    override fun isCompleted(): Boolean = true
    override fun markCompleted() = Unit
}

internal object DefaultArchiveExportSettingsStore : ArchiveExportSettingsStore {
    override fun load(): SpaceArchiveCompression = SpaceArchiveCompression.MEDIUM
    override fun save(compression: SpaceArchiveCompression) = Unit
}
