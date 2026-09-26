package com.lody.virtual.client.env;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

public class BackgroundExecutionSettingsTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private File base() { return new File(folder.getRoot(), "setting"); }
    private void write(File file, int... bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            for (int value : bytes) out.write(value);
        }
    }
    @Test public void newInstallationDefaultsOn() { assertTrue(BackgroundExecutionSettings.readEnabled(base())); }
    @Test public void explicitOffAndOnAreFreshReads() throws Exception {
        write(base(), 0); assertFalse(BackgroundExecutionSettings.readEnabled(base()));
        write(base(), 1); assertTrue(BackgroundExecutionSettings.readEnabled(base()));
    }
    @Test public void readerNeverDeletesConcurrentWritersPendingFile() throws Exception {
        write(base(), 0);
        File pending = new File(base().getPath() + ".new");
        write(pending, 1);
        assertFalse(BackgroundExecutionSettings.readEnabled(base()));
        assertTrue(pending.exists());
    }
    @Test public void unfinishedFirstWriteFailsClosedWithoutDeletingIt() throws Exception {
        File pending = new File(base().getPath() + ".new"); write(pending, 1);
        assertFalse(BackgroundExecutionSettings.readEnabled(base()));
        assertTrue(pending.exists());
    }
    @Test public void legacyOffBackupIsReadWithoutMutatingRecoveryState() throws Exception {
        File backup = new File(base().getPath() + ".bak"); write(backup, 0);
        assertFalse(BackgroundExecutionSettings.readEnabled(base()));
        assertTrue(backup.exists()); assertFalse(base().exists());
    }
    @Test public void legacyBackupWinsOverAnUncommittedBase() throws Exception {
        write(base(), 1);
        File backup = new File(base().getPath() + ".bak"); write(backup, 0);
        assertFalse(BackgroundExecutionSettings.readEnabled(base()));
        assertTrue(backup.exists());
    }
    @Test public void corruptRecordFailsClosed() throws Exception {
        write(base(), 1, 0); assertFalse(BackgroundExecutionSettings.readEnabled(base()));
    }
}
