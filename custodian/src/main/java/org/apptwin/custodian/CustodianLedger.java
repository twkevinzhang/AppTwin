package org.apptwin.custodian;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.UUID;

/** Durable, fail-closed ownership ledger. It stores no aliases, keys, tokens, or app payload. */
final class CustodianLedger {
    private static final String SCHEMA_VERSION = "1";
    private static final String KEY_SCHEMA_VERSION = "schemaVersion";
    private static final String KEY_KEYSPACE_ID = "keyspaceId";
    private static final String KEY_OWNER_SPACE_ID = "ownerSpaceId";
    private static final String KEY_PACKAGE_NAME = "packageName";

    private final File root;

    CustodianLedger(File filesDir) {
        root = new File(filesDir, "keyspaces");
    }

    synchronized boolean isReady() {
        return root.isDirectory() || root.mkdirs();
    }

    synchronized String register(String spaceId, String packageName) {
        String owner = KeyspaceValidation.requireCanonicalUuid(spaceId, "spaceId");
        String guestPackage = KeyspaceValidation.requireSupportedPackage(packageName);
        String existing = resolve(owner, guestPackage);
        if (existing != null) return existing;

        String keyspaceId = owner;
        File destination = recordFile(keyspaceId, guestPackage);
        if (destination.exists()) {
            Record record = read(destination);
            if (!owner.equals(record.ownerSpaceId)) {
                throw new IllegalStateException("Keyspace is owned by another Space");
            }
            return record.keyspaceId;
        }
        write(destination, new Record(keyspaceId, owner, guestPackage));
        return keyspaceId;
    }

    synchronized String resolve(String spaceId, String packageName) {
        String owner = KeyspaceValidation.requireCanonicalUuid(spaceId, "spaceId");
        String guestPackage = KeyspaceValidation.requireSupportedPackage(packageName);
        if (!isReady()) throw new IllegalStateException("Custodian store is unavailable");
        File[] keyspaces = root.listFiles(File::isDirectory);
        if (keyspaces == null) throw new IllegalStateException("Custodian store cannot be listed");
        String found = null;
        for (File keyspace : keyspaces) {
            File candidate = recordFile(keyspace.getName(), guestPackage);
            if (!candidate.isFile()) continue;
            Record record = read(candidate);
            if (!owner.equals(record.ownerSpaceId)) continue;
            if (found != null && !found.equals(record.keyspaceId)) {
                throw new IllegalStateException("Space owns multiple keyspaces");
            }
            found = record.keyspaceId;
        }
        return found;
    }

    synchronized boolean transfer(
            String sourceSpaceId,
            String destinationSpaceId,
            String packageName,
            String keyspaceId) {
        String source = KeyspaceValidation.requireCanonicalUuid(sourceSpaceId, "sourceSpaceId");
        String destination = KeyspaceValidation.requireCanonicalUuid(
                destinationSpaceId, "destinationSpaceId");
        String keyspace = KeyspaceValidation.requireCanonicalUuid(keyspaceId, "keyspaceId");
        String guestPackage = KeyspaceValidation.requireSupportedPackage(packageName);
        if (source.equals(destination) || resolve(destination, guestPackage) != null) return false;
        File file = recordFile(keyspace, guestPackage);
        if (!file.isFile()) return false;
        Record current = read(file);
        if (!source.equals(current.ownerSpaceId) || !keyspace.equals(current.keyspaceId)) {
            return false;
        }
        write(file, new Record(keyspace, destination, guestPackage));
        return true;
    }

    synchronized boolean release(String spaceId, String packageName, String keyspaceId) {
        String owner = KeyspaceValidation.requireCanonicalUuid(spaceId, "spaceId");
        String keyspace = KeyspaceValidation.requireCanonicalUuid(keyspaceId, "keyspaceId");
        String guestPackage = KeyspaceValidation.requireSupportedPackage(packageName);
        File file = recordFile(keyspace, guestPackage);
        if (!file.isFile()) return false;
        Record current = read(file);
        if (!owner.equals(current.ownerSpaceId) || !keyspace.equals(current.keyspaceId)) return false;
        return file.delete();
    }

    private File recordFile(String keyspaceId, String packageName) {
        String canonical = KeyspaceValidation.requireCanonicalUuid(keyspaceId, "keyspaceId");
        KeyspaceValidation.requireSupportedPackage(packageName);
        File directory = new File(root, canonical);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Custodian keyspace directory is unavailable");
        }
        return new File(directory, packageName + ".properties");
    }

    private Record read(File file) {
        Properties fields = new Properties();
        try (FileInputStream input = new FileInputStream(file)) {
            fields.load(input);
        } catch (IOException failure) {
            throw new IllegalStateException("Custodian record cannot be read");
        }
        if (!SCHEMA_VERSION.equals(fields.getProperty(KEY_SCHEMA_VERSION))) {
            throw new IllegalStateException("Unsupported Custodian schema");
        }
        String keyspace = KeyspaceValidation.requireCanonicalUuid(
                fields.getProperty(KEY_KEYSPACE_ID), KEY_KEYSPACE_ID);
        String owner = KeyspaceValidation.requireCanonicalUuid(
                fields.getProperty(KEY_OWNER_SPACE_ID), KEY_OWNER_SPACE_ID);
        String guestPackage = KeyspaceValidation.requireSupportedPackage(
                fields.getProperty(KEY_PACKAGE_NAME));
        if (!file.getParentFile().getName().equals(keyspace)
                || !file.getName().equals(guestPackage + ".properties")) {
            throw new IllegalStateException("Custodian record path is inconsistent");
        }
        return new Record(keyspace, owner, guestPackage);
    }

    private void write(File destination, Record record) {
        Properties fields = new Properties();
        fields.setProperty(KEY_SCHEMA_VERSION, SCHEMA_VERSION);
        fields.setProperty(KEY_KEYSPACE_ID, record.keyspaceId);
        fields.setProperty(KEY_OWNER_SPACE_ID, record.ownerSpaceId);
        fields.setProperty(KEY_PACKAGE_NAME, record.packageName);
        File replacement = new File(
                destination.getParentFile(), "." + destination.getName() + "-" + UUID.randomUUID());
        try {
            try (FileOutputStream output = new FileOutputStream(replacement)) {
                fields.store(output, "AppTwin Custodian ownership");
                output.getFD().sync();
            }
            Files.move(
                    replacement.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new IllegalStateException("Custodian record cannot be written");
        } finally {
            replacement.delete();
        }
    }

    private static final class Record {
        final String keyspaceId;
        final String ownerSpaceId;
        final String packageName;

        Record(String keyspaceId, String ownerSpaceId, String packageName) {
            this.keyspaceId = keyspaceId;
            this.ownerSpaceId = ownerSpaceId;
            this.packageName = packageName;
        }
    }
}
