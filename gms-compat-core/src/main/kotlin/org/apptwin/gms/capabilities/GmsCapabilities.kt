package org.apptwin.gms.capabilities

import org.apptwin.gms.model.GmsGroupId

enum class GmsCapability {
    PLAY_SERVICES_AVAILABILITY,
    FCM_REGISTRATION,
    FCM_MESSAGE,
    FCM_NOTIFICATION_ROUTING,
    FUSED_LOCATION,
    MAPS_SDK_V2,
    GOOGLE_SIGN_IN_LEGACY,
    GOOGLE_SIGN_IN_GIS,
    CAST_SENDER,
    NEARBY,
    PLAY_BILLING,
    PLAY_INTEGRITY,
}

enum class GmsEvidenceTier(val rank: Int) {
    LOCAL_FIXTURE(1),
    ASUS_FIXTURE(2),
    REAL_EXTERNAL(3),
    THIRD_PARTY_APP(4),
}

enum class GmsEvidenceOutcome {
    PASSED,
    FAILED,
}

data class GmsCapabilityEvidence(
    val evidenceId: String,
    val groupId: GmsGroupId,
    val releaseId: String,
    val capability: GmsCapability,
    val tier: GmsEvidenceTier,
    val outcome: GmsEvidenceOutcome,
    val observedAtEpochMillis: Long,
    val androidApi: Int,
    val appTwinVersion: String,
    val fixtureOrClientVersion: String,
    val failureCode: String? = null,
) {
    init {
        require(EVIDENCE_ID.matches(evidenceId)) { "evidenceId must be a stable token" }
        require(RELEASE_ID.matches(releaseId)) { "releaseId must be a stable token" }
        require(observedAtEpochMillis >= 0) { "observedAtEpochMillis must not be negative" }
        require(androidApi > 0) { "androidApi must be positive" }
        require(SAFE_VERSION.matches(appTwinVersion)) { "appTwinVersion must be a safe token" }
        require(SAFE_VERSION.matches(fixtureOrClientVersion)) {
            "fixtureOrClientVersion must be a safe token"
        }
        require(failureCode == null || STABLE_CODE.matches(failureCode)) {
            "failureCode must be a stable machine-readable code"
        }
        require(outcome == GmsEvidenceOutcome.FAILED || failureCode == null) {
            "failureCode is valid only for failed evidence"
        }
    }

    private companion object {
        val EVIDENCE_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        val RELEASE_ID = Regex("[A-Za-z0-9._+-]{1,96}")
        val SAFE_VERSION = Regex("[A-Za-z0-9._+-]{1,96}")
        val STABLE_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
    }
}

enum class GmsCapabilityStatus {
    /** Product policy: no evidence can upgrade this capability. */
    UNSUPPORTED,
    UNTESTED,
    FIXTURE_PASSED_EXTERNAL_UNTESTED,
    REAL_EXTERNAL_VERIFIED,
    THIRD_PARTY_APP_VERIFIED,
    KNOWN_FAILURE,
}

data class GmsCapabilityAssessment(
    val capability: GmsCapability,
    val status: GmsCapabilityStatus,
    val evidenceTier: GmsEvidenceTier? = null,
    val failureCode: String? = null,
)

object GmsCapabilityPolicy {
    private val permanentlyUnsupported = setOf(
        GmsCapability.PLAY_BILLING,
        GmsCapability.PLAY_INTEGRITY,
    )

    fun evaluate(
        groupId: GmsGroupId,
        capability: GmsCapability,
        activeReleaseId: String,
        evidence: List<GmsCapabilityEvidence>,
    ): GmsCapabilityAssessment {
        if (capability in permanentlyUnsupported) {
            return GmsCapabilityAssessment(capability, GmsCapabilityStatus.UNSUPPORTED)
        }
        val strongest = evidence
            .asSequence()
            .filter {
                it.groupId == groupId &&
                    it.capability == capability &&
                    it.releaseId == activeReleaseId
            }
            .sortedWith(
                compareByDescending<GmsCapabilityEvidence> { it.tier.rank }
                    .thenByDescending { it.observedAtEpochMillis }
                    .thenByDescending { it.evidenceId },
            )
            .firstOrNull()
            ?: return GmsCapabilityAssessment(capability, GmsCapabilityStatus.UNTESTED)
        if (strongest.outcome == GmsEvidenceOutcome.FAILED) {
            return GmsCapabilityAssessment(
                capability = capability,
                status = GmsCapabilityStatus.KNOWN_FAILURE,
                evidenceTier = strongest.tier,
                failureCode = strongest.failureCode,
            )
        }
        val status = when (strongest.tier) {
            GmsEvidenceTier.LOCAL_FIXTURE,
            GmsEvidenceTier.ASUS_FIXTURE,
            -> GmsCapabilityStatus.FIXTURE_PASSED_EXTERNAL_UNTESTED
            GmsEvidenceTier.REAL_EXTERNAL -> GmsCapabilityStatus.REAL_EXTERNAL_VERIFIED
            GmsEvidenceTier.THIRD_PARTY_APP -> GmsCapabilityStatus.THIRD_PARTY_APP_VERIFIED
        }
        return GmsCapabilityAssessment(capability, status, strongest.tier)
    }
}
