package com.lody.virtual.helper.compat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PackageParserCompatTest {
    @Test
    public void legacyPackageUserStateIsRequiredThroughApi28() {
        assertTrue(PackageParserCompat.LegacyUserStateCompat.isRequired(17));
        assertTrue(PackageParserCompat.LegacyUserStateCompat.isRequired(21));
        assertTrue(PackageParserCompat.LegacyUserStateCompat.isRequired(28));
    }

    @Test
    public void legacyPackageUserStateIsNotRequiredOnApi29AndNewer() {
        assertFalse(PackageParserCompat.LegacyUserStateCompat.isRequired(16));
        assertFalse(PackageParserCompat.LegacyUserStateCompat.isRequired(29));
        assertFalse(PackageParserCompat.LegacyUserStateCompat.isRequired(37));
    }
}
