package org.maskaccounts.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneRuntimeSupportTest {
    @Test
    fun `line shopee and youtube are launchable`() {
        assertTrue(CloneRuntimeSupport.canLaunch(CloneRuntimeSupport.LINE_PACKAGE))
        assertTrue(CloneRuntimeSupport.canLaunch(CloneRuntimeSupport.SHOPEE_PACKAGE))
        assertTrue(CloneRuntimeSupport.canLaunch(CloneRuntimeSupport.YOUTUBE_PACKAGE))
    }

    @Test
    fun `shopee exposes its native login while line keeps its normal launcher flow`() {
        assertTrue(
            CloneRuntimeSupport.loginActivity(CloneRuntimeSupport.SHOPEE_PACKAGE)
                ?.endsWith(".LoginActivity_") == true,
        )
        assertTrue(CloneRuntimeSupport.loginActivity(CloneRuntimeSupport.LINE_PACKAGE) == null)
        assertTrue(CloneRuntimeSupport.loginActivity(CloneRuntimeSupport.YOUTUBE_PACKAGE) == null)
    }

    @Test
    fun `youtube declares its google runtime dependencies`() {
        assertTrue(
            CloneRuntimeSupport.requiredPackages(CloneRuntimeSupport.YOUTUBE_PACKAGE) == listOf(
                CloneRuntimeSupport.GOOGLE_SERVICES_FRAMEWORK_PACKAGE,
                CloneRuntimeSupport.GOOGLE_PLAY_SERVICES_PACKAGE,
                CloneRuntimeSupport.GOOGLE_PLAY_STORE_PACKAGE,
            ),
        )
        assertTrue(CloneRuntimeSupport.requiredPackages(CloneRuntimeSupport.LINE_PACKAGE).isEmpty())
    }

    @Test
    fun `maps declares the same isolated google runtime dependencies`() {
        assertTrue(CloneRuntimeSupport.canLaunch(CloneRuntimeSupport.MAPS_PACKAGE))
        assertTrue(
            CloneRuntimeSupport.compatibility(CloneRuntimeSupport.MAPS_PACKAGE) ==
                RuntimeCompatibility.EXPERIMENTAL,
        )
        assertTrue(
            CloneRuntimeSupport.requiredPackages(CloneRuntimeSupport.MAPS_PACKAGE) ==
                CloneRuntimeSupport.requiredPackages(CloneRuntimeSupport.YOUTUBE_PACKAGE),
        )
        assertTrue(CloneRuntimeSupport.requiresDedicatedVirtualUser(CloneRuntimeSupport.MAPS_PACKAGE))
        assertFalse(CloneRuntimeSupport.requiresDedicatedVirtualUser(CloneRuntimeSupport.YOUTUBE_PACKAGE))
    }

    @Test
    fun `youtube remains explicitly experimental until it passes device acceptance`() {
        assertTrue(
            CloneRuntimeSupport.compatibility(CloneRuntimeSupport.YOUTUBE_PACKAGE) ==
                RuntimeCompatibility.EXPERIMENTAL,
        )
        assertTrue(
            CloneRuntimeSupport.compatibility(CloneRuntimeSupport.LINE_PACKAGE) ==
                RuntimeCompatibility.VERIFIED,
        )
    }

    @Test
    fun `unaccepted packages remain metadata only`() {
        assertFalse(CloneRuntimeSupport.canLaunch("com.example.unaccepted"))
    }
}
