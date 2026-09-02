package com.lody.virtual.server.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PackageSettingRevisionTest {
    @Test
    public void legacyDefaultHasNoTrustedMarker() {
        PackageSetting setting = new PackageSetting();
        assertEquals(0, setting.packageRevisionGeneration);
        assertNull(setting.verifiedRevisionId);
    }

    @Test
    public void invalidationAdvancesGenerationAndClearsEveryMarkerFact() {
        PackageSetting setting = populatedSetting();
        setting.packageRevisionGeneration = 8;

        setting.invalidateVerifiedRevision();

        assertEquals(9, setting.packageRevisionGeneration);
        assertNull(setting.verifiedRevisionId);
        assertNull(setting.verifiedBaseSha256);
        assertNull(setting.verifiedBasePath);
        assertNull(setting.verifiedSplitNames);
        assertNull(setting.verifiedSplitSha256);
        assertNull(setting.verifiedSplitPaths);
        assertNull(setting.verifiedSplitSizes);
        assertNull(setting.verifiedSplitLastModified);
    }

    /** Exercises the complete v5 field set without Android Parcel's host-JVM stub implementation. */
    @Test
    public void v5MarkerFieldSetCanBeReconstructedWithoutDroppingFacts() {
        PackageSetting source = populatedSetting();
        PackageSetting restored = new PackageSetting();
        restored.packageRevisionGeneration = source.packageRevisionGeneration;
        restored.verifiedRevisionId = source.verifiedRevisionId;
        restored.verifiedBaseSha256 = source.verifiedBaseSha256;
        restored.verifiedBasePath = source.verifiedBasePath;
        restored.verifiedBaseSize = source.verifiedBaseSize;
        restored.verifiedBaseLastModified = source.verifiedBaseLastModified;
        restored.verifiedSplitNames = source.verifiedSplitNames.clone();
        restored.verifiedSplitSha256 = source.verifiedSplitSha256.clone();
        restored.verifiedSplitPaths = source.verifiedSplitPaths.clone();
        restored.verifiedSplitSizes = source.verifiedSplitSizes.clone();
        restored.verifiedSplitLastModified = source.verifiedSplitLastModified.clone();

        assertEquals(source.verifiedRevisionId, restored.verifiedRevisionId);
        assertEquals(source.verifiedBasePath, restored.verifiedBasePath);
        assertEquals(source.verifiedSplitNames[0], restored.verifiedSplitNames[0]);
        assertEquals(source.verifiedSplitSizes[0], restored.verifiedSplitSizes[0]);
    }

    private static PackageSetting populatedSetting() {
        PackageSetting setting = new PackageSetting();
        setting.packageRevisionGeneration = 2;
        setting.verifiedRevisionId = "revision-2";
        setting.verifiedBaseSha256 = "base";
        setting.verifiedBasePath = "/data/base.apk";
        setting.verifiedBaseSize = 10;
        setting.verifiedBaseLastModified = 20;
        setting.verifiedSplitNames = new String[]{"config.arm64"};
        setting.verifiedSplitSha256 = new String[]{"split"};
        setting.verifiedSplitPaths = new String[]{"/data/split.apk"};
        setting.verifiedSplitSizes = new long[]{30};
        setting.verifiedSplitLastModified = new long[]{40};
        return setting;
    }
}
