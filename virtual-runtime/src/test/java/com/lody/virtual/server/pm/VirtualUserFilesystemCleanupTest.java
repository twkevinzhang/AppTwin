package com.lody.virtual.server.pm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class VirtualUserFilesystemCleanupTest {
    @Test
    public void completeTreeIsGoneBeforeCallerMayReleaseUserId() throws Exception {
        File root = Files.createTempDirectory("apptwin-user-cleanup").toFile();
        File nested = new File(root, "package/files");
        if (!nested.mkdirs()) throw new IOException("fixture");
        if (!new File(nested, "token").createNewFile()) throw new IOException("fixture");

        VUserManagerService.removeDirectoryRecursiveOrThrow(root);

        assertFalse(root.exists());
    }

    @Test
    public void guestReadOnlyDirectoryIsMadeOwnerWritableBeforeDeletion() throws Exception {
        File root = Files.createTempDirectory("apptwin-readonly-cleanup").toFile();
        File extractedLibraries = new File(root, "package/lib-compressed");
        if (!extractedLibraries.mkdirs()) throw new IOException("fixture");
        File library = new File(extractedLibraries, "libguest.so");
        assertTrue(library.createNewFile());
        assertTrue(extractedLibraries.setWritable(false, true));

        try {
            VUserManagerService.removeDirectoryRecursiveOrThrow(root);
            assertFalse(root.exists());
        } finally {
            if (extractedLibraries.exists()) extractedLibraries.setWritable(true, true);
            if (root.exists()) VUserManagerService.removeDirectoryRecursiveOrThrow(root);
        }
    }

    @Test
    public void externalStorageUnavailableIsReportedFailClosed() {
        assertThrows(IOException.class,
                () -> VUserManagerService.requireExternalFilesRoot(null));
    }

    @Test
    public void symlinksAndDanglingSymlinksAreDeletedWithoutFollowingForeignTarget()
            throws Exception {
        File root = Files.createTempDirectory("apptwin-delete-root").toFile();
        File foreign = Files.createTempDirectory("apptwin-foreign-group").toFile();
        File sentinel = new File(foreign, "must-survive");
        assertTrue(sentinel.createNewFile());
        Files.createSymbolicLink(new File(root, "foreign-link").toPath(), foreign.toPath());
        Files.createSymbolicLink(
                new File(root, "dangling-link").toPath(),
                new File(foreign, "missing-target").toPath());

        VUserManagerService.removeDirectoryRecursiveOrThrow(root);

        assertFalse(root.exists());
        assertTrue(sentinel.exists());
        VUserManagerService.removeDirectoryRecursiveOrThrow(foreign);
    }
}
