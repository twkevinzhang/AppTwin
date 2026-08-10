package org.apptwin.runtime

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.apptwin.AndroidMainOperations
import org.apptwin.permissions.ClonePermissionAction
import org.apptwin.permissions.ClonePermissionCategory
import org.apptwin.permissions.ClonePermissionVirtualScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClonePermissionAggregationE2eTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context
        get() = instrumentation.targetContext

    @Test
    fun cloneManifestPermissionsFollowHostGrantState() = runBlocking {
        requireFixtureInstalled()
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.CAMERA),
        )
        val operations = AndroidMainOperations(context.applicationContext as Application)
        val group = operations.createGroup("權限驗收")
        try {
            operations.addAppToGroup(group.id, FIXTURE_PACKAGE)
            val denied = operations.refreshSnapshot().clonePermissions
                .single { it.permission == Manifest.permission.CAMERA }
            assertFalse(denied.granted)
            assertEquals(ClonePermissionCategory.RUNTIME, denied.category)
            assertEquals(ClonePermissionAction.REQUEST_RUNTIME, denied.action)
            assertEquals(
                ClonePermissionVirtualScope.CAMERA_MIC_PER_SPACE,
                denied.virtualScope,
            )
            assertTrue(denied.affectedClones.any { it.groupId == group.id })

            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.CAMERA,
            )

            val granted = operations.refreshSnapshot().clonePermissions
                .single { it.permission == Manifest.permission.CAMERA }
            assertTrue(granted.granted)
            assertEquals(ClonePermissionAction.NONE, granted.action)
            assertTrue(
                operations.refreshSnapshot().clonePermissions
                    .any { it.permission == Manifest.permission.RECORD_AUDIO },
            )
        } finally {
            operations.deleteGroup(group.id)
        }
    }

    private fun requireFixtureInstalled() {
        runCatching { context.packageManager.getPackageInfo(FIXTURE_PACKAGE, 0) }
            .getOrElse {
                throw IllegalStateException(
                    "Install $FIXTURE_PACKAGE before running this acceptance test",
                    it,
                )
            }
    }

    private companion object {
        const val FIXTURE_PACKAGE = "org.apptwin.fixture"
    }
}
