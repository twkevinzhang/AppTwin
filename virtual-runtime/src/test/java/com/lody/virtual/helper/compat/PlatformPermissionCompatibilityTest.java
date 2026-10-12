package com.lody.virtual.helper.compat;

import android.content.pm.PackageManager;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlatformPermissionCompatibilityTest {
    private static final String IMAGES = "android.permission.READ_MEDIA_IMAGES";
    @Test public void onlyKnownFutureNamesAreCandidates() {
        assertTrue(PlatformPermissionCompatibility.isKnownFuturePermission(IMAGES, 31));
        assertFalse(PlatformPermissionCompatibility.isKnownFuturePermission(IMAGES, 33));
        assertFalse(PlatformPermissionCompatibility.isKnownFuturePermission("custom.permission.READ_MEDIA_IMAGES", 31));
        assertFalse(PlatformPermissionCompatibility.isKnownFuturePermission("android.permission.READ_MEDIA_IMAGE", 31));
        assertFalse(PlatformPermissionCompatibility.isKnownFuturePermission("android.permission.INTERACT_ACROSS_USERS", 31));
        assertFalse(PlatformPermissionCompatibility.isKnownFuturePermission(null, 31));
    }
    @Test public void onlyKnownFutureMissingLookupIsExempt() {
        PlatformPermissionCompatibility.PlatformLookup missing = () -> {
            throw new PackageManager.NameNotFoundException();
        };
        assertTrue(PlatformPermissionCompatibility.isUnavailableOnPlatform(IMAGES, 31, missing));
        assertFalse(PlatformPermissionCompatibility.isUnavailableOnPlatform(IMAGES, 33, missing));
        assertFalse(PlatformPermissionCompatibility.isUnavailableOnPlatform(
                "custom.permission.READ_MEDIA_IMAGES", 31, missing));
        assertFalse(PlatformPermissionCompatibility.isUnavailableOnPlatform(
                "android.permission.READ_MEDIA_IMAGE", 31, missing));
        assertFalse(PlatformPermissionCompatibility.isUnavailableOnPlatform(
                "android.permission.INTERACT_ACROSS_USERS", 31, missing));
    }
    @Test public void lookupSuccessAndUnexpectedFailuresNeverExempt() {
        assertFalse(PlatformPermissionCompatibility.isUnavailableOnPlatform(IMAGES, 31, () -> {}));
        assertFalse(PlatformPermissionCompatibility.isUnavailableOnPlatform(IMAGES, 31,
                () -> { throw new SecurityException("denied"); }));
        assertFalse(PlatformPermissionCompatibility.isUnavailableOnPlatform(IMAGES, 31,
                () -> { throw new IllegalStateException("unavailable"); }));
    }
    @Test public void supportedNamesDoNotEvenConsultMissingLookup() {
        assertFalse(PlatformPermissionCompatibility.isUnavailableOnPlatform(IMAGES, 33,
                () -> { fail("supported permission cannot be exempt"); }));
    }
    @Test public void modernGrantsCannotBypassLegacyMediaDenialOnAndroid12() {
        assertFalse(PlatformPermissionCompatibility.canReadMedia(31, false, true, true, true, true));
        assertFalse(PlatformPermissionCompatibility.canReadMedia(31, false, true, true, true, false));
        assertTrue(PlatformPermissionCompatibility.canReadMedia(31, true, false, false, true, true));
    }
    @Test public void android13MediaAccessRequiresApplicableGrants() {
        assertFalse(PlatformPermissionCompatibility.canReadMedia(33, false, false, true, true, false));
        assertTrue(PlatformPermissionCompatibility.canReadMedia(33, false, true, false, true, false));
        assertFalse(PlatformPermissionCompatibility.canReadMedia(33, false, true, false, true, true));
    }
}
