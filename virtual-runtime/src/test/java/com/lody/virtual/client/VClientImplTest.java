package com.lody.virtual.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VClientImplTest {
    @Test
    public void regularProcessExposesKernelOwnedHostUidToJavaHooks() {
        assertEquals(10311, GuestUidPolicy.guestFacingUid(10005, -1, 10311));
    }

    @Test
    public void isolatedProcessExposesItsDedicatedReportedUidToJavaHooks() {
        assertEquals(99005, GuestUidPolicy.guestFacingUid(10005, 99005, 10311));
    }
}
