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
    fun `only the exact device-tested package version and api are verified`() {
        assertTrue(
            GroupAppRuntimeSupport.compatibility(
                GroupAppRuntimeSupport.LINE_PACKAGE,
                150_540_375L,
                31,
            ) ==
                RuntimeCompatibility.VERIFIED,
        )
        assertEquals(
            RuntimeCompatibility.UNVERIFIED,
            GroupAppRuntimeSupport.compatibility(
                GroupAppRuntimeSupport.LINE_PACKAGE,
                150_540_376L,
                31,
            ),
        )
        assertEquals(
            RuntimeCompatibility.UNVERIFIED,
            GroupAppRuntimeSupport.compatibility(
                GroupAppRuntimeSupport.SHOPEE_PACKAGE,
                37_927L,
                32,
            ),
        )
    }

    @Test
    fun `unknown packages are marked unverified without a launch policy`() {
        assertEquals(
            RuntimeCompatibility.UNVERIFIED,
            GroupAppRuntimeSupport.compatibility("com.example.unaccepted", 1L, 31),
        )
    }

}
