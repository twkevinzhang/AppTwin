package com.lody.virtual.server.pm.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageInfo;

import com.lody.virtual.remote.TrustedPackageProvenance;
import com.lody.virtual.server.pm.PackageSetting;
import com.lody.virtual.server.pm.PackageUserState;

import org.junit.Test;

import java.util.Collections;

public class TrustedGmsCompatibilityVersionPolicyTest {

    private static final int ACTUAL_VERSION = 250_932_030;

    @Test
    public void facadesOnlyReturnedInfoForInstalledTrustedGms() {
        VPackage parsedPackage = trustedGms(ACTUAL_VERSION);
        PackageUserState installed = state(true);
        PackageInfo output = packageInfo(ACTUAL_VERSION);
        TrustedPackageProvenance parsedProvenance = parsedPackage.trustedPackageProvenance;
        TrustedPackageProvenance persistedProvenance =
                ((PackageSetting) parsedPackage.mExtras).trustedPackageProvenance;

        assertTrue(TrustedGmsCompatibilityVersionPolicy.isEligible(parsedPackage, installed));
        TrustedGmsCompatibilityVersionPolicy.apply(output, parsedPackage, installed);

        assertEquals(TrustedGmsCompatibilityVersionPolicy.MIN_COMPATIBILITY_VERSION_CODE,
                output.versionCode);
        assertEquals(ACTUAL_VERSION, parsedPackage.mVersionCode);
        assertEquals(ACTUAL_VERSION, parsedProvenance.versionCode);
        assertEquals(ACTUAL_VERSION, persistedProvenance.versionCode);
        assertSame(parsedProvenance, parsedPackage.trustedPackageProvenance);
        assertSame(persistedProvenance,
                ((PackageSetting) parsedPackage.mExtras).trustedPackageProvenance);
    }

    @Test
    public void neverDowngradesNewerTrustedGms() {
        int newerVersion = TrustedGmsCompatibilityVersionPolicy.MIN_COMPATIBILITY_VERSION_CODE + 1;
        VPackage parsedPackage = trustedGms(newerVersion);
        PackageInfo output = packageInfo(newerVersion);

        TrustedGmsCompatibilityVersionPolicy.apply(output, parsedPackage, state(true));

        assertEquals(newerVersion, output.versionCode);
        assertEquals(newerVersion,
                TrustedGmsCompatibilityVersionPolicy.effectiveVersionCode(newerVersion));
    }

    @Test
    public void rejectsUninstalledUserAndUntrustedPackageNameCollision() {
        VPackage trusted = trustedGms(ACTUAL_VERSION);
        PackageInfo uninstalledOutput = packageInfo(ACTUAL_VERSION);

        assertFalse(TrustedGmsCompatibilityVersionPolicy.isEligible(
                trusted, state(false)));
        TrustedGmsCompatibilityVersionPolicy.apply(
                uninstalledOutput, trusted, state(false));
        assertEquals(ACTUAL_VERSION, uninstalledOutput.versionCode);

        VPackage untrusted = trustedGms(ACTUAL_VERSION);
        untrusted.trustedPackageProvenance = null;
        ((PackageSetting) untrusted.mExtras).trustedPackageProvenance = null;
        PackageInfo untrustedOutput = packageInfo(ACTUAL_VERSION);

        assertFalse(TrustedGmsCompatibilityVersionPolicy.isEligible(
                untrusted, state(true)));
        TrustedGmsCompatibilityVersionPolicy.apply(
                untrustedOutput, untrusted, state(true));
        assertEquals(ACTUAL_VERSION, untrustedOutput.versionCode);
    }

    @Test
    public void rejectsMismatchedArtifactOrProvenanceIdentity() {
        VPackage wrongArtifact = trustedGms(ACTUAL_VERSION);
        wrongArtifact.packageName = "com.example.fake.gms";
        assertFalse(TrustedGmsCompatibilityVersionPolicy.isEligible(
                wrongArtifact, state(true)));

        VPackage mismatchedVersion = trustedGms(ACTUAL_VERSION);
        mismatchedVersion.trustedPackageProvenance = provenance(ACTUAL_VERSION - 1);
        assertFalse(TrustedGmsCompatibilityVersionPolicy.isEligible(
                mismatchedVersion, state(true)));

        VPackage missingSetting = trustedGms(ACTUAL_VERSION);
        missingSetting.mExtras = null;
        assertFalse(TrustedGmsCompatibilityVersionPolicy.isEligible(
                missingSetting, state(true)));
    }

    private static VPackage trustedGms(int versionCode) {
        VPackage parsedPackage = new VPackage();
        parsedPackage.packageName = TrustedSignatureOverridePolicy.TRUSTED_PACKAGE;
        parsedPackage.mVersionCode = versionCode;
        parsedPackage.trustedPackageProvenance = provenance(versionCode);
        PackageSetting setting = new PackageSetting();
        setting.packageName = parsedPackage.packageName;
        setting.trustedPackageProvenance = provenance(versionCode);
        parsedPackage.mExtras = setting;
        return parsedPackage;
    }

    private static TrustedPackageProvenance provenance(int versionCode) {
        return new TrustedPackageProvenance(
                "microg-fixture",
                TrustedSignatureOverridePolicy.TRUSTED_PACKAGE,
                versionCode,
                Collections.singletonList(digest('a')),
                digest('b'),
                Collections.emptyMap(),
                new byte[]{1, 2, 3});
    }

    private static String digest(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }

    private static PackageUserState state(boolean installed) {
        PackageUserState state = new PackageUserState();
        state.installed = installed;
        return state;
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo packageInfo(int versionCode) {
        PackageInfo info = new PackageInfo();
        info.packageName = TrustedSignatureOverridePolicy.TRUSTED_PACKAGE;
        info.versionCode = versionCode;
        return info;
    }
}
