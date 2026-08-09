package com.lody.virtual.server.pm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.lody.virtual.helper.utils.FileUtils;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NewPackageInstallRollbackTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void persistenceFailureRemovesNewCodeSplitsParserCacheOdexAndMemoryCache()
            throws Exception {
        File appDir = temporaryFolder.newFolder("new-package");
        write(new File(appDir, "base.apk"));
        write(new File(appDir, "config.arm64_v8a.apk"));
        write(new File(appDir, "package.ini"));
        File odex = temporaryFolder.newFile("new-package.odex");
        FakeOperations operations = new FakeOperations();
        operations.cachePresent = true;
        NewPackageInstallRollback rollback = NewPackageInstallRollback.begin(
                "com.example.app", appDir, odex, operations);

        rollback.rollback();

        assertFalse(appDir.exists());
        assertFalse(odex.exists());
        assertFalse(operations.cachePresent);
    }

    @Test
    public void persistenceFailureRestoresPackageSetSeenAfterRestart() throws Exception {
        File packageList = temporaryFolder.newFile("package-list.ini");
        write(packageList, "existing.package");
        PackageCodeRollback persistenceRollback = PackageCodeRollback.begin(packageList);
        write(packageList, "existing.package,new.package");
        File appDir = temporaryFolder.newFolder("failed-new-package");
        write(new File(appDir, "base.apk"));
        FakeOperations operations = new FakeOperations();
        operations.cachePresent = true;
        NewPackageInstallRollback installRollback = NewPackageInstallRollback.begin(
                "new.package", appDir, new File(appDir, "base.odex"), operations);

        // Model saveOrThrow failing after the candidate package set was written.
        persistenceRollback.rollback();
        installRollback.rollback();

        assertEquals("existing.package", read(packageList));
        assertFalse(appDir.exists());
        assertFalse(operations.cachePresent);
    }

    @Test
    public void successfulPersistenceCommitKeepsFirstInstallArtifacts() throws Exception {
        File appDir = temporaryFolder.newFolder("committed-package");
        write(new File(appDir, "base.apk"));
        File odex = temporaryFolder.newFile("committed-package.odex");
        FakeOperations operations = new FakeOperations();
        operations.cachePresent = true;
        NewPackageInstallRollback rollback = NewPackageInstallRollback.begin(
                "com.example.app", appDir, odex, operations);

        rollback.commit();
        rollback.rollback();

        assertTrue(appDir.exists());
        assertTrue(odex.exists());
        assertTrue(operations.cachePresent);
    }

    private static void write(File file) throws Exception {
        write(file, "fixture");
    }

    private static void write(File file, String content) throws Exception {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
        }
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static final class FakeOperations implements NewPackageInstallRollback.Operations {
        boolean cachePresent;

        @Override
        public boolean exists(File file) {
            return file.exists();
        }

        @Override
        public boolean delete(File file) {
            return FileUtils.deleteDir(file);
        }

        @Override
        public void removePackageCache(String packageName) {
            cachePresent = false;
        }
    }
}
