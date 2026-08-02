package org.maskaccounts.packagesource

/** A verified file belonging to one installed package revision. */
data class PackageArtifact(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    init {
        require(path.isNotBlank()) { "Artifact path must not be blank" }
        require(sizeBytes > 0) { "Artifact must not be empty" }
        require(SHA_256.matches(sha256)) { "Artifact SHA-256 must be 64 lowercase hex characters" }
    }

    private companion object {
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}

/** A split APK plus the split name Android reports for it. */
data class SplitArtifact(
    val splitName: String,
    val artifact: PackageArtifact,
) {
    init {
        require(splitName.isNotBlank()) { "Split name must not be blank" }
    }
}

/**
 * Immutable description of the main-system app imported as a package source.
 *
 * [requiredSplitNames] is captured independently from [splitApks] so an interrupted copy cannot
 * accidentally be treated as a complete revision.
 */
data class PackageSourceSnapshot(
    val packageName: String,
    val versionCode: Long,
    val signingCertificateLineageSha256: List<String>,
    val baseApk: PackageArtifact,
    val requiredSplitNames: Set<String>,
    val splitApks: List<SplitArtifact>,
    val supportedAbis: Set<String>,
) {
    init {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid Android package name: $packageName" }
        require(versionCode > 0) { "Version code must be positive" }
        require(signingCertificateLineageSha256.isNotEmpty()) {
            "At least one signing certificate is required"
        }
        require(signingCertificateLineageSha256.all(SHA_256::matches)) {
            "Signing certificate digests must be 64 lowercase hex characters"
        }
        require(signingCertificateLineageSha256.distinct().size == signingCertificateLineageSha256.size) {
            "Signing certificate lineage must not contain duplicates"
        }
        require(supportedAbis.none(String::isBlank)) { "ABI names must not be blank" }

        val actualSplitNames = splitApks.map(SplitArtifact::splitName)
        require(actualSplitNames.distinct().size == actualSplitNames.size) {
            "Split APK names must be unique"
        }
        require(actualSplitNames.toSet() == requiredSplitNames) {
            "Split APK set is incomplete or contains unexpected splits"
        }
    }

    val currentSignerSha256: String
        get() = signingCertificateLineageSha256.last()

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}
