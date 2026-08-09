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
    public void processAndTaskMetadataAreVisibleOnlyWithinCallerGroup() {
        int hostUid = 10_321;
        int groupB = com.lody.virtual.os.VUserHandle.getUid(8, 12_345);
        assertEquals(true, VActivityManagerService.canObserveUser(groupB, hostUid, 8));
        assertEquals(false, VActivityManagerService.canObserveUser(groupB, hostUid, 7));
        assertEquals(false, VActivityManagerService.canObserveUser(-1, hostUid, 7));
        assertEquals(true, VActivityManagerService.canObserveUser(hostUid, hostUid, 7));
    }
}
