package org.apptwin.runtime

import java.io.File
import org.apptwin.revision.ActiveRuntimeRevision
import org.apptwin.revision.PackageArtifactIdentity

object RuntimePackageArtifactReader {
    fun read(
        baseApk: File,
        splitNames: Array<String>,
        splitCodePaths: Array<String>,
        sha256: (File) -> String,
    ): PackageArtifactIdentity {
        require(baseApk.isFile) { "VirtualCore base artifact is missing: ${baseApk.path}" }
        require(splitNames.size == splitCodePaths.size) {
            "VirtualCore split name/path count does not match"
        }
        require(splitNames.toSet().size == splitNames.size) {
            "VirtualCore split names are not unique"
        }
        val splits = splitNames.indices.associate { index ->
            val split = File(splitCodePaths[index])
            require(split.isFile) { "VirtualCore split artifact is missing: ${split.path}" }
            splitNames[index] to sha256(split)
        }
        return PackageArtifactIdentity(sha256(baseApk), splits)
    }
}

interface RuntimePackageGateway {
    fun isInstalled(packageName: String): Boolean
    fun isRevisionVerified(revision: ActiveRuntimeRevision): Boolean
    fun isRevisionReadyForUser(revision: ActiveRuntimeRevision, userId: Int): Boolean
    fun packageRevisionGeneration(packageName: String): Long
    fun recordVerifiedRevision(revision: ActiveRuntimeRevision, expectedGeneration: Long): Boolean
    fun installedArtifactIdentity(packageName: String): PackageArtifactIdentity?
    fun installOrUpdate(revision: ActiveRuntimeRevision, update: Boolean): RuntimePackageInstallResult
    fun isInstalledForUser(userId: Int, packageName: String): Boolean
    fun installForUser(userId: Int, packageName: String): Boolean
}

data class RuntimePackageInstallResult(
    val isSuccess: Boolean,
    val error: String? = null,
)

/** Makes VirtualCore's shared package code match the active immutable revision before launch. */
class RuntimePackageSynchronizer(
    private val gateway: RuntimePackageGateway,
) {
    /** Cheap, read-only launch gate. A miss never hashes or mutates package state. */
    fun isReadyForPureLaunch(revision: ActiveRuntimeRevision, userId: Int): Boolean =
        gateway.isRevisionReadyForUser(revision, userId)

    fun synchronize(revision: ActiveRuntimeRevision, userId: Int) {
        val wasInstalled = gateway.isInstalled(revision.packageName)
        val verified = wasInstalled && gateway.isRevisionVerified(revision)
        val generationBeforeVerification = if (wasInstalled && !verified) {
            gateway.packageRevisionGeneration(revision.packageName)
        } else {
            -1L
        }
        val installedIdentity = if (wasInstalled && !verified) {
            gateway.installedArtifactIdentity(revision.packageName)
        } else null
        if (!wasInstalled || (!verified && installedIdentity != revision.artifactIdentity)) {
            val result = gateway.installOrUpdate(revision, update = wasInstalled)
            check(result.isSuccess) { result.error ?: "virtual package install failed" }
            val installedGeneration = gateway.packageRevisionGeneration(revision.packageName)
            check(
                gateway.installedArtifactIdentity(revision.packageName) == revision.artifactIdentity,
            ) {
                "VirtualCore package identity does not match active revision ${revision.revisionId}"
            }
            check(gateway.recordVerifiedRevision(revision, installedGeneration)) {
                "VirtualCore package changed while recording revision ${revision.revisionId}"
            }
        } else if (!verified) {
            // The full digest matched. Publish the marker only if no install/uninstall changed the
            // generation while hashing; a CAS miss fails closed and the caller may retry.
            check(gateway.recordVerifiedRevision(revision, generationBeforeVerification)) {
                "VirtualCore package changed while recording revision ${revision.revisionId}"
            }
        }
        check(
            gateway.isInstalledForUser(userId, revision.packageName) ||
                gateway.installForUser(userId, revision.packageName),
        ) { "Unable to add ${revision.packageName} to Group environment" }
        // One atomic server observation closes races between the marker, current code stats, and
        // user binding. Any concurrent install/update/uninstall invalidation fails closed here.
        check(gateway.isRevisionReadyForUser(revision, userId)) {
            "VirtualCore package changed before launch ${revision.revisionId}"
        }
    }
}
