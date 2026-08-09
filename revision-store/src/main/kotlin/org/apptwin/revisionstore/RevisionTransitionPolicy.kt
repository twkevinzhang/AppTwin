package org.apptwin.revisionstore

/**
 * Shared policy for accepting a candidate package revision.
 *
 * Persistence adapters may differ, but every production and test store must make the same
 * downgrade and signing-lineage decision before staging artifacts.
 */
object RevisionTransitionPolicy {
    fun rejectionReason(
        currentVersionCode: Long?,
        currentSignerSha256: String?,
        candidateVersionCode: Long,
        candidateSigningLineageSha256: List<String>,
    ): RejectionReason? = when {
        currentVersionCode != null && candidateVersionCode < currentVersionCode ->
            RejectionReason.VERSION_ROLLBACK

        currentSignerSha256 != null &&
            currentSignerSha256 !in candidateSigningLineageSha256 ->
            RejectionReason.INCOMPATIBLE_SIGNING_LINEAGE

        else -> null
    }
}
