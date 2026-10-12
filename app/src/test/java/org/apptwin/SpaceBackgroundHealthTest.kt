package org.apptwin

import com.lody.virtual.remote.TrustedGmsCloudMessagingState as RuntimeState
import org.junit.Assert.*
import org.junit.Test

class SpaceBackgroundHealthTest {
    private fun state(phase: Int, tcp: Int, process: Boolean = true, binding: Boolean = true, session: Boolean = true) =
        RuntimeState(phase, process, binding, 100L, 0, 1L, null, tcp, 200L, session)
    @Test fun cachedConnectedWithoutTcpCannotReportSuccess() {
        val health = mapSpaceBackgroundHealth("space-2", state(RuntimeState.PHASE_CONNECTED, RuntimeState.TCP_DISCONNECTED))
        assertFalse(health.healthy)
        assertEquals(SpaceBackgroundStatus.FAILED, health.tcp)
        assertTrue(health.retryable)
    }
    @Test fun unknownTcpCannotBecomeHealthyAndCanBeRetried() {
        val health = mapSpaceBackgroundHealth("space-2", state(RuntimeState.PHASE_CONNECTED, RuntimeState.TCP_UNKNOWN))
        assertFalse(health.healthy)
        assertEquals(SpaceBackgroundStatus.UNKNOWN, health.tcp)
        assertTrue(health.retryable)
    }
    @Test fun startingDoesNotOfferDuplicateRetry() {
        val health = mapSpaceBackgroundHealth("space-2", state(RuntimeState.PHASE_STARTING, RuntimeState.TCP_DISCONNECTED, false, false))
        assertEquals(SpaceBackgroundStatus.STARTING, health.microg)
        assertEquals(SpaceBackgroundStatus.STARTING, health.tcp)
        assertFalse(health.retryable)
    }
    @Test fun tcpAliveButSessionLostOffersRetry() {
        val health = mapSpaceBackgroundHealth("space-2", state(RuntimeState.PHASE_DEGRADED, RuntimeState.TCP_CONNECTED, session = false))
        assertFalse(health.healthy)
        assertTrue(health.retryable)
        assertEquals("推播連線異常，請重新連線。", health.error)
    }
    @Test fun connectedSocketWithUnverifiedSessionAllowsRecovery() {
        val health = mapSpaceBackgroundHealth("space-2", state(RuntimeState.PHASE_CONNECTED, RuntimeState.TCP_CONNECTED, session = false))
        assertFalse(health.healthy)
        assertTrue(health.retryable)
    }
    @Test fun disabledSupervisorDoesNotOfferRecovery() {
        val health = mapSpaceBackgroundHealth("space-2", state(RuntimeState.PHASE_DISABLED, RuntimeState.TCP_UNKNOWN, false, false, false))
        assertEquals(SpaceBackgroundStatus.DISABLED, health.microg)
        assertEquals(SpaceBackgroundStatus.DISABLED, health.tcp)
        assertFalse(health.healthy)
        assertFalse(health.retryable)
    }
    @Test fun pendingRuntimeRemainsNonRetryableAfterUiWaitExpires() {
        val health = SpaceBackgroundHealth("space-2", SpaceBackgroundStatus.SUCCESS, SpaceBackgroundStatus.STARTING,
            error = "連線尚未恢復，請稍後檢查")
        assertTrue(health.recovering)
        assertFalse(health.retryable)
        assertFalse(health.healthy)
    }
    @Test fun liveProcessAndBindingAndTcpAndSessionAreRequired() {
        assertFalse(mapSpaceBackgroundHealth("space-2", state(RuntimeState.PHASE_CONNECTED, RuntimeState.TCP_CONNECTED, session = false)).healthy)
        assertTrue(mapSpaceBackgroundHealth("space-2", state(RuntimeState.PHASE_CONNECTED, RuntimeState.TCP_CONNECTED)).healthy)
        assertFalse(mapSpaceBackgroundHealth("space-2", state(RuntimeState.PHASE_CONNECTED, RuntimeState.TCP_CONNECTED, binding = false)).healthy)
        assertFalse(mapSpaceBackgroundHealth("space-2", state(RuntimeState.PHASE_DEGRADED, RuntimeState.TCP_CONNECTED)).healthy)
    }
}
