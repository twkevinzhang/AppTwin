package com.lody.virtual.server.pm;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Durable, fail-closed dangerous-permission decisions keyed by virtual user and package.
 *
 * <p>The host UID's platform grant is only the outer capability. A grant in this store is the
 * inner, per-space capability; callers must require both before exposing a dangerous permission to
 * a guest.</p>
 */
final class RuntimePermissionState {
    private static final int MAGIC = 0x52545031; // RTP1
    private static final int MAX_ENTRIES = 16_384;

    private final File file;
    private final Set<Key> grants = new HashSet<>();
    private boolean damaged;

    RuntimePermissionState(File file) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        this.file = file;
        load();
    }

    synchronized boolean isGranted(int userId, String packageName, String permission) {
        return grants.contains(Key.create(userId, packageName, permission));
    }

    synchronized boolean setGranted(int userId, String packageName, String permission,
                                    boolean granted) throws IOException {
        Key key = Key.create(userId, packageName, permission);
        Set<Key> next = new HashSet<>(grants);
        boolean changed = granted ? next.add(key) : next.remove(key);
        if (!changed) {
            return false;
        }
        write(next);
        grants.clear();
        grants.addAll(next);
        damaged = false;
        return true;
    }

    synchronized void clearPackageUser(int userId, String packageName) throws IOException {
        Key.validate(userId, packageName, "placeholder.permission");
        Set<Key> next = new HashSet<>(grants);
        boolean changed = false;
        for (Key key : new HashSet<>(next)) {
            if (key.userId == userId && key.packageName.equals(packageName)) {
                next.remove(key);
                changed = true;
            }
        }
        if (changed) {
            write(next);
            grants.clear();
            grants.addAll(next);
        }
    }

    synchronized void clearUser(int userId) throws IOException {
        if (userId < 0) {
            throw new IllegalArgumentException("userId must be non-negative");
        }
        Set<Key> next = new HashSet<>(grants);
        boolean changed = false;
        for (Key key : new HashSet<>(next)) {
            if (key.userId == userId) {
                next.remove(key);
                changed = true;
            }
        }
        if (changed) {
            write(next);
            grants.clear();
            grants.addAll(next);
        }
    }

    synchronized boolean isDamaged() {
        return damaged;
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        Set<Key> loaded = new HashSet<>();
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Unexpected runtime-permission file magic");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_ENTRIES) {
                throw new IOException("Invalid runtime-permission entry count: " + count);
            }
            for (int i = 0; i < count; i++) {
                loaded.add(Key.create(input.readInt(), input.readUTF(), input.readUTF()));
            }
            if (input.read() != -1) {
                throw new IOException("Trailing runtime-permission data");
            }
            grants.addAll(loaded);
        } catch (IOException | IllegalArgumentException failure) {
            // A corrupt decision file must never broaden guest access.
            grants.clear();
            damaged = true;
        }
    }

    private void write(Set<Key> next) throws IOException {
        File parent = file.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            throw new IOException("Unable to create runtime-permission directory");
        }
        File pending = new File(parent, file.getName() + ".next");
        try (FileOutputStream fileOutput = new FileOutputStream(pending);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(fileOutput))) {
            output.writeInt(MAGIC);
            output.writeInt(next.size());
            for (Key key : next) {
                output.writeInt(key.userId);
                output.writeUTF(key.packageName);
                output.writeUTF(key.permission);
            }
            output.flush();
            fileOutput.getFD().sync();
        }
        if (!pending.renameTo(file)) {
            pending.delete();
            throw new IOException("Unable to activate runtime-permission decisions");
        }
        PackageInstallTransaction.syncDirectory(parent);
    }

    private static final class Key {
        final int userId;
        final String packageName;
        final String permission;

        private Key(int userId, String packageName, String permission) {
            this.userId = userId;
            this.packageName = packageName;
            this.permission = permission;
        }

        static Key create(int userId, String packageName, String permission) {
            validate(userId, packageName, permission);
            return new Key(userId, packageName, permission);
        }

        static void validate(int userId, String packageName, String permission) {
            if (userId < 0 || packageName == null || packageName.isEmpty()
                    || permission == null || permission.isEmpty()) {
                throw new IllegalArgumentException("Invalid runtime-permission identity");
            }
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key)) {
                return false;
            }
            Key that = (Key) other;
            return userId == that.userId
                    && packageName.equals(that.packageName)
                    && permission.equals(that.permission);
        }

        @Override
        public int hashCode() {
            int result = userId;
            result = 31 * result + packageName.hashCode();
            return 31 * result + permission.hashCode();
        }
    }
}
