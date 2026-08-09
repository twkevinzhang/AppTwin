package com.lody.virtual.server.pm;

import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import com.lody.virtual.helper.utils.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Durable rollback record for the package code and global package-settings transaction. */
final class PackageInstallTransaction {
    private static final int SCHEMA_VERSION = 1;
    private static final String ACTIVE_SUFFIX = ".active";
    private static final String COMMITTED_SUFFIX = ".committed";
    private static final String PENDING_SUFFIX = ".pending";

    private final File journalBase;
    private final File allowedRoot;
    private boolean completed;

    private PackageInstallTransaction(File journalBase, File allowedRoot) {
        this.journalBase = journalBase;
        this.allowedRoot = allowedRoot;
    }

    static PackageInstallTransaction begin(
            File journalBase, File allowedRoot,
            List<File> snapshotTargets, List<File> cleanupTargets)
            throws IOException {
        recover(journalBase, allowedRoot);
        List<Entry> entries = buildEntries(allowedRoot, snapshotTargets, cleanupTargets);
        writeActiveJournal(journalBase, entries);
        try {
            for (Entry entry : entries) {
                if (entry.type == EntryType.RESTORE
                        && !entry.target.renameTo(entry.backup)) {
                    throw new IOException("Unable to snapshot " + entry.target);
                }
                if (entry.type == EntryType.RESTORE) {
                    syncDirectory(entry.target.getParentFile());
                }
            }
            return new PackageInstallTransaction(journalBase, allowedRoot);
        } catch (IOException failure) {
            try {
                recoverActive(journalBase, allowedRoot);
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    static void recover(File journalBase, File allowedRoot) throws IOException {
        File committed = committedJournal(journalBase);
        if (committed.isFile()) {
            finishCommitted(committed, allowedRoot);
        }
        File active = activeJournal(journalBase);
        if (active.isFile()) {
            recoverActive(journalBase, allowedRoot);
        }
        pendingJournal(journalBase).delete();
    }

    void rollback() throws IOException {
        if (completed) return;
        File active = activeJournal(journalBase);
        File committed = committedJournal(journalBase);
        IOException failure = null;
        if (!active.exists() && committed.isFile()) {
            if (!committed.renameTo(active)) {
                failure = new IOException(
                        "Unable to return package transaction to rollback phase");
            } else {
                try {
                    syncDirectory(active.getParentFile());
                } catch (IOException syncFailure) {
                    failure = syncFailure;
                }
            }
        }
        if (active.isFile()) {
            try {
                recoverActive(journalBase, allowedRoot);
            } catch (IOException rollbackFailure) {
                failure = appendFailure(failure, rollbackFailure);
            }
        }
        completed = !active.exists() && !committed.exists();
        if (failure != null) throw failure;
    }

    void commit() throws IOException {
        if (completed) return;
        File active = activeJournal(journalBase);
        File committed = committedJournal(journalBase);
        check(!committed.exists(), "Committed package transaction already exists");
        check(active.renameTo(committed), "Unable to mark package transaction committed");
        syncDirectory(committed.getParentFile());
        completed = true;
        // Cleanup is recoverable from the committed marker and must not turn a committed install
        // into a reported failure.
        try {
            finishCommitted(committed, allowedRoot);
        } catch (IOException ignored) {
        }
    }

    private static List<Entry> buildEntries(
            File allowedRoot,
            List<File> snapshotTargets, List<File> cleanupTargets) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Set<String> recorded = new HashSet<>();
        for (File target : snapshotTargets) {
            if (target == null) continue;
            File canonical = target.getCanonicalFile();
            requireWithinRoot(canonical, allowedRoot);
            if (!recorded.add(canonical.getPath())) continue;
            if (canonical.exists()) {
                entries.add(Entry.restore(canonical, nextBackupFile(canonical)));
            } else {
                entries.add(Entry.delete(canonical));
            }
        }
        for (File target : cleanupTargets) {
            if (target == null) continue;
            File canonical = target.getCanonicalFile();
            requireWithinRoot(canonical, allowedRoot);
            if (recorded.add(canonical.getPath())) entries.add(Entry.delete(canonical));
        }
        return entries;
    }

    private static void writeActiveJournal(File journalBase, List<Entry> entries)
            throws IOException {
        File active = activeJournal(journalBase);
        File pending = pendingJournal(journalBase);
        check(!active.exists(), "Active package transaction already exists");
        Properties properties = new Properties();
        properties.setProperty("schemaVersion", Integer.toString(SCHEMA_VERSION));
        properties.setProperty("entryCount", Integer.toString(entries.size()));
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            properties.setProperty("entry." + i + ".type", entry.type.name());
            properties.setProperty("entry." + i + ".target", entry.target.getPath());
            if (entry.backup != null) {
                properties.setProperty("entry." + i + ".backup", entry.backup.getPath());
            }
        }
        try {
            try (FileOutputStream output = new FileOutputStream(pending)) {
                properties.store(output, "Virtual package install transaction");
                output.getFD().sync();
            }
            check(pending.renameTo(active), "Unable to activate package transaction journal");
            syncDirectory(active.getParentFile());
        } finally {
            pending.delete();
        }
    }

    private static void recoverActive(File journalBase, File allowedRoot) throws IOException {
        File active = activeJournal(journalBase);
        if (!active.isFile()) return;
        List<Entry> entries = readJournal(active, allowedRoot);
        IOException failure = null;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            try {
                if (entry.type == EntryType.DELETE) {
                    deleteIfPresent(entry.target);
                    syncDirectory(entry.target.getParentFile());
                } else if (entry.backup.isFile() || entry.backup.isDirectory()) {
                    deleteIfPresent(entry.target);
                    check(entry.backup.renameTo(entry.target), "Unable to restore " + entry.target);
                    syncDirectory(entry.target.getParentFile());
                } else if (!entry.target.exists()) {
                    throw new IOException(
                            "Package transaction target and backup are both missing: "
                                    + entry.target);
                }
            } catch (IOException entryFailure) {
                failure = appendFailure(failure, entryFailure);
            }
        }
        if (failure != null) throw failure;
        check(active.delete(), "Unable to clear active package transaction journal");
        syncDirectory(active.getParentFile());
    }

