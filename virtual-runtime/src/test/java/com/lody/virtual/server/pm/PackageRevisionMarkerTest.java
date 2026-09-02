package com.lody.virtual.server.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PackageRevisionMarkerTest {
    @Test
    public void codeMutationAdvancesGenerationAndClearsEveryMarkerField() {
        PackageSetting setting = new PackageSetting();
        setting.packageRevisionGeneration = 7L;
        setting.verifiedRevisionId = "revision";
        setting.verifiedBaseSha256 = "base";
        setting.verifiedBasePath = "/base.apk";
        setting.verifiedBaseSize = 1L;
        setting.verifiedBaseLastModified = 2L;
        setting.verifiedSplitNames = new String[] {"config.arm64_v8a"};
        setting.verifiedSplitSha256 = new String[] {"split"};
        setting.verifiedSplitPaths = new String[] {"/split.apk"};
        setting.verifiedSplitSizes = new long[] {3L};
        setting.verifiedSplitLastModified = new long[] {4L};

        setting.invalidateVerifiedRevision();

        assertEquals(8L, setting.packageRevisionGeneration);
        assertNull(setting.verifiedRevisionId);
        assertNull(setting.verifiedBaseSha256);
        assertNull(setting.verifiedBasePath);
        assertNull(setting.verifiedSplitNames);
        assertNull(setting.verifiedSplitSha256);
        assertNull(setting.verifiedSplitPaths);
        assertNull(setting.verifiedSplitSizes);
        assertNull(setting.verifiedSplitLastModified);
    }
}
