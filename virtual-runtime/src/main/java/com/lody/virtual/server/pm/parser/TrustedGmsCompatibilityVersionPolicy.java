package com.lody.virtual.server.pm.parser;

import android.content.pm.PackageInfo;
import android.os.Build;

import com.lody.virtual.remote.TrustedPackageProvenance;
import com.lody.virtual.server.pm.PackageSetting;
import com.lody.virtual.server.pm.PackageUserState;

/**
 * Guest-visible version facade for the pinned trusted GmsCore artifact.
 *
 * <p>The installed APK, parsed package cache, provenance and update comparisons retain the real
 * artifact version. Only a newly generated {@link PackageInfo} returned by the virtual package
 * manager is adjusted, so compatibility checks cannot weaken trusted install authorization.</p>
 */
final class TrustedGmsCompatibilityVersionPolicy {

    static final int MIN_COMPATIBILITY_VERSION_CODE = 263_005_000;

    private TrustedGmsCompatibilityVersionPolicy() {
    }

    static void apply(PackageInfo output, VPackage parsedPackage, PackageUserState userState) {
        if (output == null || !isEligible(parsedPackage, userState)) {
            return;
        }
        int compatibilityVersion = effectiveVersionCode(parsedPackage.mVersionCode);
        output.versionCode = compatibilityVersion;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            output.setLongVersionCode(compatibilityVersion);
        }
    }

    static int effectiveVersionCode(int actualVersionCode) {
        return Math.max(actualVersionCode, MIN_COMPATIBILITY_VERSION_CODE);
    }

    static boolean isEligible(VPackage parsedPackage, PackageUserState userState) {
        if (parsedPackage == null
                || userState == null
                || !userState.installed
                || !TrustedSignatureOverridePolicy.TRUSTED_PACKAGE.equals(
                        parsedPackage.packageName)
                || !(parsedPackage.mExtras instanceof PackageSetting)) {
            return false;
        }

        PackageSetting setting = (PackageSetting) parsedPackage.mExtras;
        TrustedPackageProvenance persisted = setting.trustedPackageProvenance;
        TrustedPackageProvenance parsed = parsedPackage.trustedPackageProvenance;
        return persisted != null
                && parsed != null
                && TrustedSignatureOverridePolicy.TRUSTED_PACKAGE.equals(persisted.packageName)
                && TrustedSignatureOverridePolicy.TRUSTED_PACKAGE.equals(parsed.packageName)
                && persisted.versionCode == parsedPackage.mVersionCode
                && parsed.versionCode == parsedPackage.mVersionCode;
    }
}
