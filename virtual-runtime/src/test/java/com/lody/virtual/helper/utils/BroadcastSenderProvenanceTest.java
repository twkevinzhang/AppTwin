package com.lody.virtual.helper.utils;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Structural contract: sender metadata comes from the bound VClient, not original extras. */
public class BroadcastSenderProvenanceTest {
    @Test
    public void cloneFilterDropsForgedExtrasBeforeTrustedIdentityIsWritten() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/lody/virtual/helper/utils/ComponentUtils.java")),
                StandardCharsets.UTF_8);
        int overload = source.indexOf(
                "String virtualSenderPackage, int virtualSenderVuid)");
        int cloneFilter = source.indexOf("intent.cloneFilter()", overload);
        int writePackage = source.indexOf(
                "BroadcastPackageScope.EXTRA_VIRTUAL_SENDER_PACKAGE", cloneFilter);
        int writeVuid = source.indexOf(
                "BroadcastPackageScope.EXTRA_VIRTUAL_SENDER_VUID", cloneFilter);

        assertTrue(overload >= 0);
        assertTrue(cloneFilter > overload);
        assertTrue(writePackage > cloneFilter);
        assertTrue(writeVuid > cloneFilter);
    }

    @Test
    public void wrapperPathsWithoutBoundClientUseNullSenderOverload() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/lody/virtual/helper/utils/ComponentUtils.java")),
                StandardCharsets.UTF_8);

        assertTrue(source.contains(
                "return redirectBroadcastIntent(intent, userId, null, VUserHandle.USER_NULL);"));
    }
}
