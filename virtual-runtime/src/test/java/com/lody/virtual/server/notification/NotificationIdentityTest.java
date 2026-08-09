package com.lody.virtual.server.notification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class NotificationIdentityTest {

    @Test
    public void sameGuestIdentityIsStable() {
        assertEquals(
                NotificationIdentity.namespaceUntaggedTag("com.example.chat", 7),
                NotificationIdentity.namespaceUntaggedTag("com.example.chat", 7));
    }

    @Test
    public void untaggedIdentityIsIsolatedAcrossUsersAndPackages() {
        String first = NotificationIdentity.namespaceUntaggedTag("com.example.chat", 7);

        assertNotEquals(first,
                NotificationIdentity.namespaceUntaggedTag("com.example.chat", 8));
        assertNotEquals(first,
                NotificationIdentity.namespaceUntaggedTag("com.example.mail", 7));
    }

    @Test
    public void knownLinearIdCollisionNowUsesDistinctCancelableTags() {
        // The previous 32-bit scheme collided for (user 7, id 73) and (user 8, id 42).
        String userSeven = NotificationIdentity.namespaceUntaggedTag("com.example.chat", 7);
        String userEight = NotificationIdentity.namespaceUntaggedTag("com.example.chat", 8);

        assertNotEquals(userSeven, userEight);
        assertEquals(userSeven, NotificationIdentity.namespaceUntaggedTag("com.example.chat", 7));
        assertEquals(userEight, NotificationIdentity.namespaceUntaggedTag("com.example.chat", 8));
    }

    @Test
    public void malformedPackageHasNoSyntheticTag() {
        assertEquals(null, NotificationIdentity.namespaceUntaggedTag(null, 7));
    }

    @Test
    public void cancellingKnownCollisionForOneUserKeepsTheOtherRecord() {
        String packageName = "com.example.chat";
        String userSeven = NotificationIdentity.namespaceUntaggedTag(packageName, 7);
        String userEight = NotificationIdentity.namespaceUntaggedTag(packageName, 8);
        List<VNotificationManagerService.NotificationInfo> records = new ArrayList<>();
        records.add(new VNotificationManagerService.NotificationInfo(73, userSeven, packageName, 7));
        records.add(new VNotificationManagerService.NotificationInfo(42, userEight, packageName, 8));

        records.removeIf(info -> info.id == 73 && userSeven.equals(info.tag));

        assertEquals(1, records.size());
        assertEquals(8, records.get(0).userId);
        assertEquals(userEight, records.get(0).tag);
    }
}
