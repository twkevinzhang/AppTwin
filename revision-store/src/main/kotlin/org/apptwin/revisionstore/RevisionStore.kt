package org.apptwin.revisionstore

import org.apptwin.packagesource.PackageSourceSnapshot

interface RevisionStore {
    fun stage(source: PackageSourceSnapshot, createdAtEpochMillis: Long): StageResult
    fun activate(packageName: String, revisionId: String): PackageRevision
    fun active(packageName: String): PackageRevision?
    fun revisions(packageName: String): List<PackageRevision>
}

/**
 * Deterministic reference implementation of revision state transitions.
 *
 * Production persistence will wrap the same transition rules in a filesystem transaction. This
 * in-memory store lets M0 lock down rollback, signing-lineage, and atomic activation behavior first.
 */
class InMemoryRevisionStore : RevisionStore {
    private val records = linkedMapOf<String, MutableList<PackageRevision>>()

    @Synchronized
    override fun stage(source: PackageSourceSnapshot, createdAtEpochMillis: Long): StageResult {
        val current = active(source.packageName)
        if (current != null && source.versionCode < current.source.versionCode) {
            return StageResult.Rejected(RejectionReason.VERSION_ROLLBACK)
        }
        if (current != null &&
            current.source.currentSignerSha256 !in source.signingCertificateLineageSha256
        ) {
            return StageResult.Rejected(RejectionReason.INCOMPATIBLE_SIGNING_LINEAGE)
        }

        val packageRecords = records.getOrPut(source.packageName) { mutableListOf() }
        val ordinal = packageRecords.size + 1
        val revision = PackageRevision(
            id = "${source.packageName}:${source.versionCode}:$ordinal",
            source = source,
            createdAtEpochMillis = createdAtEpochMillis,
            state = RevisionState.STAGED,
        )
        packageRecords += revision
        return StageResult.Staged(revision)
    }

    @Synchronized
    override fun activate(packageName: String, revisionId: String): PackageRevision {
        val packageRecords = records[packageName]
            ?: throw NoSuchElementException("No revisions for $packageName")
        val candidate = packageRecords.firstOrNull { it.id == revisionId }
            ?: throw NoSuchElementException("Unknown revision $revisionId")
        require(candidate.state == RevisionState.STAGED) {
            "Only a staged revision can be activated"
        }

        packageRecords.replaceAll { revision ->
            when {
                revision.id == revisionId -> revision.copy(state = RevisionState.ACTIVE)
                revision.state == RevisionState.ACTIVE -> revision.copy(state = RevisionState.RETIRED)
                else -> revision
            }
        }
        return packageRecords.first { it.id == revisionId }
    }

    @Synchronized
    override fun active(packageName: String): PackageRevision? =
        records[packageName]?.singleOrNull { it.state == RevisionState.ACTIVE }

    @Synchronized
    override fun revisions(packageName: String): List<PackageRevision> =
        records[packageName]?.toList().orEmpty()
}
