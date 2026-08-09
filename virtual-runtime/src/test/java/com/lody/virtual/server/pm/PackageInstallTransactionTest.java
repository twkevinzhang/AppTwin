package com.lody.virtual.server.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PackageInstallTransactionTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void startupRecoveryRestoresAppDirectorySettingsAndExternalOdexAfterProcessDeath()
            throws Exception {
        File root = temporaryFolder.newFolder("virtual");
        File appDir = new File(root, "data/app/com.example.app");
        assertTrue(appDir.mkdirs());
        write(new File(appDir, "base.apk"), "old base");
        write(new File(appDir, "config.en.apk"), "old split");
        File settings = file(root, "data/app/system/packages.ini", "old package set");
        File odex = file(root, "opt/com.example.app.odex", "old odex");
        File journal = new File(root, "data/app/system/package-install-transaction");

        PackageInstallTransaction.begin(
                journal,
                root,
                Arrays.asList(appDir, settings, odex),
                Collections.emptyList());
        assertFalse(appDir.exists());
        assertFalse(settings.exists());
        assertFalse(odex.exists());
        assertTrue(appDir.mkdirs());
        write(new File(appDir, "base.apk"), "partial new base");
        write(settings, "candidate package set");
        write(odex, "partial new odex");

        // Abandon the transaction object to model process death, then run startup recovery.
        PackageInstallTransaction.recover(journal, root);

        assertEquals("old base", read(new File(appDir, "base.apk")));
        assertEquals("old split", read(new File(appDir, "config.en.apk")));
        assertEquals("old package set", read(settings));
        assertEquals("old odex", read(odex));
        assertFalse(new File(journal.getPath() + ".active").exists());
    }

    @Test
    public void startupRecoveryDeletesFirstInstallArtifactsWhoseOriginalsWereAbsent()
            throws Exception {
        File root = temporaryFolder.newFolder("new-install-virtual");
        File settings = file(root, "data/app/system/packages.ini", "existing package set");
        File appDir = new File(root, "data/app/com.example.newapp");
        File odex = new File(root, "opt/com.example.newapp.odex");
        File journal = new File(root, "data/app/system/package-install-transaction");

        PackageInstallTransaction.begin(
                journal,
                root,
                Collections.singletonList(settings),
                Arrays.asList(appDir, odex));
        assertTrue(appDir.mkdirs());
        write(new File(appDir, "base.apk"), "new base");
        write(settings, "existing package set,new package");
        write(odex, "new odex");

        PackageInstallTransaction.recover(journal, root);

        assertEquals("existing package set", read(settings));
        assertFalse(appDir.exists());
        assertFalse(odex.exists());
    }

    @Test
    public void committedMarkerKeepsCandidateAndFinishesBackupCleanupAfterProcessDeath()
            throws Exception {
        File root = temporaryFolder.newFolder("committed-virtual");
        File settings = file(root, "data/app/system/packages.ini", "old package set");
        File journal = new File(root, "data/app/system/package-install-transaction");

        PackageInstallTransaction.begin(
                journal,
                root,
                Collections.singletonList(settings),
                Collections.emptyList());
        write(settings, "committed package set");
        File active = new File(journal.getPath() + ".active");
        File committed = new File(journal.getPath() + ".committed");
        assertTrue(active.renameTo(committed));

        PackageInstallTransaction.recover(journal, root);

        assertEquals("committed package set", read(settings));
        assertFalse(committed.exists());
        assertEquals(0, settings.getParentFile().listFiles((dir, name) ->
                name.startsWith(settings.getName() + ".rollback-")).length);
    }

    @Test
    public void rollbackAfterCommitMarkerRenameRestoresOriginalSettings() throws Exception {
        File root = temporaryFolder.newFolder("commit-marker-rollback-virtual");
        File settings = file(root, "data/app/system/packages.ini", "old package set");
        File journal = new File(root, "data/app/system/package-install-transaction");

        PackageInstallTransaction transaction = PackageInstallTransaction.begin(
                journal,
                root,
                Collections.singletonList(settings),
                Collections.emptyList());
        write(settings, "candidate package set");
        File active = new File(journal.getPath() + ".active");
        File committed = new File(journal.getPath() + ".committed");
        assertTrue(active.renameTo(committed));

        transaction.rollback();

        assertEquals("old package set", read(settings));
        assertFalse(active.exists());
        assertFalse(committed.exists());
    }

    @Test
    public void equalVersionBindingPersistenceFailureRestoresFlagAndGlobalSettings()
            throws Exception {
        File root = temporaryFolder.newFolder("binding-failure-virtual");
        File settings = file(root, "data/app/system/packages.ini", "user7=false");
        File journal = new File(root, "data/app/system/package-install-transaction");
        PackageInstallTransaction transaction = PackageInstallTransaction.begin(
                journal,
                root,
                Collections.singletonList(settings),
                Collections.emptyList());
        boolean[] installed = {false};

        try {
            EqualVersionUserBinding.bind(
                    new EqualVersionUserBinding.InstalledState() {
                        @Override
                        public boolean isInstalled(int userId) {
                            return installed[0];
                        }

                        @Override
                        public void setInstalled(int userId, boolean value) {
                            installed[0] = value;
                        }
                    },
                    7,
                    () -> {
                        Files.write(
                                settings.toPath(),
                                "user7=true".getBytes(StandardCharsets.UTF_8));
                        throw new IOException("settings fsync failed");
                    });
            fail("Expected persistence failure");
        } catch (IOException expected) {
            assertEquals("settings fsync failed", expected.getMessage());
        }
        transaction.rollback();

        assertFalse(installed[0]);
        assertEquals("user7=false", read(settings));
        assertFalse(new File(journal.getPath() + ".active").exists());
    }

    @Test
    public void missingTargetAndBackupFailsClosedWithoutClearingJournal() throws Exception {
        File root = temporaryFolder.newFolder("missing-transaction-virtual");
        File settings = file(root, "data/app/system/packages.ini", "old package set");
        File journal = new File(root, "data/app/system/package-install-transaction");

        PackageInstallTransaction.begin(
                journal,
                root,
                Collections.singletonList(settings),
                Collections.emptyList());
        File backup = settings.getParentFile().listFiles((dir, name) ->
                name.startsWith(settings.getName() + ".rollback-")).length == 1
                ? settings.getParentFile().listFiles((dir, name) ->
                        name.startsWith(settings.getName() + ".rollback-"))[0]
                : null;
        assertTrue(backup != null && backup.delete());

        try {
            PackageInstallTransaction.recover(journal, root);
            fail("Recovery must fail closed");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("both missing"));
        }

        assertTrue(new File(journal.getPath() + ".active").isFile());
    }

    @Test
    public void journalPathOutsideVirtualRootIsRejected() throws Exception {
        File root = temporaryFolder.newFolder("bounded-virtual");
        File outside = temporaryFolder.newFile("outside-packages.ini");
        File journal = new File(root, "package-install-transaction");

        try {
            PackageInstallTransaction.begin(
                    journal,
                    root,
                    Collections.singletonList(outside),
                    Collections.emptyList());
            fail("Path escape must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("escapes virtual root"));
        }
    }

    private static File file(File root, String path, String content) throws Exception {
        File file = new File(root, path);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        write(file, content);
        return file;
    }

    private static void write(File file, String content) throws Exception {
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
