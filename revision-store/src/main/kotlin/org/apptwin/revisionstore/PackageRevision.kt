package org.apptwin.revisionstore

import org.apptwin.packagesource.PackageSourceSnapshot

enum class RevisionState {
    STAGED,
    ACTIVE,
    RETIRED,
}

/** A package revision record. Instance data is intentionally not part of this object. */
data class PackageRevision(
    val id: String,
    val source: PackageSourceSnapshot,
    val createdAtEpochMillis: Long,
    val state: RevisionState,
)

sealed class StageResult {
    data class Staged(val revision: PackageRevision) : StageResult()
    data class Rejected(val reason: RejectionReason) : StageResult()
}

enum class RejectionReason {
    VERSION_ROLLBACK,
    INCOMPATIBLE_SIGNING_LINEAGE,
}
