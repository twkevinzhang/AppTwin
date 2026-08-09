package com.lody.virtual.server.notification;

import com.lody.virtual.helper.utils.AtomicFile;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Durable non-content notification ownership used to clean a deleted virtual user after restart. */
final class NotificationStateStore {
    private static final int MAGIC = 0x41544E46;
    private static final int VERSION = 1;
    private static final int MAX_RECORDS = 100_000;
    private final AtomicFile file;

    NotificationStateStore(File file) {
        this.file = new AtomicFile(file);
    }

    State read() throws IOException {
        if (!file.getBaseFile().exists()) {
            return new State();
        }
        byte[] bytes = file.readFully();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IOException("Unsupported notification state");
            }
            State state = new State();
            int recordCount = checkedCount(input.readInt());
            for (int i = 0; i < recordCount; i++) {
                String packageName = input.readUTF();
                boolean hasTag = input.readBoolean();
                String tag = hasTag ? input.readUTF() : null;
                int id = input.readInt();
                int userId = input.readInt();
                if (packageName.isEmpty() || userId < 0) {
                    throw new IOException("Invalid notification owner");
                }
                List<VNotificationManagerService.NotificationInfo> records =
                        state.notifications.get(packageName);
                if (records == null) {
                    records = new ArrayList<>();
                    state.notifications.put(packageName, records);
                }
                records.add(new VNotificationManagerService.NotificationInfo(
                        id, tag, packageName, userId));
            }
            int disabledCount = checkedCount(input.readInt());
            for (int i = 0; i < disabledCount; i++) {
                state.disabled.add(input.readUTF());
            }
            if (input.available() != 0) {
                throw new IOException("Trailing notification state data");
            }
            return state;
        }
    }

    void write(Map<String, List<VNotificationManagerService.NotificationInfo>> notifications,
               List<String> disabled) throws IOException {
        FileOutputStream raw = null;
        try {
            raw = file.startWrite();
            DataOutputStream output = new DataOutputStream(raw);
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            int count = 0;
            for (List<VNotificationManagerService.NotificationInfo> records : notifications.values()) {
                count += records.size();
            }
            if (count > MAX_RECORDS || disabled.size() > MAX_RECORDS) {
                throw new IOException("Notification state limit exceeded");
            }
            output.writeInt(count);
            for (List<VNotificationManagerService.NotificationInfo> records : notifications.values()) {
                for (VNotificationManagerService.NotificationInfo record : records) {
                    output.writeUTF(record.packageName);
                    output.writeBoolean(record.tag != null);
                    if (record.tag != null) {
                        output.writeUTF(record.tag);
                    }
                    output.writeInt(record.id);
                    output.writeInt(record.userId);
                }
            }
            output.writeInt(disabled.size());
            for (String key : disabled) {
                output.writeUTF(key);
            }
            output.flush();
            file.finishWrite(raw);
            raw = null;
        } catch (Exception failure) {
            file.failWrite(raw);
            if (failure instanceof IOException) {
                throw (IOException) failure;
            }
            throw new IOException("Unable to persist notification state", failure);
        }
    }

    void delete() {
        file.delete();
    }

    private static int checkedCount(int count) throws IOException {
        if (count < 0 || count > MAX_RECORDS) {
            throw new IOException("Invalid notification state count");
        }
        return count;
    }

    static final class State {
        final HashMap<String, List<VNotificationManagerService.NotificationInfo>> notifications =
                new HashMap<>();
        final ArrayList<String> disabled = new ArrayList<>();
    }
}
