package com.lody.virtual.server.am;

import com.lody.virtual.client.stub.VASettings;
import org.junit.Test;
import static org.junit.Assert.*;

public class PhysicalTaskIdentityTest {
    private static final String HOST = "org.apptwin";
    private static final String STUB = VASettings.STUB_ACTIVITY + "$C3";

    @Test
    public void modernRunningTaskRetainsLiveExactOwnerWhenDeprecatedIdIsMissing() {
        int physical = PhysicalTaskIdentity.runningTaskId(37, 99, 98, 42, true, 1);
        assertTrue(retain(physical, true, HOST, STUB));
        assertTrue(retain(PhysicalTaskIdentity.runningTaskId(37, -1, -1, 42, true, 1),
                true, HOST, STUB));
    }

    @Test
    public void modernNonRunningAndEmptyTasksCannotRetainEvenLiveOwner() {
        assertFalse(retain(PhysicalTaskIdentity.runningTaskId(29, 42, 42, 42, false, 1),
                true, HOST, STUB));
        assertFalse(retain(PhysicalTaskIdentity.runningTaskId(37, 42, 42, 42, true, 0),
                true, HOST, STUB));
        assertFalse(retain(PhysicalTaskIdentity.runningTaskId(37, 42, 42, -1, true, 1),
                true, HOST, STUB));
    }

    @Test
    public void stableIdentityDoesNotRescueDeadSupersededOrWrongUserOwner() {
        int physical = PhysicalTaskIdentity.runningTaskId(37, -1, -1, 42, true, 1);
        assertFalse(retain(physical, false, HOST, STUB));
        boolean wrongUser = TaskHistoryRetentionPolicy.hasEligibleOwner(42, 2, 42, 3, 3, false, true);
        assertFalse(retain(physical, wrongUser, HOST, STUB));
        boolean superseded = TaskHistoryRetentionPolicy.hasEligibleOwner(42, 2, 42, 2, 2, false, false);
        assertFalse(retain(physical, superseded, HOST, STUB));
        boolean marked = TaskHistoryRetentionPolicy.hasEligibleOwner(42, 2, 42, 2, 2, true, true);
        assertFalse(retain(physical, marked, HOST, STUB));
    }

    @Test
    public void stableIdentityStillRequiresExactPhysicalTaskAndHostStub() {
        assertFalse(retain(PhysicalTaskIdentity.runningTaskId(37, -1, -1, 43, true, 1),
                true, HOST, STUB));
        int physical = PhysicalTaskIdentity.runningTaskId(37, -1, -1, 42, true, 1);
        assertFalse(retain(physical, true, "other.host", STUB));
        assertFalse(retain(physical, true, HOST, "org.apptwin.MainActivity"));
        assertFalse(retain(physical, true, HOST, STUB + "suffix"));
    }

    @Test
    public void legacyRunningTaskUsesPersistentIdentityWithIndependentRunningIndicator() {
        for (int sdk : new int[]{21, 26, 28}) {
            int physical = PhysicalTaskIdentity.runningTaskId(sdk, 99, 42, -1, false, 0);
            assertTrue(retain(physical, true, HOST, STUB));
            assertFalse(retain(PhysicalTaskIdentity.runningTaskId(sdk, -1, 42, 42, true, 1),
                    true, HOST, STUB));
            assertFalse(retain(PhysicalTaskIdentity.runningTaskId(sdk, 42, -1, 42, true, 1),
                    true, HOST, STUB));
        }
    }

    @Test
    public void pendingLaunchCannotRetainNonRunningTaskDespiteValidLeaseAndGeneration() {
        boolean pending = TaskHistoryRetentionPolicy.isPendingOwnerValid(1001, 1000, 2, 2, 7, 7, true);
        assertTrue(pending);
        int physical = PhysicalTaskIdentity.runningTaskId(37, -1, -1, 42, false, 0);
        assertFalse(retain(physical, pending, HOST, STUB));
    }

    @Test
    public void canonicalIdentityProtectsOwnedOrphanCandidateDespiteDeprecatedId() {
        int canonical = PhysicalTaskIdentity.canonicalTaskId(37, 98, 42);
        boolean liveOwnership = canonical == 42
                && TaskHistoryRetentionPolicy.hasEligibleOwner(42, 2, 42, 2, 2, false, true);
        assertFalse(OrphanStubTaskPolicy.shouldRemove(HOST, HOST, STUB, null, null,
                liveOwnership, 50));
        // Non-running tasks retain their identity for cleanup, not their live-owner status.
        assertEquals(42, PhysicalTaskIdentity.canonicalTaskId(28, 42, 99));
        assertEquals(-1, PhysicalTaskIdentity.canonicalTaskId(37, 42, -1));
        assertEquals(-1, PhysicalTaskIdentity.canonicalTaskId(28, -1, 42));
    }

    @Test
    public void canonicalIdentityDoesNotKeepEmptyNonRunningOrphanStub() {
        int canonical = PhysicalTaskIdentity.canonicalTaskId(37, -1, 42);
        int running = PhysicalTaskIdentity.runningTaskId(37, -1, -1, 42, false, 0);
        assertEquals(42, canonical);
        assertEquals(-1, running);
        assertTrue(OrphanStubTaskPolicy.shouldRemove(HOST, HOST, STUB, null, null,
                false, 50));
        assertFalse(OrphanStubTaskPolicy.shouldRemove(HOST, "other.host", STUB, null, null,
                false, 50));
        assertFalse(OrphanStubTaskPolicy.shouldRemove(HOST, HOST, "org.apptwin.MainActivity", null, null,
                false, 50));
    }

    private static boolean retain(int physical, boolean owner, String pkg, String component) {
        return TaskHistoryRetentionPolicy.shouldRetain(42, physical, owner,
                HOST, pkg, component, null, null, 50);
    }
}
