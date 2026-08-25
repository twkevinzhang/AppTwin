package com.lody.virtual.client.stub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DaemonLifetimePolicyTest {
    @Test
    public void workloadKeepsDaemonWithoutSessionDeadline() {
        DaemonLifetimePolicy policy = new DaemonLifetimePolicy(5_000L);

        assertEquals(DaemonLifetimePolicy.Decision.KEEP, policy.evaluate(true, 0L));
        assertEquals(DaemonLifetimePolicy.Decision.KEEP,
                policy.evaluate(true, Long.MAX_VALUE - 1L));
    }

    @Test
    public void continuousIdleStopsOnlyAfterBoundedGrace() {
        DaemonLifetimePolicy policy = new DaemonLifetimePolicy(5_000L);

        assertEquals(DaemonLifetimePolicy.Decision.WAIT, policy.evaluate(false, 100L));
        assertEquals(DaemonLifetimePolicy.Decision.WAIT, policy.evaluate(false, 5_099L));
        assertEquals(DaemonLifetimePolicy.Decision.STOP, policy.evaluate(false, 5_100L));
    }

    @Test
    public void workloadAndNewStartEachResetPendingIdleStop() {
        DaemonLifetimePolicy policy = new DaemonLifetimePolicy(5_000L);

        assertEquals(DaemonLifetimePolicy.Decision.WAIT, policy.evaluate(false, 0L));
        assertEquals(DaemonLifetimePolicy.Decision.KEEP, policy.evaluate(true, 4_999L));
        assertEquals(DaemonLifetimePolicy.Decision.WAIT, policy.evaluate(false, 10_000L));
        policy.onStart();
        assertEquals(DaemonLifetimePolicy.Decision.WAIT, policy.evaluate(false, 14_999L));
        assertEquals(DaemonLifetimePolicy.Decision.WAIT, policy.evaluate(false, 19_998L));
        assertEquals(DaemonLifetimePolicy.Decision.STOP, policy.evaluate(false, 19_999L));
    }

    @Test
    public void zeroGraceStopsOnFirstIdleObservation() {
        DaemonLifetimePolicy policy = new DaemonLifetimePolicy(0L);

        assertEquals(DaemonLifetimePolicy.Decision.STOP, policy.evaluate(false, 10L));
    }

    @Test
    public void negativeGraceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DaemonLifetimePolicy(-1L));
    }

    @Test
    public void freshWorkloadBeforeStopCancelsTheIdleDecision() {
        assertTrue(DaemonLifetimePolicy.shouldStopAfterConfirmation(true, false));
        assertFalse(DaemonLifetimePolicy.shouldStopAfterConfirmation(true, true));
        assertFalse(DaemonLifetimePolicy.shouldStopAfterConfirmation(false, false));
    }
}
