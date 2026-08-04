package com.lody.virtual.server.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileWriter;
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
}
