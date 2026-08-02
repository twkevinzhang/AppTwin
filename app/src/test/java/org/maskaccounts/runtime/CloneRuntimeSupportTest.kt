package org.maskaccounts.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloneRuntimeSupportTest {
    @Test
    fun `line and shopee are launchable`() {
        assertTrue(CloneRuntimeSupport.canLaunch(CloneRuntimeSupport.LINE_PACKAGE))
        assertTrue(CloneRuntimeSupport.canLaunch(CloneRuntimeSupport.SHOPEE_PACKAGE))
    }

    @Test
    fun `shopee exposes its native login while line keeps its normal launcher flow`() {
        assertTrue(
            CloneRuntimeSupport.loginActivity(CloneRuntimeSupport.SHOPEE_PACKAGE)
                ?.endsWith(".LoginActivity_") == true,
        )
        assertTrue(CloneRuntimeSupport.loginActivity(CloneRuntimeSupport.LINE_PACKAGE) == null)
    }

    @Test
    fun `unaccepted packages remain metadata only`() {
        assertFalse(CloneRuntimeSupport.canLaunch("com.example.unaccepted"))
    }
}
