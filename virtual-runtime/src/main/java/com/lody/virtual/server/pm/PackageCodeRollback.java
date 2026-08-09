package com.lody.virtual.server.pm;

import com.lody.virtual.helper.utils.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Renames existing code paths aside before an update, allowing exact restoration on failure.
 * Targets and backups remain on the same filesystem, so each individual rename is atomic.
 */
final class PackageCodeRollback {
    interface FileOperations {
        boolean exists(File file);

        boolean rename(File source, File target);

        boolean delete(File file);
    }

    private static final FileOperations DEFAULT_FILE_OPERATIONS = new FileOperations() {
        @Override
        public boolean exists(File file) {
            return file.exists();
        }

        @Override
        public boolean rename(File source, File target) {
            return source.renameTo(target);
        }

        @Override
        public boolean delete(File file) {
            return FileUtils.deleteDir(file);
        }
    };

    private final List<Entry> entries;
    private final FileOperations fileOperations;
    private boolean completed;

    private PackageCodeRollback(List<Entry> entries, FileOperations fileOperations) {
        this.entries = entries;
        this.fileOperations = fileOperations;
    }

    static PackageCodeRollback begin(File... targets) throws IOException {
        return begin(DEFAULT_FILE_OPERATIONS, targets);
    }

    static PackageCodeRollback begin(FileOperations fileOperations, File... targets)
            throws IOException {
        List<Entry> entries = new ArrayList<>();
        try {
            for (File target : targets) {
                if (target == null || !fileOperations.exists(target)) {
                    continue;
                }
                File backup = nextBackupFile(target, fileOperations);
                if (!fileOperations.rename(target, backup)) {
                    throw new IOException("Unable to snapshot " + target);
                }
                entries.add(new Entry(target, backup));
            }
            return new PackageCodeRollback(entries, fileOperations);
        } catch (IOException failure) {
            try {
                restoreEntries(entries, fileOperations);
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    void commit() {
        if (completed) {
            return;
        }
        for (Entry entry : entries) {
            if (fileOperations.exists(entry.backup)) {
                // Snapshot cleanup failure must not roll back an otherwise successful install.
                // A later maintenance pass may remove the inert sibling path safely.
                fileOperations.delete(entry.backup);
            }
        }
        completed = true;
    }

    void rollback() throws IOException {
        if (completed) {
            return;
        }
        restoreEntries(entries, fileOperations);
        completed = true;
    }

    private static void restoreEntries(List<Entry> entries, FileOperations fileOperations)
            throws IOException {
        IOException failure = null;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (fileOperations.exists(entry.target) && !fileOperations.delete(entry.target)) {
                failure = appendFailure(
                        failure, new IOException("Unable to remove failed update " + entry.target));
                continue;
            }
            if (fileOperations.exists(entry.backup)
                    && !fileOperations.rename(entry.backup, entry.target)) {
                failure = appendFailure(
                        failure, new IOException("Unable to restore " + entry.target));
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static IOException appendFailure(IOException aggregate, IOException next) {
        if (aggregate == null) {
            return next;
        }
        aggregate.addSuppressed(next);
        return aggregate;
    }

    private static File nextBackupFile(File target, FileOperations fileOperations) {
        File parent = target.getParentFile();
        String prefix = target.getName() + ".rollback-";
        long suffix = System.nanoTime();
        File candidate;
        do {
            candidate = new File(parent, prefix + suffix++);
        } while (fileOperations.exists(candidate));
        return candidate;
    }

    private static final class Entry {
        final File target;
        final File backup;

        Entry(File target, File backup) {
            this.target = target;
            this.backup = backup;
        }
    }
}
