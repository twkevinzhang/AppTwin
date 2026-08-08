package com.lody.virtual.server.secondary;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FakeIdentityBinderTest {

    @Test
    public void binderIdentityUsesHostUidAndPidWithoutSignExtension() {
        int hostUid = 10966;
        int hostPid = 0x81234567;

        long identity = FakeIdentityBinder.composeIdentity(hostUid, hostPid);

        assertEquals(hostUid, (int) (identity >>> 32));
        assertEquals(hostPid, (int) identity);
    }

    @Test
    public void binderIdentityDoesNotContainGuestLogicalUid() {
        int hostUid = 10966;
        int guestLogicalUid = 10003;

        long identity = FakeIdentityBinder.composeIdentity(hostUid, 1234);

        assertEquals(hostUid, (int) (identity >>> 32));
        org.junit.Assert.assertNotEquals(guestLogicalUid, (int) (identity >>> 32));
    }
}
