package org.apptwin.revision

import java.io.File

/** Content identity used to compare an immutable active revision with VirtualCore's copied code. */
data class PackageArtifactIdentity(
    val baseSha256: String,
    val splitSha256ByName: Map<String, String>,
) {
    init {
        require(baseSha256.isNotBlank()) { "base artifact digest must not be blank" }
        require(splitSha256ByName.keys.none(String::isBlank)) { "split name must not be blank" }
        require(splitSha256ByName.values.none(String::isBlank)) { "split digest must not be blank" }
    }
}

data class ActiveRuntimeRevision(
    val packageName: String,
    val revisionId: String,
    val directory: File,
    val artifactIdentity: PackageArtifactIdentity,
)

fun interface ActiveRuntimeRevisionProvider {
    fun activeRuntimeRevision(packageName: String): ActiveRuntimeRevision?
}
