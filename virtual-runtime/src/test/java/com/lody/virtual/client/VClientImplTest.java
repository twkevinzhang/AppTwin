package com.lody.virtual.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VClientImplTest {
    @Test
    public void regularProcessStillExposesLogicalGuestUidToJavaHooks() {
        assertEquals(10005, GuestUidPolicy.guestFacingUid(10005, -1));
    }

    @Test
    public void isolatedProcessExposesItsDedicatedReportedUidToJavaHooks() {
        assertEquals(99005, GuestUidPolicy.guestFacingUid(10005, 99005));
    }
}
