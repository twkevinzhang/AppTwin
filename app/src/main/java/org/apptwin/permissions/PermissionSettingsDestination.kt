package org.apptwin.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.provider.Settings

internal data class PermissionSettingsDestination(
    val action: String,
    val packageScoped: Boolean = true,
)

@SuppressLint("InlinedApi")
internal fun permissionSettingsDestination(permission: String): PermissionSettingsDestination =
    when (permission) {
        Manifest.permission.MANAGE_EXTERNAL_STORAGE -> PermissionSettingsDestination(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        )
        Manifest.permission.SYSTEM_ALERT_WINDOW -> PermissionSettingsDestination(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        )
        Manifest.permission.WRITE_SETTINGS -> PermissionSettingsDestination(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
        )
        Manifest.permission.REQUEST_INSTALL_PACKAGES -> PermissionSettingsDestination(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        )
        Manifest.permission.SCHEDULE_EXACT_ALARM,
        Manifest.permission.USE_EXACT_ALARM,
        -> PermissionSettingsDestination(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        Manifest.permission.PACKAGE_USAGE_STATS -> PermissionSettingsDestination(
            Settings.ACTION_USAGE_ACCESS_SETTINGS,
            packageScoped = false,
        )
        else -> PermissionSettingsDestination(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }
