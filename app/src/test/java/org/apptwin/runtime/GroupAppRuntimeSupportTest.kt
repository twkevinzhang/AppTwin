package org.apptwin.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAppRuntimeSupportTest {
    @Test
    fun `shopee exposes its native login while other apps use their launcher`() {
        assertTrue(
            GroupAppRuntimeSupport.loginActivity(GroupAppRuntimeSupport.SHOPEE_PACKAGE)
                ?.endsWith(".LoginActivity_") == true,
        )
        assertTrue(GroupAppRuntimeSupport.loginActivity(GroupAppRuntimeSupport.LINE_PACKAGE) == null)
        assertTrue(GroupAppRuntimeSupport.loginActivity("com.google.android.youtube") == null)
    }

    @Test
    fun `only verified packages receive compatibility status`() {
        assertTrue(
            GroupAppRuntimeSupport.compatibility(GroupAppRuntimeSupport.LINE_PACKAGE) ==
                RuntimeCompatibility.VERIFIED,
        )
        assertEquals(
            RuntimeCompatibility.UNVERIFIED,
            GroupAppRuntimeSupport.compatibility("com.google.android.youtube"),
        )
        assertEquals(
            RuntimeCompatibility.UNVERIFIED,
            GroupAppRuntimeSupport.compatibility("com.google.android.apps.maps"),
        )
    }

    @Test
    fun `unknown packages are marked unverified without a launch policy`() {
        assertEquals(
            RuntimeCompatibility.UNVERIFIED,
            GroupAppRuntimeSupport.compatibility("com.example.unaccepted"),
        )
    }

}
