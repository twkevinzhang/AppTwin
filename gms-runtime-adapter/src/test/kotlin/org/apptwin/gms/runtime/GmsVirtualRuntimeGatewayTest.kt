package org.apptwin.gms.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GmsVirtualRuntimeGatewayTest {
    @Test
    fun `trusted bundle reports the real pinned GmsCore artifact revision`() {
        assertEquals(
            250_932_030L,
            trustedBundleVersionCode(
                gmsArtifactVersion = 250_932_030L,
                companionArtifactVersion = 84_022_630L,
            ),
        )
    }

    @Test
    fun `trusted bundle rejects a mismatched companion artifact`() {
        assertNull(
            trustedBundleVersionCode(
                gmsArtifactVersion = 250_932_030L,
                companionArtifactVersion = 84_022_631L,
            ),
        )
    }
}
