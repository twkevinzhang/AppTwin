package com.lody.virtual.server.pm.parser;

import com.lody.virtual.remote.TrustedPackageProvenance;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Fail-closed authorization for the single package allowed to expose a compatibility signer. */
public final class TrustedSignatureOverridePolicy {
    public static final String TRUSTED_PACKAGE = "com.google.android.gms";
    public static final String TRUSTED_COMPANION_PACKAGE = "com.android.vending";

    // SHA-256 of Google's long-lived Android release certificate used by Play services clients.
    static final String GOOGLE_RELEASE_CERT_SHA256 =
            "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83";
    static final int PINNED_MICROG_VERSION_CODE = 250932030;
    static final String PINNED_MICROG_SIGNER_SHA256 =
            "9bd06727e62796c0130eb6dab39b73157451582cbd138e86c468acc395d14165";
    static final String PINNED_MICROG_BASE_APK_SHA256 =
            "52597e77fd25fdd347574d0457ed1936a4b9561cf4c8d34e7ac8dd8191dfd4b9";
    static final int PINNED_COMPANION_VERSION_CODE = 84022630;
    static final String PINNED_COMPANION_BASE_APK_SHA256 =
            "a973e0235a2829773a4faf36d235d5f703d1c04a2adff674ebaa535a2e78f937";

    private final String requiredEffectiveSignerSha256;
    private final int requiredVersionCode;
    private final List<String> requiredRealSignerSha256;
    private final String requiredBaseApkSha256;
    private final Map<String, String> requiredSplitApkSha256;
    private final boolean productionTwoPackagePolicy;

    public TrustedSignatureOverridePolicy() {
        this(
                GOOGLE_RELEASE_CERT_SHA256,
                PINNED_MICROG_VERSION_CODE,
                Collections.singletonList(PINNED_MICROG_SIGNER_SHA256),
                PINNED_MICROG_BASE_APK_SHA256,
                Collections.emptyMap(),
                true);
    }

    TrustedSignatureOverridePolicy(
            String requiredEffectiveSignerSha256,
            int requiredVersionCode,
            List<String> requiredRealSignerSha256,
            String requiredBaseApkSha256,
            Map<String, String> requiredSplitApkSha256) {
        this(requiredEffectiveSignerSha256, requiredVersionCode, requiredRealSignerSha256,
                requiredBaseApkSha256, requiredSplitApkSha256, false);
    }

    private TrustedSignatureOverridePolicy(
            String requiredEffectiveSignerSha256,
            int requiredVersionCode,
            List<String> requiredRealSignerSha256,
            String requiredBaseApkSha256,
            Map<String, String> requiredSplitApkSha256,
            boolean productionTwoPackagePolicy) {
        this.requiredEffectiveSignerSha256 = normalizeDigest(requiredEffectiveSignerSha256);
        this.requiredVersionCode = requiredVersionCode;
        this.requiredRealSignerSha256 = normalizeList(requiredRealSignerSha256);
        this.requiredBaseApkSha256 = normalizeDigest(requiredBaseApkSha256);
        this.requiredSplitApkSha256 = normalizeMap(requiredSplitApkSha256);
        this.productionTwoPackagePolicy = productionTwoPackagePolicy;
    }

