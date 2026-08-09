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
    fun synchronize(revision: ActiveRuntimeRevision, userId: Int) {
        val wasInstalled = gateway.isInstalled(revision.packageName)
        val installedIdentity = if (wasInstalled) {
            gateway.installedArtifactIdentity(revision.packageName)
        } else {
            null
        }
        if (!wasInstalled || installedIdentity != revision.artifactIdentity) {
            val result = gateway.installOrUpdate(revision, update = wasInstalled)
            check(result.isSuccess) { result.error ?: "virtual package install failed" }
            check(
                gateway.installedArtifactIdentity(revision.packageName) == revision.artifactIdentity,
            ) {
                "VirtualCore package identity does not match active revision ${revision.revisionId}"
            }
        }
        check(
            gateway.isInstalledForUser(userId, revision.packageName) ||
                gateway.installForUser(userId, revision.packageName),
        ) { "Unable to add ${revision.packageName} to Group environment" }
    }
}
