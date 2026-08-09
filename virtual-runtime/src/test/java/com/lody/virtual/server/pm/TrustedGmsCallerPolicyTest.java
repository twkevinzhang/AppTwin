package com.lody.virtual.server.pm;

import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class TrustedGmsCallerPolicyTest {
    @Test
    public void hostCallerIsAllowed() {
        TrustedGmsCallerPolicy.enforceHost(10_001, 10_001);
    }

    @Test
    public void guestAndCrossUserVirtualCallersAreRejected() {
        assertThrows(SecurityException.class,
                () -> TrustedGmsCallerPolicy.enforceHost(310_001, 10_001));
        assertThrows(SecurityException.class,
                () -> TrustedGmsCallerPolicy.enforceHost(410_001, 10_001));
    }
}
