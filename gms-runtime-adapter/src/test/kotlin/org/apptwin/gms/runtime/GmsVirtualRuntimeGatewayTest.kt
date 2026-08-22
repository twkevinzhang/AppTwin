package org.apptwin.gms.runtime

import com.lody.virtual.remote.TrustedGmsCloudMessagingState
import org.apptwin.gms.ports.CloudMessagingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GmsVirtualRuntimeGatewayTest {
    @Test
    fun `supervisor phases map without promoting starting to connected`() {
        val phases = listOf(
            TrustedGmsCloudMessagingState.PHASE_DISABLED,
            TrustedGmsCloudMessagingState.PHASE_STARTING,
            TrustedGmsCloudMessagingState.PHASE_CONNECTED,
            TrustedGmsCloudMessagingState.PHASE_DEGRADED,
            TrustedGmsCloudMessagingState.PHASE_UNKNOWN,
        )

        val mapped = phases.map { phase ->
            trustedCloudMessagingHealth(runtimeState(phase)).state
        }

        assertEquals(
            listOf(
                CloudMessagingState.DISABLED,
                CloudMessagingState.STARTING,
                CloudMessagingState.CONNECTED,
                CloudMessagingState.DEGRADED,
                CloudMessagingState.UNKNOWN,
            ),
            mapped,
        )
    }

    @Test
    fun `connected phase without live process and binding degrades fail closed`() {
        val health = trustedCloudMessagingHealth(
            runtimeState(
                phase = TrustedGmsCloudMessagingState.PHASE_CONNECTED,
                processAlive = true,
                bindingAlive = false,
                lastConnectedAtMillis = 987L,
                retryAttempt = 2,
                failureCode = "MCS_BINDING_LOST",
            ),
        )

        assertEquals(CloudMessagingState.DEGRADED, health.state)
        assertEquals(987L, health.lastConnectedAtMillis)
        assertEquals(2, health.retryAttempt)
        assertEquals("MCS_BINDING_LOST", health.failureCode)
    }

    @Test
    fun `unknown phase and absent runtime state remain unknown`() {
        assertEquals(
            CloudMessagingState.UNKNOWN,
            trustedCloudMessagingHealth(runtimeState(phase = Int.MAX_VALUE)).state,
        )
        val absent = trustedCloudMessagingHealth(null)
        assertEquals(CloudMessagingState.UNKNOWN, absent.state)
        assertEquals("CLOUD_MESSAGING_STATE_UNAVAILABLE", absent.failureCode)
    }

    @Test
    fun `runtime observation exception is sanitized and fails closed`() {
        val health = observeTrustedCloudMessaging {
            throw IllegalStateException("raw binder token and filesystem path")
        }

        assertEquals(CloudMessagingState.UNKNOWN, health.state)
        assertEquals("CLOUD_MESSAGING_OBSERVE_RETRYABLE", health.failureCode)
    }

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

    private fun runtimeState(
        phase: Int,
        processAlive: Boolean = true,
        bindingAlive: Boolean = true,
        lastConnectedAtMillis: Long = 0L,
        retryAttempt: Int = 0,
        failureCode: String? = null,
    ) = TrustedGmsCloudMessagingState(
        phase,
        processAlive,
        bindingAlive,
        lastConnectedAtMillis,
        retryAttempt,
        1L,
        failureCode,
    )
}
