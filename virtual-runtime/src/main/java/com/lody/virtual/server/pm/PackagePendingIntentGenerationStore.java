package com.lody.virtual.server.pm;

import com.lody.virtual.helper.utils.AtomicFile;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Durable package+user revocation epoch for host PendingIntent redirects. */
final class PackagePendingIntentGenerationStore {
    private static final int MAGIC = 0x41545049;
    private static final int VERSION = 1;
    private final AtomicFile file;
    private final Map<Key, Long> generations = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    PackagePendingIntentGenerationStore(File file) {
        this.file = new AtomicFile(file);
        read();
    }

    synchronized long currentOrCreate(String packageName, int userId) throws IOException {
        Key key = new Key(packageName, userId);
        Long current = generations.get(key);
        if (current != null) return current;
        long created = next();
        generations.put(key, created);
        try {
            write();
            return created;
        } catch (IOException failure) {
            generations.remove(key);
            throw failure;
        }
    }

    synchronized long bump(String packageName, int userId) throws IOException {
        Key key = new Key(packageName, userId);
        Long previous = generations.put(key, next());
        try {
            write();
            return generations.get(key);
        } catch (IOException failure) {
            if (previous == null) generations.remove(key); else generations.put(key, previous);
            throw failure;
        }
    }

    synchronized void clearUser(int userId) throws IOException {
        Map<Key, Long> before = new HashMap<>(generations);
        Iterator<Key> iterator = generations.keySet().iterator();
        while (iterator.hasNext()) if (iterator.next().userId == userId) iterator.remove();
        try {
            write();
        } catch (IOException failure) {
            generations.clear();
            generations.putAll(before);
            throw failure;
        }
    }

    private long next() {
        long value;
        do value = random.nextLong(); while (value == 0L);
        return value;
    }

    private void read() {
        if (!file.getBaseFile().exists()) return;
        try (DataInputStream input = new DataInputStream(file.openRead())) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException();
            int count = input.readInt();
            if (count < 0 || count > 100_000) throw new IOException();
            while (count-- > 0) {
                String packageName = input.readUTF();
                int userId = input.readInt();
                long generation = input.readLong();
                if (packageName.isEmpty() || userId < 0 || generation == 0L) throw new IOException();
                generations.put(new Key(packageName, userId), generation);
            }
            if (input.read() != -1) throw new IOException();
        } catch (IOException failure) {
            generations.clear();
            file.delete();
        }
    }

    private void write() throws IOException {
        FileOutputStream raw = null;
        try {
            raw = file.startWrite();
            DataOutputStream output = new DataOutputStream(raw);
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(generations.size());
            for (Map.Entry<Key, Long> entry : generations.entrySet()) {
                output.writeUTF(entry.getKey().packageName);
                output.writeInt(entry.getKey().userId);
                output.writeLong(entry.getValue());
            }
            output.flush();
            file.finishWrite(raw);
            raw = null;
        } finally {
            if (raw != null) file.failWrite(raw);
        }
    }

    private static final class Key {
        final String packageName;
        final int userId;
        Key(String packageName, int userId) {
            this.packageName = packageName;
            this.userId = userId;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return userId == key.userId && packageName.equals(key.packageName);
        }
        @Override public int hashCode() { return 31 * packageName.hashCode() + userId; }
    }
}
