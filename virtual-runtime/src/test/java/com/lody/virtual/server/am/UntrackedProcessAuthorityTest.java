package com.lody.virtual.server.am;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UntrackedProcessAuthorityTest {
    @Test
    public void onlyEngineOrSystemAuthenticatedHostMainProcessReceivesHostUid() {
        assertEquals(10_321, VActivityManagerService.resolveUntrackedProcessUid(
                90, 90, 10_321, false));
        assertEquals(10_321, VActivityManagerService.resolveUntrackedProcessUid(
                91, 90, 10_321, true));
        assertEquals(-1, VActivityManagerService.resolveUntrackedProcessUid(
                92, 90, 10_321, false));
        assertEquals(-1, VActivityManagerService.resolveUntrackedProcessUid(
                93, 90, 10_321, false));
    }

    @Test
    public void untrackedCallerFailsClosedWhenSystemIdentityIsUnavailable() {
        assertEquals(-1, VActivityManagerService.resolveUntrackedProcessUid(
                92, 90, 10_321, false));
    }

    @Test
    public void untrackedOrGroupBStubCannotClaimGroupAOnRestart() {
        assertEquals(false, VActivityManagerService.matchesRestartClaim(
                8, "com.fixture.b", "com.fixture.b", true,
                "com.google.android.gms", "com.google.android.gms", 7));
        assertEquals(false, VActivityManagerService.matchesRestartClaim(
                7, "com.google.android.gms", "com.google.android.gms", false,
                "com.google.android.gms", "com.google.android.gms", 7));
        assertEquals(true, VActivityManagerService.matchesRestartClaim(
                7, "com.google.android.gms", "com.google.android.gms", true,
                "com.google.android.gms", "com.google.android.gms", 7));
    }

    @Test
    public void pendingBootstrapRequiresExactSlotAndServerDerivedLogicalKey() {
        LogicalProcessOwnerRegistry<Object> owners =
                new LogicalProcessOwnerRegistry<>(owner -> true);
        LogicalProcessOwnerRegistry.Reservation reservation = owners.reserve(
                new LogicalProcessKey(712_345, "com.fixture", "com.fixture:push"), 4)
                .reservation();

        assertEquals(true, VActivityManagerService.matchesBootstrapReservation(
                reservation, 4, 712_345, "com.fixture", "com.fixture:push"));
        assertEquals(false, VActivityManagerService.matchesBootstrapReservation(
                reservation, 3, 712_345, "com.fixture", "com.fixture:push"));
        assertEquals(false, VActivityManagerService.matchesBootstrapReservation(
                reservation, 4, 812_345, "com.fixture", "com.fixture:push"));
        assertEquals(false, VActivityManagerService.matchesBootstrapReservation(
                reservation, 4, 712_345, "other", "com.fixture:push"));
    }

    @Test
    public void processDeathAlwaysSchedulesFullExactOrphanReconciliation() throws Exception {
        String source = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/com/lody/virtual/server/am/VActivityManagerService.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        int cleanup = source.indexOf("private void cleanupProcessGeneration");
        int nextMethod = source.indexOf("private void onConnectionDied", cleanup);
        String cleanupSource = source.substring(cleanup, nextMethod);

        assertEquals(true, cleanupSource.contains(
                "scheduleOrphanedStubTaskReconciliation(null,"));
        assertEquals(false, cleanupSource.contains("if (!emptiedTaskIds.isEmpty())"));
        assertEquals(true, source.contains("OrphanStubTaskPolicy.shouldRemove"));
        assertEquals(true, source.contains("mMainStack.hasLiveTaskOwnership(taskInfo.id)"));
    }

    @Test
    public void processAndTaskMetadataAreVisibleOnlyWithinCallerGroup() {
        int hostUid = 10_321;
        int groupB = com.lody.virtual.os.VUserHandle.getUid(8, 12_345);
        assertEquals(true, VActivityManagerService.canObserveUser(groupB, hostUid, 8));
        assertEquals(false, VActivityManagerService.canObserveUser(groupB, hostUid, 7));
        assertEquals(false, VActivityManagerService.canObserveUser(-1, hostUid, 7));
        assertEquals(true, VActivityManagerService.canObserveUser(hostUid, hostUid, 7));
    }
}
