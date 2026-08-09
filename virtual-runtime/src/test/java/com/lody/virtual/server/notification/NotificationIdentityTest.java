package com.lody.virtual.server.notification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class NotificationIdentityTest {

    @Test
    public void sameGuestIdentityIsStable() {
        assertEquals(
                NotificationIdentity.namespaceUntaggedId(42, "com.example.chat", 7),
                NotificationIdentity.namespaceUntaggedId(42, "com.example.chat", 7));
    }

    @Test
    public void sameGuestIdIsIsolatedAcrossUsersAndPackages() {
        int first = NotificationIdentity.namespaceUntaggedId(42, "com.example.chat", 7);

        assertNotEquals(first,
                NotificationIdentity.namespaceUntaggedId(42, "com.example.chat", 8));
        assertNotEquals(first,
                NotificationIdentity.namespaceUntaggedId(42, "com.example.mail", 7));
    }

    @Test
    public void malformedPackageFailsOpenToOriginalId() {
        assertEquals(42, NotificationIdentity.namespaceUntaggedId(42, null, 7));
    }
}
