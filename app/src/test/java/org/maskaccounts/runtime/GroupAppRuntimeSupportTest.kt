package org.maskaccounts.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAppRuntimeSupportTest {
    @Test
    fun `accepted GroupApps are launchable`() {
        assertTrue(GroupAppRuntimeSupport.canLaunch(GroupAppRuntimeSupport.LINE_PACKAGE))
        assertTrue(GroupAppRuntimeSupport.canLaunch(GroupAppRuntimeSupport.SHOPEE_PACKAGE))
        assertTrue(GroupAppRuntimeSupport.canLaunch(GroupAppRuntimeSupport.YOUTUBE_PACKAGE))
        assertTrue(GroupAppRuntimeSupport.canLaunch(GroupAppRuntimeSupport.MAPS_PACKAGE))
    }

    @Test
    fun `shopee exposes its native login while other apps use their launcher`() {
        assertTrue(
            GroupAppRuntimeSupport.loginActivity(GroupAppRuntimeSupport.SHOPEE_PACKAGE)
                ?.endsWith(".LoginActivity_") == true,
        )
        assertTrue(GroupAppRuntimeSupport.loginActivity(GroupAppRuntimeSupport.LINE_PACKAGE) == null)
        assertTrue(GroupAppRuntimeSupport.loginActivity(GroupAppRuntimeSupport.YOUTUBE_PACKAGE) == null)
    }

    @Test
    fun `youtube and maps declare google runtime dependencies`() {
        assertTrue(
            GroupAppRuntimeSupport.requiredPackages(GroupAppRuntimeSupport.YOUTUBE_PACKAGE) ==
                GroupAppRuntimeSupport.googlePackages,
        )
        assertTrue(
            GroupAppRuntimeSupport.requiredPackages(GroupAppRuntimeSupport.MAPS_PACKAGE) ==
                GroupAppRuntimeSupport.googlePackages,
        )
        assertTrue(
            GroupAppRuntimeSupport.requiredPackages(GroupAppRuntimeSupport.LINE_PACKAGE).isEmpty(),
        )
    }

    @Test
    fun `maps and youtube remain experimental`() {
        assertTrue(
            GroupAppRuntimeSupport.compatibility(GroupAppRuntimeSupport.YOUTUBE_PACKAGE) ==
                RuntimeCompatibility.EXPERIMENTAL,
        )
        assertTrue(
            GroupAppRuntimeSupport.compatibility(GroupAppRuntimeSupport.MAPS_PACKAGE) ==
                RuntimeCompatibility.EXPERIMENTAL,
        )
        assertTrue(
            GroupAppRuntimeSupport.compatibility(GroupAppRuntimeSupport.LINE_PACKAGE) ==
                RuntimeCompatibility.VERIFIED,
        )
    }

    @Test
    fun `unaccepted packages remain metadata only`() {
        assertFalse(GroupAppRuntimeSupport.canLaunch("com.example.unaccepted"))
    }

    @Test
    fun `legacy user zero mappings are promoted exactly once`() {
        assertTrue(
            requiresDedicatedGroupMigration(
                0,
                hasLegacyInstanceId = true,
                legacyDataMigrated = false,
            ),
        )
        assertFalse(
            requiresDedicatedGroupMigration(
                1,
                hasLegacyInstanceId = true,
                legacyDataMigrated = false,
            ),
        )
        assertFalse(
            requiresDedicatedGroupMigration(
                0,
                hasLegacyInstanceId = false,
                legacyDataMigrated = false,
            ),
        )
        assertFalse(
            requiresDedicatedGroupMigration(
                0,
                hasLegacyInstanceId = true,
                legacyDataMigrated = true,
            ),
        )
    }
}
