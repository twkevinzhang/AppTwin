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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PackageCodeRollbackTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void rollbackRestoresExactPreviousRevision() throws Exception {
        File codeDir = temporaryFolder.newFolder("package");
        write(new File(codeDir, "base.apk"), "old revision");

        PackageCodeRollback rollback = PackageCodeRollback.begin(codeDir);
        assertFalse(codeDir.exists());
        assertTrue(codeDir.mkdirs());
        write(new File(codeDir, "base.apk"), "partial new revision");

        rollback.rollback();

        assertEquals("old revision", read(new File(codeDir, "base.apk")));
    }

    @Test
    public void copyFailureRestoresBaseEverySplitAndPackageMetadata() throws Exception {
        File codeDir = temporaryFolder.newFolder("split-package");
        write(new File(codeDir, "base.apk"), "old base");
        write(new File(codeDir, "config.en.apk"), "old en split");
        write(new File(codeDir, "config.arm64_v8a.apk"), "old abi split");
        write(new File(codeDir, "package.ini"), "old parsed package");

        PackageCodeRollback rollback = PackageCodeRollback.begin(codeDir);
        assertTrue(codeDir.mkdirs());
        write(new File(codeDir, "base.apk"), "new base");
        write(new File(codeDir, "config.en.apk"), "partial new split");

        rollback.rollback();

        assertEquals("old base", read(new File(codeDir, "base.apk")));
        assertEquals("old en split", read(new File(codeDir, "config.en.apk")));
        assertEquals("old abi split", read(new File(codeDir, "config.arm64_v8a.apk")));
        assertEquals("old parsed package", read(new File(codeDir, "package.ini")));
    }

    @Test
    public void commitFailureRestoresCompleteBaseAndSplitRevision() throws Exception {
        File codeDir = temporaryFolder.newFolder("commit-failure-package");
        write(new File(codeDir, "base.apk"), "old base");
        write(new File(codeDir, "feature.apk"), "old feature");
        write(new File(codeDir, "package.ini"), "old parsed package");

        PackageCodeRollback rollback = PackageCodeRollback.begin(codeDir);
        assertTrue(codeDir.mkdirs());
        write(new File(codeDir, "base.apk"), "new base");
        write(new File(codeDir, "feature.apk"), "new feature");
        write(new File(codeDir, "package.ini"), "new parsed package");

        // A persistence/cache commit fails after all new files have been staged.
        rollback.rollback();

        assertEquals("old base", read(new File(codeDir, "base.apk")));
        assertEquals("old feature", read(new File(codeDir, "feature.apk")));
        assertEquals("old parsed package", read(new File(codeDir, "package.ini")));
    }

    @Test
    public void equalVersionUserBindingPersistenceFailureRestoresInstalledUserSetOnly()
            throws Exception {
        File codeDir = temporaryFolder.newFolder("equal-version-code");
        File base = new File(codeDir, "base.apk");
        write(base, "unchanged shared code");
        File packageList = temporaryFolder.newFile("equal-version-package-list.ini");
        write(packageList, "com.example.app users=0,8");

        // Equal-version scoped install snapshots settings only, not the shared code directory.
        PackageCodeRollback rollback = PackageCodeRollback.begin(packageList);
        write(packageList, "com.example.app users=0,7,8");
        rollback.rollback();

        assertEquals("com.example.app users=0,8", read(packageList));
        assertEquals("unchanged shared code", read(base));
        assertTrue(codeDir.exists());
    }

    @Test
    public void rollbackRestoresCodeDirectoryEvenWhenExternalOdexRestoreFails() throws Exception {
        File codeDir = temporaryFolder.newFolder("package-with-odex");
        File odexFile = temporaryFolder.newFile("base.odex");
        write(new File(codeDir, "base.apk"), "old base");
        write(new File(codeDir, "feature.apk"), "old feature");
        write(odexFile, "old odex");
        FailingDeleteOperations operations = new FailingDeleteOperations(odexFile);

        PackageCodeRollback rollback = PackageCodeRollback.begin(
                operations, codeDir, odexFile);
        assertTrue(codeDir.mkdirs());
        write(new File(codeDir, "base.apk"), "partial new base");
        write(odexFile, "partial new odex");

        try {
            rollback.rollback();
            fail("Expected external odex rollback failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Unable to remove failed update"));
        }

        assertEquals("old base", read(new File(codeDir, "base.apk")));
        assertEquals("old feature", read(new File(codeDir, "feature.apk")));
    }

    @Test
    public void commitKeepsNewRevisionAndDeletesSnapshot() throws Exception {
        File codeDir = temporaryFolder.newFolder("package");
        write(new File(codeDir, "base.apk"), "old revision");

        PackageCodeRollback rollback = PackageCodeRollback.begin(codeDir);
        assertTrue(codeDir.mkdirs());
        write(new File(codeDir, "base.apk"), "new revision");
        rollback.commit();

        assertEquals("new revision", read(new File(codeDir, "base.apk")));
        assertEquals(1, temporaryFolder.getRoot().listFiles().length);
    }

    private static void write(File file, String content) throws Exception {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static final class FailingDeleteOperations implements PackageCodeRollback.FileOperations {
        private final File deleteFailureTarget;

        FailingDeleteOperations(File deleteFailureTarget) {
            this.deleteFailureTarget = deleteFailureTarget;
        }

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
            if (file.equals(deleteFailureTarget)) {
                return false;
            }
            return com.lody.virtual.helper.utils.FileUtils.deleteDir(file);
        }
    }
}
