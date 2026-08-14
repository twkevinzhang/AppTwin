package com.lody.virtual.helper.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BroadcastPackageScopeTest {

    @Test
    public void packageTargetedPushOnlyReachesTargetPackage() {
        assertTrue(BroadcastPackageScope.accepts(
                "jp.naver.line.android", "jp.naver.line.android"));
        assertFalse(BroadcastPackageScope.accepts(
                "jp.naver.line.android", "com.discord"));
    }

    @Test
    public void implicitBroadcastStillReachesMatchingPackages() {
        assertTrue(BroadcastPackageScope.accepts(null, "jp.naver.line.android"));
        assertTrue(BroadcastPackageScope.accepts(null, "com.discord"));
    }
}
