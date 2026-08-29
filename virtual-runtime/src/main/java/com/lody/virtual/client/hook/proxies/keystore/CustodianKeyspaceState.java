package com.lody.virtual.client.hook.proxies.keystore;

import com.lody.virtual.os.VEnvironment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.UUID;

/** Host-written activation record consumed by LINE guest processes. */
public final class CustodianKeyspaceState {
    private static final String SCHEMA_VERSION = "1";
    private static final String FILE_NAME = "line-custodian.properties";

    private CustodianKeyspaceState() {
    }

    public static File fileForUser(int userId) {
        return new File(VEnvironment.getUserSystemDirectory(userId), FILE_NAME);
    }

    public static Record readForUser(int userId) {
        return read(fileForUser(userId));
    }

    public static void writeForUser(int userId, String ownerSpaceId, String keyspaceId) {
        write(fileForUser(userId), new Record(ownerSpaceId, keyspaceId));
    }

    static Record read(File file) {
        if (!file.isFile()) return null;
        Properties fields = new Properties();
        try (FileInputStream input = new FileInputStream(file)) {
            fields.load(input);
        } catch (IOException failure) {
            throw new IllegalStateException("Custodian keyspace state cannot be read");
        }
        if (!SCHEMA_VERSION.equals(fields.getProperty("schemaVersion"))) {
            throw new IllegalStateException("Unsupported Custodian keyspace schema");
        }
        return new Record(
                canonicalUuid(fields.getProperty("ownerSpaceId"), "ownerSpaceId"),
                canonicalUuid(fields.getProperty("keyspaceId"), "keyspaceId"));
    }

    static void write(File destination, Record record) {
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IllegalStateException("Custodian keyspace directory is unavailable");
        }
        Properties fields = new Properties();
        fields.setProperty("schemaVersion", SCHEMA_VERSION);
        fields.setProperty("ownerSpaceId", canonicalUuid(record.ownerSpaceId, "ownerSpaceId"));
        fields.setProperty("keyspaceId", canonicalUuid(record.keyspaceId, "keyspaceId"));
        File replacement = new File(parent, "." + FILE_NAME + "-" + UUID.randomUUID());
        try {
            try (FileOutputStream output = new FileOutputStream(replacement)) {
                fields.store(output, "AppTwin LINE Custodian keyspace");
                output.getFD().sync();
            }
            Files.move(
                    replacement.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new IllegalStateException("Custodian keyspace state cannot be written");
        } finally {
            replacement.delete();
        }
    }

    private static String canonicalUuid(String value, String field) {
        if (value == null) throw new IllegalStateException(field + " is missing");
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equals(value)) throw new IllegalStateException(field + " is not canonical");
            return canonical;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(field + " is invalid");
        }
    }

    public static final class Record {
        public final String ownerSpaceId;
        public final String keyspaceId;

        public Record(String ownerSpaceId, String keyspaceId) {
            this.ownerSpaceId = ownerSpaceId;
            this.keyspaceId = keyspaceId;
        }
    }
}
