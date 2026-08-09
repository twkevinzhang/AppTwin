package com.lody.virtual.server.pm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PendingIntentGenerationAccessPolicyTest {
    @Test
    public void guestCannotCreateEpochsForAnotherPackageOrUser() {
        int guestVuid = 300_123;
        assertTrue(PendingIntentGenerationAccessPolicy.canRead(
                guestVuid, 10_000, "com.example.own", 3,
                new String[]{"com.example.own"}));
        assertFalse(PendingIntentGenerationAccessPolicy.canRead(
                guestVuid, 10_000, "com.google.android.gms", 3,
                new String[]{"com.example.own"}));
        assertFalse(PendingIntentGenerationAccessPolicy.canRead(
                guestVuid, 10_000, "com.example.own", 4,
                new String[]{"com.example.own"}));
    }
}
