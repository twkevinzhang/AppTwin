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
    private final List<Entry> entries;
    private boolean completed;

    private PackageCodeRollback(List<Entry> entries) {
        this.entries = entries;
    }

    static PackageCodeRollback begin(File... targets) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try {
            for (File target : targets) {
                if (target == null || !target.exists()) {
                    continue;
                }
                File backup = nextBackupFile(target);
                if (!target.renameTo(backup)) {
                    throw new IOException("Unable to snapshot " + target);
                }
                entries.add(new Entry(target, backup));
            }
            return new PackageCodeRollback(entries);
        } catch (IOException failure) {
            restoreEntries(entries);
            throw failure;
        }
    }

    void commit() {
        if (completed) {
            return;
        }
        for (Entry entry : entries) {
            if (entry.backup.exists()) {
                // Snapshot cleanup failure must not roll back an otherwise successful install.
                // A later maintenance pass may remove the inert sibling path safely.
                FileUtils.deleteDir(entry.backup);
            }
        }
        completed = true;
    }

    void rollback() throws IOException {
        if (completed) {
            return;
        }
        restoreEntries(entries);
        completed = true;
    }

    private static void restoreEntries(List<Entry> entries) throws IOException {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (entry.target.exists() && !FileUtils.deleteDir(entry.target)) {
                throw new IOException("Unable to remove failed update " + entry.target);
            }
            if (entry.backup.exists() && !entry.backup.renameTo(entry.target)) {
                throw new IOException("Unable to restore " + entry.target);
            }
        }
    }

    private static File nextBackupFile(File target) {
        File parent = target.getParentFile();
        String prefix = target.getName() + ".rollback-";
        long suffix = System.nanoTime();
        File candidate;
        do {
            candidate = new File(parent, prefix + suffix++);
        } while (candidate.exists());
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
