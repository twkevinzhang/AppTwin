package org.apptwin.ui

import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.ipc.VPackageManager
import com.lody.virtual.helper.compat.PlatformPermissionCompatibility
import org.apptwin.MainActivity
import org.apptwin.MainViewModel
import org.apptwin.groups.FileGroupStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in production UI read-only acceptance against an existing space. */
@RunWith(AndroidJUnit4::class)
class PlatformPermissionCompatibilityUiE2eTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun existingSpaceMediaPermissionsAreAutomaticWithoutInvalidSwitches() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("opt-in required", args.getString("platformPermissionCompatUi") == "1")
        val id = requireNotNull(args.getString("groupId"))
        val userId = requireNotNull(args.getString("userId")).toInt()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val group = requireNotNull(FileGroupStore(context).find(id))
        assertEquals(userId, group.environmentBinding?.internalId)
        val permissions = listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO")
        permissions.forEach { assertTrue(PlatformPermissionCompatibility.isUnavailableOnPlatform(context.packageManager, it)) }
        var model: MainViewModel? = null
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
            model!!.openSpaceDetails(id)
        }
        compose.waitUntil(30_000) {
            model?.uiState?.let { it.selectedSpaceId == id && !it.isLoadingSpaceDetails && it.spaceDetails != null } == true
        }
        val details = model!!.uiState.spaceDetails!!
        assertNull(model!!.uiState.spaceDetailsError)
        val vpm = VPackageManager.get()
        permissions.forEach { permission ->
            val entry = details.permissions.single { it.permission == permission }
            assertTrue(entry.platformExempt)
            assertFalse(entry.controllable)
            assertTrue(entry.hostGranted)
            assertTrue("existing space must declare media permissions", entry.apps.isNotEmpty())
            entry.apps.forEach { app ->
                val requested = vpm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS, userId).requestedPermissions.orEmpty()
                assertTrue(permission in requested)
                assertTrue(app.granted)
                assertEquals(PackageManager.PERMISSION_GRANTED, vpm.checkPermission(permission, app.packageName, userId))
            }
        }
        compose.onNodeWithTag("space-details").performScrollToNode(hasText("按權限管理 App"))
        compose.onNodeWithText("按權限管理 App").performClick()
        permissions.forEach { permission ->
            compose.onNodeWithTag("space-details").performScrollToNode(hasTestTag("space-permission-group-$permission"))
            val card = compose.onNodeWithTag("space-permission-group-$permission")
            card.assertIsDisplayed()
            card.assert(hasAnyDescendant(hasText("自動允許（此版本免設定）")))
            card.assert(!hasAnyDescendant(isToggleable()))
        }
        compose.onNodeWithText("權限設定失敗", substring = true).assertDoesNotExist()
    }
}
