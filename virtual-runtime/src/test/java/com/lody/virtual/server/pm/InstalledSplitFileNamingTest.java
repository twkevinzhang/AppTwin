package com.lody.virtual.server.pm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class InstalledSplitFileNamingTest {

    @Test
    public void privateSplitFileName_matchesAndroidInstalledSplitConvention() {
        assertEquals("split_boost.apk", VAppManagerService.privateSplitFileName("boost"));
        assertEquals(
                "split_config.arm64_v8a.apk",
                VAppManagerService.privateSplitFileName("config.arm64_v8a"));
    }
}
