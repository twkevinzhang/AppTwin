package com.lody.virtual.server.pm.parser;

import static org.junit.Assert.assertThrows;

import com.lody.virtual.remote.TrustedPackageProvenance;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

public class TrustedSignatureOverridePolicyTest {
    private static final byte[] EFFECTIVE_SIGNATURE = new byte[]{1, 2, 3, 4};
    private static final String BASE = digest(11);
    private static final String REAL_SIGNER = digest(22);

    private final TrustedSignatureOverridePolicy policy =
            new TrustedSignatureOverridePolicy(
                    TrustedSignatureOverridePolicy.sha256(EFFECTIVE_SIGNATURE),
                    123,
                    Arrays.asList(REAL_SIGNER),
                    BASE,
                    splits("config.arm64_v8a", digest(33)));

    @Test
    public void acceptsOnlyExactTrustedArtifactIdentity() {
        policy.authorize(facts(TrustedSignatureOverridePolicy.TRUSTED_PACKAGE, 123, BASE,
                        REAL_SIGNER, splits("config.arm64_v8a", digest(33))),
                provenance(TrustedSignatureOverridePolicy.TRUSTED_PACKAGE, 123, BASE,
                        REAL_SIGNER, splits("config.arm64_v8a", digest(33)),
                        EFFECTIVE_SIGNATURE));
    }

    @Test
    public void rejectsArbitraryPackageEvenWhenAllDigestsMatch() {
        assertThrows(SecurityException.class, () -> policy.authorize(
                facts("evil.fake.gms", 123, BASE, REAL_SIGNER, Collections.emptyMap()),
                provenance("evil.fake.gms", 123, BASE, REAL_SIGNER,
                        Collections.emptyMap(), EFFECTIVE_SIGNATURE)));
    }

    @Test
    public void rejectsVersionSignerBaseAndSplitTampering() {
        TrustedPackageProvenance trusted = provenance(
                TrustedSignatureOverridePolicy.TRUSTED_PACKAGE, 123, BASE, REAL_SIGNER,
                splits("config.arm64_v8a", digest(33)), EFFECTIVE_SIGNATURE);

        assertThrows(SecurityException.class, () -> policy.authorize(
                facts(TrustedSignatureOverridePolicy.TRUSTED_PACKAGE, 124, BASE, REAL_SIGNER,
                        splits("config.arm64_v8a", digest(33))), trusted));
        assertThrows(SecurityException.class, () -> policy.authorize(
                facts(TrustedSignatureOverridePolicy.TRUSTED_PACKAGE, 123, BASE, digest(99),
                        splits("config.arm64_v8a", digest(33))), trusted));
        assertThrows(SecurityException.class, () -> policy.authorize(
                facts(TrustedSignatureOverridePolicy.TRUSTED_PACKAGE, 123, digest(98), REAL_SIGNER,
                        splits("config.arm64_v8a", digest(33))), trusted));
        assertThrows(SecurityException.class, () -> policy.authorize(
                facts(TrustedSignatureOverridePolicy.TRUSTED_PACKAGE, 123, BASE, REAL_SIGNER,
                        splits("config.arm64_v8a", digest(97))), trusted));
        assertThrows(SecurityException.class, () -> policy.authorize(
                facts(TrustedSignatureOverridePolicy.TRUSTED_PACKAGE, 123, BASE, REAL_SIGNER,
                        Collections.emptyMap()), trusted));
    }

    @Test
    public void rejectsCallerSelectedEffectiveSignature() {
        assertThrows(SecurityException.class, () -> policy.authorize(
                facts(TrustedSignatureOverridePolicy.TRUSTED_PACKAGE, 123, BASE, REAL_SIGNER,
                        Collections.emptyMap()),
                provenance(TrustedSignatureOverridePolicy.TRUSTED_PACKAGE, 123, BASE,
                        REAL_SIGNER, Collections.emptyMap(), new byte[]{9, 9, 9})));
    }

