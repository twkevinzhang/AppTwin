package org.apptwin.permissions

import android.Manifest
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionSettingsDestinationTest {
    @Test
    fun `special permissions map to their app-scoped settings screens`() {
        assertEquals(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            permissionSettingsDestination(Manifest.permission.MANAGE_EXTERNAL_STORAGE).action,
        )
        assertEquals(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            permissionSettingsDestination(Manifest.permission.SYSTEM_ALERT_WINDOW).action,
        )
        assertEquals(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            permissionSettingsDestination(Manifest.permission.WRITE_SETTINGS).action,
        )
        assertEquals(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            permissionSettingsDestination(Manifest.permission.REQUEST_INSTALL_PACKAGES).action,
        )
        assertEquals(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            permissionSettingsDestination(Manifest.permission.SCHEDULE_EXACT_ALARM).action,
        )
        assertEquals(
            false,
            permissionSettingsDestination(Manifest.permission.PACKAGE_USAGE_STATS).packageScoped,
        )
    }

    @Test
    fun `runtime permission falls back to AppTwin application details`() {
        assertEquals(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            permissionSettingsDestination(Manifest.permission.CAMERA).action,
        )
    }
}