    private static void finishCommitted(File committed, File allowedRoot) throws IOException {
        if (!committed.isFile()) return;
        List<Entry> entries = readJournal(committed, allowedRoot);
        IOException failure = null;
        for (Entry entry : entries) {
            if (entry.backup == null) continue;
            try {
                deleteIfPresent(entry.backup);
                syncDirectory(entry.backup.getParentFile());
            } catch (IOException entryFailure) {
                failure = appendFailure(failure, entryFailure);
            }
        }
        if (failure != null) throw failure;
        check(committed.delete(), "Unable to clear committed package transaction journal");
        syncDirectory(committed.getParentFile());
    }

    private static List<Entry> readJournal(File journal, File allowedRoot) throws IOException {
        if (!journal.isFile()) return new ArrayList<>();
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(journal)) {
            properties.load(input);
        }
        int schemaVersion = parseInt(properties, "schemaVersion");
        check(schemaVersion == SCHEMA_VERSION, "Unsupported package transaction schema");
        int count = parseInt(properties, "entryCount");
        check(count >= 0 && count <= 16, "Invalid package transaction entry count");
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            EntryType type;
            try {
                type = EntryType.valueOf(require(properties, "entry." + i + ".type"));
            } catch (IllegalArgumentException failure) {
                throw new IOException("Invalid package transaction entry type", failure);
            }
            File target = new File(require(properties, "entry." + i + ".target")).getCanonicalFile();
            check(target.isAbsolute(), "Package transaction target must be absolute");
            requireWithinRoot(target, allowedRoot);
            if (type == EntryType.RESTORE) {
                File backup = new File(
                        require(properties, "entry." + i + ".backup")).getCanonicalFile();
                check(backup.isAbsolute(), "Package transaction backup must be absolute");
                requireWithinRoot(backup, allowedRoot);
                check(backup.getParentFile().equals(target.getParentFile()),
                        "Package transaction backup must share target parent");
                check(backup.getName().startsWith(target.getName() + ".rollback-"),
                        "Invalid package transaction backup name");
                entries.add(Entry.restore(target, backup));
            } else {
                entries.add(Entry.delete(target));
            }
        }
        return entries;
    }

    private static int parseInt(Properties properties, String key) throws IOException {
        try {
            return Integer.parseInt(require(properties, key));
        } catch (NumberFormatException failure) {
            throw new IOException("Invalid package transaction " + key, failure);
        }
    }

    private static String require(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isEmpty()) throw new IOException("Missing " + key);
        return value;
    }

    private static void deleteIfPresent(File file) throws IOException {
        if (file.exists() && !FileUtils.deleteDir(file)) {
            throw new IOException("Unable to delete " + file);
        }
    }

    static void syncDirectory(File directory) throws IOException {
        // android.jar host tests expose SDK_INT as 0. Device builds are API 21+, where directory
        // fsync makes journal/phase renames durable before live targets are touched or cleaned.
        if (Build.VERSION.SDK_INT == 0) return;
        FileDescriptor descriptor = null;
        try {
            descriptor = Os.open(
                    directory.getAbsolutePath(),
                    OsConstants.O_RDONLY,
                    0);
            Os.fsync(descriptor);
        } catch (ErrnoException failure) {
            throw new IOException("Unable to sync directory " + directory, failure);
        } finally {
            if (descriptor != null) {
                try {
                    Os.close(descriptor);
                } catch (ErrnoException ignored) {
                }
            }
        }
    }

    private static void requireWithinRoot(File file, File allowedRoot) throws IOException {
        File root = allowedRoot.getCanonicalFile();
        String rootPath = root.getPath() + File.separator;
        check(file.equals(root) || file.getPath().startsWith(rootPath),
                "Package transaction path escapes virtual root: " + file);
    }

    private static IOException appendFailure(IOException aggregate, IOException next) {
        if (aggregate == null) return next;
        aggregate.addSuppressed(next);
        return aggregate;
    }

    private static File nextBackupFile(File target) {
        File candidate;
        long suffix = System.nanoTime();
        do {
            candidate = new File(target.getParentFile(),
                    target.getName() + ".rollback-" + suffix++);
        } while (candidate.exists());
        return candidate;
    }

    private static File activeJournal(File base) {
        return new File(base.getPath() + ACTIVE_SUFFIX);
    }

    private static File committedJournal(File base) {
        return new File(base.getPath() + COMMITTED_SUFFIX);
    }

    private static File pendingJournal(File base) {
        return new File(base.getPath() + PENDING_SUFFIX);
    }

    private static void check(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private enum EntryType { RESTORE, DELETE }

    private static final class Entry {
        final EntryType type;
        final File target;
        final File backup;

        static Entry restore(File target, File backup) {
            return new Entry(EntryType.RESTORE, target, backup);
        }

        static Entry delete(File target) {
            return new Entry(EntryType.DELETE, target, null);
        }

        private Entry(EntryType type, File target, File backup) {
            this.type = type;
            this.target = target;
            this.backup = backup;
        }
    }
}