    public void authorize(ArtifactFacts actual, TrustedPackageProvenance trusted)
            throws SecurityException {
        require(trusted != null, "trusted provenance is required");
        require(nonEmpty(trusted.manifestId), "manifest id is required");
        require(isTrustedPackage(trusted.packageName), "package is not allowlisted");
        require(isTrustedPackage(actual.packageName), "parsed package is not allowlisted");
        require(actual.packageName.equals(trusted.packageName), "package identity mismatch");
        int pinnedVersion = requiredVersionCode;
        String pinnedBase = requiredBaseApkSha256;
        if (productionTwoPackagePolicy && TRUSTED_COMPANION_PACKAGE.equals(actual.packageName)) {
            pinnedVersion = PINNED_COMPANION_VERSION_CODE;
            pinnedBase = PINNED_COMPANION_BASE_APK_SHA256;
        } else if (!TRUSTED_PACKAGE.equals(actual.packageName)) {
            throw new SecurityException("package is not enabled by this policy");
        }
        require(actual.versionCode == trusted.versionCode, "version mismatch");
        require(actual.versionCode == pinnedVersion, "version is not runtime-allowlisted");
        require(nonEmpty(trusted.baseApkSha256), "base APK digest is required");
        require(normalizeDigest(trusted.baseApkSha256).equals(actual.baseApkSha256),
                "base APK digest mismatch");
        require(pinnedBase.equals(actual.baseApkSha256),
                "base APK digest is not runtime-allowlisted");
        require(normalizeMap(trusted.splitApkSha256).equals(actual.splitApkSha256),
                "split APK set or digest mismatch");
        require(requiredSplitApkSha256.equals(actual.splitApkSha256),
                "split APK set is not runtime-allowlisted");
        require(!trusted.realSignerSha256.isEmpty(), "real signer allowlist is empty");
        require(normalizeList(trusted.realSignerSha256).equals(actual.realSignerSha256),
                "real signer lineage mismatch");
        require(requiredRealSignerSha256.equals(actual.realSignerSha256),
                "real signer is not runtime-allowlisted");
        require(trusted.effectiveSignature != null && trusted.effectiveSignature.length > 0,
                "effective signature is missing");
        require(requiredEffectiveSignerSha256.equals(sha256(trusted.effectiveSignature)),
                "effective signature is not the pinned Google compatibility signer");
    }

    public static boolean isTrustedPackage(String packageName) {
        return TRUSTED_PACKAGE.equals(packageName)
                || TRUSTED_COMPANION_PACKAGE.equals(packageName);
    }

    public static String sha256(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = digest();
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        }
    }

    public static String sha256(byte[] bytes) {
        MessageDigest digest = digest();
        return hex(digest.digest(bytes));
    }

    /** Re-verifies activated bytes after copying, closing the external-source TOCTOU window. */
    public static void verifyInstalledCopy(
            File baseApk,
            String[] splitNames,
            String[] splitPaths,
            TrustedPackageProvenance trusted) throws IOException {
        require(trusted != null, "trusted provenance is required");
        require(normalizeDigest(trusted.baseApkSha256).equals(sha256(baseApk)),
                "installed base APK digest mismatch");
        LinkedHashMap<String, String> actualSplits = new LinkedHashMap<>();
        if (splitNames != null || splitPaths != null) {
            require(splitNames != null && splitPaths != null
                            && splitNames.length == splitPaths.length,
                    "installed split set is inconsistent");
            for (int i = 0; i < splitNames.length; i++) {
                actualSplits.put(splitNames[i], sha256(new File(splitPaths[i])));
            }
        }
        require(normalizeMap(trusted.splitApkSha256).equals(normalizeMap(actualSplits)),
                "installed split APK digest mismatch");
    }

    static List<String> normalizeList(List<String> digests) {
        ArrayList<String> normalized = new ArrayList<>();
        for (String digest : digests) normalized.add(normalizeDigest(digest));
        Collections.sort(normalized);
        return normalized;
    }

    static Map<String, String> normalizeMap(Map<String, String> digests) {
        ArrayList<String> names = new ArrayList<>(digests.keySet());
        Collections.sort(names);
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (String name : names) {
            require(nonEmpty(name), "split name is empty");
            normalized.put(name, normalizeDigest(digests.get(name)));
        }
        return normalized;
    }

    private static String normalizeDigest(String digest) {
        require(nonEmpty(digest), "digest is missing");
        String normalized = digest.replace(":", "").toLowerCase(Locale.US);
        require(normalized.matches("[0-9a-f]{64}"), "digest must be SHA-256");
        return normalized;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.US, "%02x", value & 0xff));
        return result.toString();
    }

    private static boolean nonEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new SecurityException(reason);
    }

    public static final class ArtifactFacts {
        public final String packageName;
        public final int versionCode;
        public final List<String> realSignerSha256;
        public final String baseApkSha256;
        public final Map<String, String> splitApkSha256;

        public ArtifactFacts(String packageName, int versionCode, List<String> realSignerSha256,
                             String baseApkSha256, Map<String, String> splitApkSha256) {
            this.packageName = packageName;
            this.versionCode = versionCode;
            this.realSignerSha256 = normalizeList(realSignerSha256);
            this.baseApkSha256 = normalizeDigest(baseApkSha256);
            this.splitApkSha256 = normalizeMap(splitApkSha256);
        }
    }
}
