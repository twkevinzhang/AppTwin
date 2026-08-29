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
    private static final String LEGACY_SCHEMA_VERSION = "1";
    private static final String SCHEMA_VERSION = "2";
    private static final String KEY_SCHEMA_VERSION = "schemaVersion";
    private static final String KEY_KEYSPACE_ID = "keyspaceId";
    private static final String KEY_OWNER_SPACE_ID = "ownerSpaceId";
    private static final String KEY_PACKAGE_NAME = "packageName";
    private static final String KEY_ARCHIVE_ID = "archiveId";
    private static final String KEY_ARCHIVE_SHA256 = "archiveSha256";
    private static final String KEY_PREVIOUS_OWNER_SPACE_ID = "previousOwnerSpaceId";

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
        write(destination, new Record(keyspaceId, owner, guestPackage, null, null, null));
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
        if (current.hasArchiveReservation()) return false;
        write(file, new Record(keyspace, destination, guestPackage, null, null, null));
        return true;
    }

    synchronized boolean sealArchive(
            String sourceSpaceId,
            String archiveId,
            String packageName,
            String keyspaceId,
            String archiveSha256) {
        String source = KeyspaceValidation.requireCanonicalUuid(sourceSpaceId, "sourceSpaceId");
        String archive = KeyspaceValidation.requireCanonicalUuid(archiveId, "archiveId");
        String keyspace = KeyspaceValidation.requireCanonicalUuid(keyspaceId, "keyspaceId");
        String guestPackage = KeyspaceValidation.requireSupportedPackage(packageName);
        String digest = requireCanonicalSha256(archiveSha256);
        File file = recordFile(keyspace, guestPackage);
        if (!file.isFile()) return false;
        Record current = read(file);
        if (!source.equals(current.ownerSpaceId) || !keyspace.equals(current.keyspaceId)) {
            return false;
        }
        if (current.hasArchiveReservation()) {
            if (archive.equals(current.archiveId)) {
                return digest.equals(current.archiveSha256);
            }
        }
        write(file, new Record(
                keyspace, source, guestPackage, archive, digest, null));
        return true;
    }

    synchronized String resolveArchivedOwner(
            String archiveId,
            String packageName,
            String keyspaceId,
            String archiveSha256) {
        String archive = KeyspaceValidation.requireCanonicalUuid(archiveId, "archiveId");
        String keyspace = KeyspaceValidation.requireCanonicalUuid(keyspaceId, "keyspaceId");
        String guestPackage = KeyspaceValidation.requireSupportedPackage(packageName);
        String digest = requireCanonicalSha256(archiveSha256);
        File file = recordFile(keyspace, guestPackage);
        if (!file.isFile()) return null;
        Record current = read(file);
        return current.matchesArchive(archive, digest) ? current.ownerSpaceId : null;
    }

    synchronized boolean claimArchive(
            String archiveId,
            String currentOwnerSpaceId,
            String destinationSpaceId,
            String packageName,
            String keyspaceId,
            String archiveSha256) {
        String archive = KeyspaceValidation.requireCanonicalUuid(archiveId, "archiveId");
        String currentOwner = KeyspaceValidation.requireCanonicalUuid(
                currentOwnerSpaceId, "currentOwnerSpaceId");
        String destination = KeyspaceValidation.requireCanonicalUuid(
                destinationSpaceId, "destinationSpaceId");
        String keyspace = KeyspaceValidation.requireCanonicalUuid(keyspaceId, "keyspaceId");
        String guestPackage = KeyspaceValidation.requireSupportedPackage(packageName);
        String digest = requireCanonicalSha256(archiveSha256);
        File file = recordFile(keyspace, guestPackage);
        if (!file.isFile()) return false;
        Record current = read(file);
        if (!current.matchesArchive(archive, digest)) return false;

        if (destination.equals(current.ownerSpaceId)) {
            return currentOwner.equals(destination)
                    || currentOwner.equals(current.previousOwnerSpaceId);
        }
        if (destination.equals(currentOwner) || !currentOwner.equals(current.ownerSpaceId)) {
            return false;
        }
        String destinationKeyspace = resolve(destination, guestPackage);
        if (destinationKeyspace != null && !keyspace.equals(destinationKeyspace)) return false;

        write(file, new Record(
                keyspace,
                destination,
                guestPackage,
                archive,
                digest,
                currentOwner));
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
        if (current.hasArchiveReservation()) return false;
        return file.delete();
    }

    private File recordFile(String keyspaceId, String packageName) {
        String canonical = KeyspaceValidation.requireCanonicalUuid(keyspaceId, "keyspaceId");
        KeyspaceValidation.requireSupportedPackage(packageName);
        File directory = new File(root, canonical);
        return new File(directory, packageName + ".properties");
    }

    private Record read(File file) {
        Properties fields = new Properties();
        try (FileInputStream input = new FileInputStream(file)) {
            fields.load(input);
        } catch (IOException failure) {
            throw new IllegalStateException("Custodian record cannot be read");
        }
        String schemaVersion = fields.getProperty(KEY_SCHEMA_VERSION);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                && !LEGACY_SCHEMA_VERSION.equals(schemaVersion)) {
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
        String archiveId = fields.getProperty(KEY_ARCHIVE_ID);
        String archiveSha256 = fields.getProperty(KEY_ARCHIVE_SHA256);
        String previousOwner = fields.getProperty(KEY_PREVIOUS_OWNER_SPACE_ID);
        if (LEGACY_SCHEMA_VERSION.equals(schemaVersion)
                && (archiveId != null || archiveSha256 != null || previousOwner != null)) {
            throw new IllegalStateException("Legacy Custodian record contains archive state");
        }
        if ((archiveId == null) != (archiveSha256 == null)) {
            throw new IllegalStateException("Custodian archive reservation is incomplete");
        }
        if (archiveId != null) {
            archiveId = requireCanonicalUuidFromStore(archiveId, KEY_ARCHIVE_ID);
            archiveSha256 = requireCanonicalSha256FromStore(archiveSha256);
            if (previousOwner != null) {
                previousOwner = requireCanonicalUuidFromStore(
                        previousOwner, KEY_PREVIOUS_OWNER_SPACE_ID);
            }
        } else if (previousOwner != null) {
            throw new IllegalStateException("Custodian archive owner history is inconsistent");
        }
        return new Record(
                keyspace, owner, guestPackage, archiveId, archiveSha256, previousOwner);
    }

    private void write(File destination, Record record) {
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IllegalStateException("Custodian keyspace directory is unavailable");
        }
        Properties fields = new Properties();
        fields.setProperty(KEY_SCHEMA_VERSION, SCHEMA_VERSION);
        fields.setProperty(KEY_KEYSPACE_ID, record.keyspaceId);
        fields.setProperty(KEY_OWNER_SPACE_ID, record.ownerSpaceId);
        fields.setProperty(KEY_PACKAGE_NAME, record.packageName);
        if (record.archiveId != null) {
            fields.setProperty(KEY_ARCHIVE_ID, record.archiveId);
            fields.setProperty(KEY_ARCHIVE_SHA256, record.archiveSha256);
            if (record.previousOwnerSpaceId != null) {
                fields.setProperty(KEY_PREVIOUS_OWNER_SPACE_ID, record.previousOwnerSpaceId);
            }
        }
        File replacement = new File(
                parent, "." + destination.getName() + "-" + UUID.randomUUID());
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

    private static String requireCanonicalSha256(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("archiveSha256 must be lowercase canonical SHA-256");
        }
        return value;
    }

    private static String requireCanonicalSha256FromStore(String value) {
        try {
            return requireCanonicalSha256(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Custodian archive digest is invalid");
        }
    }

    private static String requireCanonicalUuidFromStore(String value, String field) {
        try {
            return KeyspaceValidation.requireCanonicalUuid(value, field);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Custodian " + field + " is invalid");
        }
    }

    private static final class Record {
        final String keyspaceId;
        final String ownerSpaceId;
        final String packageName;
        final String archiveId;
        final String archiveSha256;
        final String previousOwnerSpaceId;

        Record(
                String keyspaceId,
                String ownerSpaceId,
                String packageName,
                String archiveId,
                String archiveSha256,
                String previousOwnerSpaceId) {
            this.keyspaceId = keyspaceId;
            this.ownerSpaceId = ownerSpaceId;
            this.packageName = packageName;
            this.archiveId = archiveId;
            this.archiveSha256 = archiveSha256;
            this.previousOwnerSpaceId = previousOwnerSpaceId;
        }

        boolean hasArchiveReservation() {
            return archiveId != null;
        }

        boolean matchesArchive(String expectedArchiveId, String expectedArchiveSha256) {
            return expectedArchiveId.equals(archiveId)
                    && expectedArchiveSha256.equals(archiveSha256);
        }
    }
}
