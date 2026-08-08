package org.apptwin.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupAppOrigin

class GroupPlayStoreAppReconcilerTest {
    @Test
    fun `new runtime packages are added and removed Play Store packages are removed`() {
        val existing = listOf(
            app("com.example.system", GroupAppOrigin.SYSTEM_IMPORT),
            app("com.example.removed", GroupAppOrigin.PLAY_STORE),
            app("com.example.kept", GroupAppOrigin.PLAY_STORE),
        )

        val plan = GroupPlayStoreAppReconciler.plan(
            existingApps = existing,
            installedPackages = listOf(
                "com.example.system",
                "com.example.kept",
                "com.example.new",
            ),
        )

        assertEquals(listOf("com.example.new"), plan.additions)
        assertEquals(listOf("com.example.removed"), plan.removals)
    }

    @Test
    fun `missing system imports are never removed by Play Store reconciliation`() {
        val plan = GroupPlayStoreAppReconciler.plan(
            existingApps = listOf(app("com.example.system", GroupAppOrigin.SYSTEM_IMPORT)),
            installedPackages = emptyList(),
        )

        assertTrue(plan.additions.isEmpty())
        assertTrue(plan.removals.isEmpty())
    }

    @Test
    fun `runtime inventory excludes host and Google infrastructure`() {
        val host = "org.apptwin"

        assertFalse(GroupVirtualPackageInventory.shouldExpose(host, host))
        GroupAppRuntimeSupport.googlePackages.forEach { packageName ->
            assertFalse(GroupVirtualPackageInventory.shouldExpose(packageName, host))
        }
        assertTrue(GroupVirtualPackageInventory.shouldExpose("com.example.downloaded", host))
    }

    private fun app(packageName: String, origin: GroupAppOrigin) = GroupApp(
        packageName = packageName,
        addedAtEpochMillis = 1,
        origin = origin,
    )
}