    @Test
    public void productionPolicyPinsReviewedMicrogRelease() {
        TrustedSignatureOverridePolicy production = new TrustedSignatureOverridePolicy();
        byte[] placeholderCompatibilityCertificate = new byte[]{1};
        // The checked-in release identity cannot be replaced merely by changing the manifest.
        assertThrows(SecurityException.class, () -> production.authorize(
                facts(TrustedSignatureOverridePolicy.TRUSTED_PACKAGE,
                        TrustedSignatureOverridePolicy.PINNED_MICROG_VERSION_CODE + 1,
                        TrustedSignatureOverridePolicy.PINNED_MICROG_BASE_APK_SHA256,
                        TrustedSignatureOverridePolicy.PINNED_MICROG_SIGNER_SHA256,
                        Collections.emptyMap()),
                provenance(TrustedSignatureOverridePolicy.TRUSTED_PACKAGE,
                        TrustedSignatureOverridePolicy.PINNED_MICROG_VERSION_CODE + 1,
                        TrustedSignatureOverridePolicy.PINNED_MICROG_BASE_APK_SHA256,
                        TrustedSignatureOverridePolicy.PINNED_MICROG_SIGNER_SHA256,
                        Collections.emptyMap(), placeholderCompatibilityCertificate)));
    }

    @Test
    public void productionAllowlistContainsOnlyGmsCoreAndPinnedCompanion() {
        org.junit.Assert.assertTrue(TrustedSignatureOverridePolicy.isTrustedPackage(
                TrustedSignatureOverridePolicy.TRUSTED_PACKAGE));
        org.junit.Assert.assertTrue(TrustedSignatureOverridePolicy.isTrustedPackage(
                TrustedSignatureOverridePolicy.TRUSTED_COMPANION_PACKAGE));
        org.junit.Assert.assertFalse(TrustedSignatureOverridePolicy.isTrustedPackage(
                "com.google.android.gsf"));
        org.junit.Assert.assertFalse(TrustedSignatureOverridePolicy.isTrustedPackage(
                "org.attacker.vending"));
    }

    @Test
    public void rejectsMissingManifestAndSignerAllowlist() {
        TrustedPackageProvenance noManifest = new TrustedPackageProvenance(
                "", TrustedSignatureOverridePolicy.TRUSTED_PACKAGE, 123,
                Arrays.asList(REAL_SIGNER), BASE, Collections.emptyMap(), EFFECTIVE_SIGNATURE);
        TrustedPackageProvenance noSigner = new TrustedPackageProvenance(
                "fixture", TrustedSignatureOverridePolicy.TRUSTED_PACKAGE, 123,
                Collections.emptyList(), BASE, Collections.emptyMap(), EFFECTIVE_SIGNATURE);
        TrustedSignatureOverridePolicy.ArtifactFacts facts = facts(
                TrustedSignatureOverridePolicy.TRUSTED_PACKAGE, 123, BASE, REAL_SIGNER,
                Collections.emptyMap());

        assertThrows(SecurityException.class, () -> policy.authorize(facts, noManifest));
        assertThrows(SecurityException.class, () -> policy.authorize(facts, noSigner));
    }

    @Test
    public void copiedPrivateApkIsRehashedSoSourceSwapCannotActivateDifferentBytes() throws Exception {
        Path reviewedSource = Files.createTempFile("microg-reviewed", ".apk");
        Path activatedPrivateCopy = Files.createTempFile("microg-activated", ".apk");
        Files.write(reviewedSource, new byte[]{1, 2, 3});
        Files.write(activatedPrivateCopy, new byte[]{9, 9, 9});
        TrustedPackageProvenance provenance = new TrustedPackageProvenance(
                "fixture", TrustedSignatureOverridePolicy.TRUSTED_PACKAGE, 123,
                Arrays.asList(REAL_SIGNER),
                TrustedSignatureOverridePolicy.sha256(reviewedSource.toFile()),
                Collections.emptyMap(), EFFECTIVE_SIGNATURE);

        assertThrows(SecurityException.class, () ->
                TrustedSignatureOverridePolicy.verifyInstalledCopy(
                        activatedPrivateCopy.toFile(), null, null, provenance));
    }

    private static TrustedSignatureOverridePolicy.ArtifactFacts facts(
            String packageName, int version, String base, String signer,
            Map<String, String> splits) {
        return new TrustedSignatureOverridePolicy.ArtifactFacts(
                packageName, version, Arrays.asList(signer), base, splits);
    }

    private static TrustedPackageProvenance provenance(
            String packageName, int version, String base, String signer,
            Map<String, String> splits, byte[] effectiveSignature) {
        return new TrustedPackageProvenance(
                "fixture-manifest", packageName, version, Arrays.asList(signer), base, splits,
                effectiveSignature);
    }

    private static Map<String, String> splits(String name, String digest) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        result.put(name, digest);
        return result;
    }

    private static String digest(int value) {
        return String.format("%064x", value);
    }
}
