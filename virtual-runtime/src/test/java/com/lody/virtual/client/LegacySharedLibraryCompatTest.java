package com.lody.virtual.client;

import static org.junit.Assert.assertEquals;

import java.io.File;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LegacySharedLibraryCompatTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void includesTestBaseAndPrefersApacheBootJar() throws Exception {
        File testBase = temporaryFolder.newFile("android.test.base.jar");
        File apacheBoot = temporaryFolder.newFile("org.apache.http.legacy.boot.jar");
        File apacheFallback = temporaryFolder.newFile("org.apache.http.legacy.jar");

        assertEquals(
                testBase.getAbsolutePath()
                        + File.pathSeparator
                        + apacheBoot.getAbsolutePath(),
                LegacySharedLibraryCompat.buildDelegatePath(
                        testBase, apacheBoot, apacheFallback));
    }

    @Test
    public void usesApacheFallbackAndSkipsMissingFiles() throws Exception {
        File missingTestBase = new File(temporaryFolder.getRoot(), "missing-test-base.jar");
        File missingApacheBoot = new File(temporaryFolder.getRoot(), "missing-apache-boot.jar");
        File apacheFallback = temporaryFolder.newFile("org.apache.http.legacy.jar");
        assertEquals(
                apacheFallback.getAbsolutePath(),
                LegacySharedLibraryCompat.buildDelegatePath(
                        missingTestBase,
                        missingApacheBoot,
                        apacheFallback));
    }
}
