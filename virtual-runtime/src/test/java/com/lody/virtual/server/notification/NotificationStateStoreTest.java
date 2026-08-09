package com.lody.virtual.server.notification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NotificationStateStoreTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void notificationOwnershipSurvivesRestartWithoutContent() throws Exception {
        File file = new File(temporaryFolder.getRoot(), "notifications.bin");
        NotificationStateStore writer = new NotificationStateStore(file);
        Map<String, List<VNotificationManagerService.NotificationInfo>> notifications =
                new HashMap<>();
        List<VNotificationManagerService.NotificationInfo> records = new ArrayList<>();
        records.add(new VNotificationManagerService.NotificationInfo(
                101, null, "com.example.chat", 3));
        records.add(new VNotificationManagerService.NotificationInfo(
                202, "message@4", "com.example.chat", 4));
        notifications.put("com.example.chat", records);

        writer.write(notifications, new ArrayList<>());
        NotificationStateStore.State restarted = new NotificationStateStore(file).read();

        assertEquals(2, restarted.notifications.get("com.example.chat").size());
        VNotificationManagerService.NotificationInfo first =
                restarted.notifications.get("com.example.chat").get(0);
        assertEquals(3, first.userId);
        assertEquals(101, first.id);
        assertNull(first.tag);
        assertFalse(restarted.disabled.contains("com.example.chat:3"));
    }

    @Test(expected = IOException.class)
    public void corruptStateFailsClosed() throws Exception {
        File file = new File(temporaryFolder.getRoot(), "notifications.bin");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[]{1, 2, 3, 4});
        }

        new NotificationStateStore(file).read();
    }
}
