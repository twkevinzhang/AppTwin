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

    @Test
    public void virtualAccountManagerMayCallAccountAuthenticator() {
        assertEquals(0, VirtualAccountPermissionEnforcer.permissionResult(
                android.Manifest.permission.ACCOUNT_MANAGER, 4201, 4201));
    }

    @Test
    public void unrelatedPermissionIsDeniedForAccountManager() {
        assertEquals(2, VirtualAccountPermissionEnforcer.permissionResult(
                android.Manifest.permission.INTERNET, 4201, 4201));
    }

    @Test
    public void directGuestAuthenticatorCallerIsDenied() {
        assertEquals(2, VirtualAccountPermissionEnforcer.permissionResult(
                android.Manifest.permission.ACCOUNT_MANAGER, 4201, 7302));
        assertEquals(2, VirtualAccountPermissionEnforcer.permissionResult(
                android.Manifest.permission.ACCOUNT_MANAGER, 0, 0));
    }

}
