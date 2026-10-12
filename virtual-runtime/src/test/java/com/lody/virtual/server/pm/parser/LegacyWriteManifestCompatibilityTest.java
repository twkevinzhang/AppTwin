package com.lody.virtual.server.pm.parser;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import static org.junit.Assert.*;

public class LegacyWriteManifestCompatibilityTest {
    private static final String PKG = "org.apptwin.fixture.mediacompat";
    private static final String WRITE = LegacyWriteManifestCompatibility.WRITE;
    @Test public void onlyGenuineFilteredDeclarationOnLegacySdkIsRestored() {
        assertTrue(LegacyWriteManifestCompatibility.shouldRestore(31, PKG, PKG, "uses-permission", WRITE, 29));
        assertTrue(LegacyWriteManifestCompatibility.shouldRestore(32, PKG, PKG, "uses-permission", WRITE, 29));
        assertFalse(LegacyWriteManifestCompatibility.shouldRestore(33, PKG, PKG, "uses-permission", WRITE, 29));
        assertFalse(LegacyWriteManifestCompatibility.shouldRestore(29, PKG, PKG, "uses-permission", WRITE, 29));
    }
    @Test public void missingMalformedOrDifferentDeclarationsDoNotCreatePermission() {
        assertFalse(LegacyWriteManifestCompatibility.shouldRestore(31, PKG, "other.package", "uses-permission", WRITE, 29));
        assertFalse(LegacyWriteManifestCompatibility.shouldRestore(31, PKG, PKG, "uses-permission", WRITE, -1));
        assertFalse(LegacyWriteManifestCompatibility.shouldRestore(31, PKG, PKG, "uses-permission", WRITE, 32));
        assertFalse(LegacyWriteManifestCompatibility.shouldRestore(31, PKG, PKG, "permission", WRITE, 29));
        assertFalse(LegacyWriteManifestCompatibility.shouldRestore(31, PKG, PKG, "uses-permission", "custom.permission.WRITE_EXTERNAL_STORAGE", 29));
        assertFalse(LegacyWriteManifestCompatibility.shouldRestore(31, null, PKG, "uses-permission", WRITE, 29));
    }
    @Test public void sdk23TagMustBeApplicableToTheDevice() {
        assertTrue(LegacyWriteManifestCompatibility.shouldRestore(31, PKG, PKG, "uses-permission-sdk-23", WRITE, 29));
        assertFalse(LegacyWriteManifestCompatibility.shouldRestore(22, PKG, PKG, "uses-permission-sdk-23", WRITE, 21));
    }
    @Test public void cachedUpgradeOnlySynchronizesWriteAndNeverDuplicatesIt() {
        ArrayList<String> cached = new ArrayList<>(Arrays.asList("keep.other.permission"));
        LegacyWriteManifestCompatibility.synchronizeWriteMembership(cached, Arrays.asList(WRITE), 31);
        LegacyWriteManifestCompatibility.synchronizeWriteMembership(cached, Arrays.asList(WRITE), 31);
        assertEquals(Arrays.asList("keep.other.permission", WRITE), cached);
        LegacyWriteManifestCompatibility.synchronizeWriteMembership(cached, new ArrayList<>(), 31);
        assertEquals(Arrays.asList("keep.other.permission"), cached);
    }
    @Test public void failedValidationRemovesStaleWriteButModernCacheIsUntouched() {
        ArrayList<String> cached = new ArrayList<>(Arrays.asList("keep.other.permission", WRITE));
        LegacyWriteManifestCompatibility.synchronizeWriteMembership(cached, null, 33);
        assertTrue(cached.contains(WRITE));
        LegacyWriteManifestCompatibility.synchronizeWriteMembership(cached, null, 31);
        assertEquals(Arrays.asList("keep.other.permission"), cached);
    }
}
