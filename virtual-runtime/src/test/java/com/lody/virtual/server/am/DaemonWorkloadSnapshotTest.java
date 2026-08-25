package com.lody.virtual.server.am;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DaemonWorkloadSnapshotTest {
    @Test
    public void reliableEmptySnapshotAllowsDaemonToStop() {
        DaemonWorkloadSnapshot snapshot = snapshot(0, 0, 0, 0, 0, 0, 0, true);

        assertTrue(snapshot.isObservationReliable());
        assertFalse(snapshot.hasWorkload());
    }

    @Test
    public void eachOwnedWorkloadRetainsDaemon() {
        for (int field = 0; field < 7; field++) {
            int[] counts = new int[7];
            counts[field] = 1;
            assertTrue("field=" + field, snapshot(
                    counts[0], counts[1], counts[2], counts[3], counts[4], counts[5],
                    counts[6], true).hasWorkload());
        }
    }

    @Test
    public void activityOnlyWorkDoesNotShortCircuitRecentTaskReconciliation() {
        DaemonWorkloadSnapshot activityOnly = snapshot(0, 1, 1, 0, 0, 0, 0, true);

        assertFalse(activityOnly.hasNonActivityWorkload());
        assertTrue(activityOnly.hasWorkload());
    }

    @Test
    public void eachNonActivityOwnerShortCircuitsRecentTaskReconciliation() {
        int[] nonActivityFields = {0, 3, 4, 5, 6};
        for (int field : nonActivityFields) {
            int[] counts = new int[7];
            counts[field] = 1;
            assertTrue("field=" + field, snapshot(
                    counts[0], counts[1], counts[2], counts[3], counts[4], counts[5],
                    counts[6], true).hasNonActivityWorkload());
        }
    }

    @Test
    public void unreliableObservationFailsSafe() {
        DaemonWorkloadSnapshot snapshot = snapshot(0, 0, 0, 0, 0, 0, 0, false);

        assertFalse(snapshot.isObservationReliable());
        assertTrue(snapshot.hasWorkload());
    }

    @Test
    public void initialReconciliationRequiresActiveForegroundAndRecoveryPermission() {
        assertTrue(VActivityManagerService.shouldScheduleInitialGmsReconciliation(true, true));
        assertFalse(VActivityManagerService.shouldScheduleInitialGmsReconciliation(true, false));
        assertFalse(VActivityManagerService.shouldScheduleInitialGmsReconciliation(false, true));
        assertFalse(VActivityManagerService.shouldScheduleInitialGmsReconciliation(false, false));
    }

    @Test
    public void automaticReconciliationCannotStartMcsWithoutForegroundSession() {
        assertTrue(VActivityManagerService.shouldRunAutomaticGmsReconciliation(true));
        assertFalse(VActivityManagerService.shouldRunAutomaticGmsReconciliation(false));
    }

    @Test
    public void failedDurableReconcileDoesNotMarkSnapshotReliable() {
        assertTrue(VActivityManagerService.shouldMarkGmsReconciliationComplete(true));
        assertFalse(VActivityManagerService.shouldMarkGmsReconciliationComplete(false));
    }

    @Test
    public void daemonGateWaitRejectsUnboundedTimeouts() {
        assertTrue(VActivityManagerService.isDaemonWorkloadGateWaitValid(0L, 0L));
        assertTrue(VActivityManagerService.isDaemonWorkloadGateWaitValid(1L,
                VActivityManagerService.MAX_DAEMON_WORKLOAD_GATE_WAIT_MS));
        assertFalse(VActivityManagerService.isDaemonWorkloadGateWaitValid(-1L, 0L));
        assertFalse(VActivityManagerService.isDaemonWorkloadGateWaitValid(0L, -1L));
        assertFalse(VActivityManagerService.isDaemonWorkloadGateWaitValid(0L,
                VActivityManagerService.MAX_DAEMON_WORKLOAD_GATE_WAIT_MS + 1L));
    }

    @Test
    public void exposesCountsForNotificationAndDiagnostics() {
        DaemonWorkloadSnapshot snapshot = snapshot(2, 3, 4, 5, 6, 7, 8, true);

        assertEquals(11L, snapshot.getWorkloadGeneration());
        assertEquals(2, snapshot.getGmsDesiredUserCount());
        assertEquals(3, snapshot.getActiveGuestTaskCount());
        assertEquals(4, snapshot.getActiveGuestActivityCount());
        assertEquals(5, snapshot.getActiveVirtualServiceCount());
        assertEquals(6, snapshot.getPendingPreparedLaunchCount());
        assertEquals(7, snapshot.getKeepAliveBindingCount());
        assertEquals(8, snapshot.getLineLeaseCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeCounts() {
        snapshot(-1, 0, 0, 0, 0, 0, 0, true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeWorkloadGeneration() {
        new DaemonWorkloadSnapshot(-1L, 0, 0, 0, 0, 0, 0, 0, true);
    }

    private static DaemonWorkloadSnapshot snapshot(int gms, int tasks, int activities,
            int services, int launches, int bindings, int leases, boolean reliable) {
        return new DaemonWorkloadSnapshot(11L, gms, tasks, activities, services, launches,
                bindings, leases, reliable);
    }
}
