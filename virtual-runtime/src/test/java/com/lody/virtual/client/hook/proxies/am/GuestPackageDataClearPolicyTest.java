package com.lody.virtual.client.hook.proxies.am;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GuestPackageDataClearPolicyTest {
    @Test
    public void installsHooksForBothAndroidSelfClearBinderMethods() {
        assertEquals("clearApplicationUserData",
                new MethodProxies.ClearApplicationUserData().getMethodName());
        assertEquals("clearApplicationUserDataWithoutPermissionReset",
                new MethodProxies.ClearApplicationUserDataWithoutPermissionReset().getMethodName());
    }

    @Test
    public void handlesGuestClearingItsOwnData() {
        assertTrue(GuestPackageDataClearPolicy.shouldHandle(
                "jp.naver.line.android", "jp.naver.line.android"));
    }

    @Test
    public void leavesOtherPackagesAndInvalidRequestsToTheSystem() {
        assertFalse(GuestPackageDataClearPolicy.shouldHandle(
                "example.other", "jp.naver.line.android"));
        assertFalse(GuestPackageDataClearPolicy.shouldHandle(
                null, "jp.naver.line.android"));
        assertFalse(GuestPackageDataClearPolicy.shouldHandle(
                "jp.naver.line.android", null));
    }
}
