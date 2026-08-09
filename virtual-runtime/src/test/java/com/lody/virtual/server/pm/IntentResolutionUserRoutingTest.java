package com.lody.virtual.server.pm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class IntentResolutionUserRoutingTest {

    @Test
    public void implicitIntentResolutionKeepsRequestedVirtualUser() {
        assertEquals(0, VPackageManagerService.resolveIntentQueryUserId(0));
        assertEquals(7, VPackageManagerService.resolveIntentQueryUserId(7));
        assertEquals(42, VPackageManagerService.resolveIntentQueryUserId(42));
    }
}
