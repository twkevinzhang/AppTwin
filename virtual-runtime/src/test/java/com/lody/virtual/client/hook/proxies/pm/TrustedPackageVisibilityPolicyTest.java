package com.lody.virtual.client.hook.proxies.pm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TrustedPackageVisibilityPolicyTest {

    @Test
    public void reservedSupplementalGidsRequireCurrentVirtualBinding() {
        assertFalse(TrustedPackageVisibilityPolicy.allowSupplementalGids(
                "com.google.android.gms", false));
        assertFalse(TrustedPackageVisibilityPolicy.allowSupplementalGids(
                "com.android.vending", false));
        assertTrue(TrustedPackageVisibilityPolicy.allowSupplementalGids(
                "com.google.android.gms", true));
        assertTrue(TrustedPackageVisibilityPolicy.allowSupplementalGids(
                "com.example.fixture", false));
    }
    @Test
    public void physicalGmsIsNeverAVisibilityFallbackForAnyVirtualUser() {
        assertFalse(TrustedPackageVisibilityPolicy.allowPhysicalFallback("com.google.android.gms"));
        assertFalse(TrustedPackageVisibilityPolicy.allowPhysicalFallback("com.android.vending"));
        assertFalse(TrustedPackageVisibilityPolicy.allowPhysicalFallback("com.google.android.gsf"));
        assertTrue(TrustedPackageVisibilityPolicy.allowPhysicalFallback("com.android.settings"));
    }

    @Test
    public void groupAInstalledAndGroupBAbsentDoNotSharePhysicalGmsVisibility() {
        assertTrue(TrustedPackageVisibilityPolicy.isAvailableToVirtualUser(
                "com.google.android.gms", true, true));
        assertFalse(TrustedPackageVisibilityPolicy.isAvailableToVirtualUser(
                "com.google.android.gms", false, true));
        assertFalse(TrustedPackageVisibilityPolicy.isAvailableToVirtualUser(
                "com.android.vending", false, true));
    }
}
