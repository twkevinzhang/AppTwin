package com.lody.virtual.server.pm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PackageRevisionFileFactsTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void exactFactsMatchAndSizeOrMtimeMismatchFailsClosed() throws Exception {
        File apk = temporary.newFile("base.apk");
        try (FileOutputStream output = new FileOutputStream(apk)) {
            output.write(new byte[]{1, 2, 3});
        }
        String path = apk.getAbsolutePath();
        long size = apk.length();
        long modified = apk.lastModified();

        assertTrue(PackageRevisionFileFacts.matches(path, size, modified, apk));
        assertFalse(PackageRevisionFileFacts.matches(path, size + 1, modified, apk));
        assertFalse(PackageRevisionFileFacts.matches(path, size, modified + 1, apk));
        assertFalse(PackageRevisionFileFacts.matches(path + ".other", size, modified, apk));
    }

    @Test
    public void missingFileNeverMatches() throws Exception {
        File missing = new File(temporary.getRoot(), "missing.apk");
        assertFalse(PackageRevisionFileFacts.matches(
                missing.getAbsolutePath(), 0, 0, missing));
    }

    @Test
    public void splitSetOrDigestMismatchFailsClosed() {
        String[] names = new String[]{"config.arm64", "config.zh"};
        String[] digests = new String[]{"a", "b"};
        assertTrue(PackageRevisionFileFacts.sameSplitIdentity(
                names, digests, names.clone(), digests.clone()));
        assertFalse(PackageRevisionFileFacts.sameSplitIdentity(
                names, digests, new String[]{"config.arm64"}, new String[]{"a"}));
        assertFalse(PackageRevisionFileFacts.sameSplitIdentity(
                names, digests, names.clone(), new String[]{"a", "changed"}));
    }
}
