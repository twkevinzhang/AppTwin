package com.lody.virtual.server.am;

import com.lody.virtual.client.stub.VASettings;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TaskHistoryRetentionPolicyTest {
    private static final String HOST = "org.apptwin";
    private static final String STUB = VASettings.STUB_ACTIVITY + "$C3";

    @Test
    public void missingRecentEntryRetainsExactPhysicalStubWithLiveOwner() {
        assertTrue(retain(42, true, HOST, STUB));
        assertTrue(retain(42, true, HOST, VASettings.STUB_DIALOG + "$C3"));
        assertTrue(retain(42, true, HOST,
                VASettings.STUB_EXCLUDE_FROM_RECENT_ACTIVITY + "$C3"));
        assertTrue(TaskHistoryRetentionPolicy.shouldRetain(42, 42, true,
                HOST, null, null, HOST, STUB, 50));
    }

    @Test
    public void deadOwnerOrDifferentPhysicalTaskCannotRetainHistory() {
        assertFalse(retain(42, false, HOST, STUB));
        assertFalse(retain(43, true, HOST, STUB));
    }

    @Test
    public void onlyExactHostStubComponentsCanConfirmPhysicalTask() {
        assertFalse(retain(42, true, "other.host", STUB));
        assertFalse(retain(42, true, HOST, "org.apptwin.MainActivity"));
        assertFalse(retain(42, true, HOST, VASettings.STUB_ACTIVITY + "$C50"));
        assertFalse(retain(42, true, HOST, STUB + "suffix"));
        assertFalse(TaskHistoryRetentionPolicy.shouldRetain(42, 42, true,
                HOST, null, null, null, null, 50));
        assertFalse(TaskHistoryRetentionPolicy.shouldRetain(42, 42, true,
                null, HOST, STUB, null, null, 50));
    }

    @Test
    public void onlyUnmarkedCurrentGenerationInExactTaskAndUserCanOwnHistory() {
        assertTrue(owner(42, 2, 2, false, true));
        assertFalse(owner(43, 2, 2, false, true));
        assertFalse(owner(42, 3, 2, false, true));
        assertFalse(owner(42, 2, 3, false, true));
        assertFalse(owner(42, 2, 2, true, true));
        // The registry rejects both dead binders and superseded process generations.
        assertFalse(owner(42, 2, 2, false, false));
    }

    @Test
    public void pendingOwnerHasFixedDeadlineAndNeverRenewsOnQuery() {
        assertTrue(pending(1_000L, 1_000L, 2, 7, true));
        assertTrue(pending(5_999L, 1_000L, 2, 7, true));
        assertFalse(pending(6_000L, 1_000L, 2, 7, true));
        assertFalse(pending(6_001L, 1_000L, 2, 7, true));
        assertFalse(pending(999L, 1_000L, 2, 7, true));
    }

    @Test
    public void pendingOwnerRejectsCrossUserDeadAndSupersededGeneration() {
        assertFalse(pending(1_001L, 1_000L, 3, 7, true));
        assertFalse(pending(1_001L, 1_000L, 2, 8, true));
        assertFalse(pending(1_001L, 1_000L, 2, 7, false));
    }

    private static boolean pending(long now, long preparedAt, int processUserId,
            long processGeneration, boolean currentOwner) {
        return TaskHistoryRetentionPolicy.isPendingOwnerValid(now, preparedAt, 2,
                processUserId, 7, processGeneration, currentOwner);
    }

    private static boolean owner(int activityTaskId, int activityUserId, int processUserId,
            boolean marked, boolean currentGenerationAlive) {
        return TaskHistoryRetentionPolicy.hasEligibleOwner(42, 2, activityTaskId,
                activityUserId, processUserId, marked, currentGenerationAlive);
    }

    private static boolean retain(int physicalTaskId, boolean liveOwner,
            String basePackage, String baseClass) {
        return TaskHistoryRetentionPolicy.shouldRetain(42, physicalTaskId, liveOwner,
                HOST, basePackage, baseClass, null, null, 50);
    }
}
