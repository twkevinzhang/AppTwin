package org.apptwin.gms.artifacts

private val SHA256 = Regex("[0-9a-f]{64}")
private val SAFE_TOKEN = Regex("[A-Za-z0-9._+-]{1,96}")

enum class GmsArtifactKind(val requiredPackageName: String) {
    GMS_CORE("com.google.android.gms"),
    GSF_PROXY("com.google.android.gsf"),
    FAKE_STORE("com.android.vending"),
}

/** Real, non-spoofed identity observed from the staged APK. */
data class GmsArtifactIdentity(
    val kind: GmsArtifactKind,
    val packageName: String,
    val versionCode: Long,
    val realSignerSha256: String,
    val apkSha256: String,
) {
    init {
        require(packageName == kind.requiredPackageName) {
            "${kind.name} must use ${kind.requiredPackageName}"
        }
        require(versionCode > 0) { "versionCode must be positive" }
        require(SHA256.matches(realSignerSha256)) { "real signer digest must be lowercase SHA-256" }
        require(SHA256.matches(apkSha256)) { "APK digest must be lowercase SHA-256" }
    }
}

data class GmsReleaseIdentity(
    val releaseId: String,
    val microGVersion: String,
    val manifestSha256: String,
    val manifestSignerSha256: String,
) {
    init {
        require(SAFE_TOKEN.matches(releaseId)) { "releaseId must be a stable token" }
        require(SAFE_TOKEN.matches(microGVersion)) { "microGVersion must be a stable token" }
        require(SHA256.matches(manifestSha256)) { "manifest digest must be lowercase SHA-256" }
        require(SHA256.matches(manifestSignerSha256)) {
            "manifest signer digest must be lowercase SHA-256"
        }
    }
}

data class TrustedGmsArtifact(
    val identity: GmsArtifactIdentity,
    /** Compatibility signature exposed only by the virtual PackageManager. */
    val exposedCompatibilitySignatureSha256: String? = null,
) {
    init {
        require(
            exposedCompatibilitySignatureSha256 == null ||
                SHA256.matches(exposedCompatibilitySignatureSha256),
        ) { "compatibility signature digest must be lowercase SHA-256" }
        require(
            identity.kind in setOf(GmsArtifactKind.GMS_CORE, GmsArtifactKind.FAKE_STORE) ||
                exposedCompatibilitySignatureSha256 == null,
        ) { "only GMS Core and the pinned FakeStore companion may expose a compatibility signature" }
    }
}

/**
 * Host-trusted allowlist. Adapters must authenticate this manifest before constructing this type;
 * guest manifest metadata never grants signature spoofing authority.
 */
data class TrustedGmsManifest(
    val release: GmsReleaseIdentity,
    val artifacts: List<TrustedGmsArtifact>,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long? = null,
) {
    init {
        require(issuedAtEpochMillis >= 0) { "issuedAtEpochMillis must not be negative" }
        require(expiresAtEpochMillis == null || expiresAtEpochMillis > issuedAtEpochMillis) {
            "manifest expiry must follow issue time"
        }
        require(artifacts.isNotEmpty()) { "manifest must contain artifacts" }
        require(artifacts.any { it.identity.kind == GmsArtifactKind.GMS_CORE }) {
            "manifest must contain GMS Core"
        }
        require(artifacts.map { it.identity.kind }.distinct().size == artifacts.size) {
            "manifest must contain at most one artifact of each kind"
        }
        require(artifacts.map { it.identity.packageName }.distinct().size == artifacts.size) {
            "manifest must not contain duplicate package names"
        }
    }

    fun isExpired(nowEpochMillis: Long): Boolean =
        expiresAtEpochMillis?.let { nowEpochMillis >= it } ?: false
}

enum class ArtifactTrustFailure {
    RELEASE_MISMATCH,
    COMPONENT_NOT_ALLOWLISTED,
    PACKAGE_MISMATCH,
    VERSION_MISMATCH,
    SIGNER_MISMATCH,
    APK_DIGEST_MISMATCH,
    MANIFEST_EXPIRED,
}

sealed interface ArtifactTrustDecision {
    data object Trusted : ArtifactTrustDecision
    data class Rejected(val failure: ArtifactTrustFailure) : ArtifactTrustDecision
}

object GmsArtifactTrustPolicy {
    fun verify(
        candidateReleaseId: String,
        candidate: GmsArtifactIdentity,
        manifest: TrustedGmsManifest,
        nowEpochMillis: Long,
    ): ArtifactTrustDecision {
        if (candidateReleaseId != manifest.release.releaseId) {
            return ArtifactTrustDecision.Rejected(ArtifactTrustFailure.RELEASE_MISMATCH)
        }
        if (manifest.isExpired(nowEpochMillis)) {
            return ArtifactTrustDecision.Rejected(ArtifactTrustFailure.MANIFEST_EXPIRED)
        }
        val trusted = manifest.artifacts.firstOrNull { it.identity.kind == candidate.kind }
            ?: return ArtifactTrustDecision.Rejected(ArtifactTrustFailure.COMPONENT_NOT_ALLOWLISTED)
        return when {
            candidate.packageName != trusted.identity.packageName ->
                ArtifactTrustDecision.Rejected(ArtifactTrustFailure.PACKAGE_MISMATCH)
            candidate.versionCode != trusted.identity.versionCode ->
                ArtifactTrustDecision.Rejected(ArtifactTrustFailure.VERSION_MISMATCH)
            candidate.realSignerSha256 != trusted.identity.realSignerSha256 ->
                ArtifactTrustDecision.Rejected(ArtifactTrustFailure.SIGNER_MISMATCH)
            candidate.apkSha256 != trusted.identity.apkSha256 ->
                ArtifactTrustDecision.Rejected(ArtifactTrustFailure.APK_DIGEST_MISMATCH)
            else -> ArtifactTrustDecision.Trusted
        }
    }
}
