package org.apptwin.gms.capabilities

import org.apptwin.gms.model.GmsGroupId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GmsCapabilityPolicyTest {
    private val group = GmsGroupId("group-a")

    @Test
    fun `fixture pass cannot be advertised as real external verification`() {
        val assessment = GmsCapabilityPolicy.evaluate(
            group,
            GmsCapability.FCM_MESSAGE,
            "release-1",
            listOf(evidence(GmsEvidenceTier.ASUS_FIXTURE, GmsEvidenceOutcome.PASSED)),
        )

        assertEquals(
            GmsCapabilityStatus.FIXTURE_PASSED_EXTERNAL_UNTESTED,
            assessment.status,
        )
        assertEquals(GmsEvidenceTier.ASUS_FIXTURE, assessment.evidenceTier)
    }

    @Test
    fun `real external and third party evidence produce exact claim levels`() {
        val real = GmsCapabilityPolicy.evaluate(
            group,
            GmsCapability.MAPS_SDK_V2,
            "release-1",
            listOf(
                evidence(
                    GmsEvidenceTier.REAL_EXTERNAL,
                    GmsEvidenceOutcome.PASSED,
                    capability = GmsCapability.MAPS_SDK_V2,
                ),
            ),
        )
        val thirdParty = GmsCapabilityPolicy.evaluate(
            group,
            GmsCapability.CAST_SENDER,
            "release-1",
            listOf(
                evidence(
                    GmsEvidenceTier.THIRD_PARTY_APP,
                    GmsEvidenceOutcome.PASSED,
                    capability = GmsCapability.CAST_SENDER,
                ),
            ),
        )

        assertEquals(GmsCapabilityStatus.REAL_EXTERNAL_VERIFIED, real.status)
        assertEquals(GmsCapabilityStatus.THIRD_PARTY_APP_VERIFIED, thirdParty.status)
    }

    @Test
    fun `billing and integrity stay unsupported even with passing evidence`() {
        for (capability in listOf(GmsCapability.PLAY_BILLING, GmsCapability.PLAY_INTEGRITY)) {
            val assessment = GmsCapabilityPolicy.evaluate(
                group,
                capability,
                "release-1",
                listOf(
                    evidence(
                        tier = GmsEvidenceTier.THIRD_PARTY_APP,
                        outcome = GmsEvidenceOutcome.PASSED,
                        capability = capability,
                    ),
                ),
            )

            assertEquals(GmsCapabilityStatus.UNSUPPORTED, assessment.status)
            assertNull(assessment.evidenceTier)
        }
    }

    @Test
    fun `stale release evidence is ignored and strongest current failure is explicit`() {
        val stale = evidence(GmsEvidenceTier.REAL_EXTERNAL, GmsEvidenceOutcome.PASSED).copy(
            releaseId = "release-old",
        )
        val failed = evidence(
            GmsEvidenceTier.ASUS_FIXTURE,
            GmsEvidenceOutcome.FAILED,
            failureCode = "TOKEN_TIMEOUT",
        )

        val assessment = GmsCapabilityPolicy.evaluate(
            group,
            GmsCapability.FCM_MESSAGE,
            "release-1",
            listOf(stale, failed),
        )

        assertEquals(GmsCapabilityStatus.KNOWN_FAILURE, assessment.status)
        assertEquals("TOKEN_TIMEOUT", assessment.failureCode)
    }

    @Test
    fun `evidence from another group cannot upgrade this group claim`() {
        val otherGroupEvidence = evidence(
            GmsEvidenceTier.REAL_EXTERNAL,
            GmsEvidenceOutcome.PASSED,
        ).copy(groupId = GmsGroupId("group-b"))

        val assessment = GmsCapabilityPolicy.evaluate(
            group,
            GmsCapability.FCM_MESSAGE,
            "release-1",
            listOf(otherGroupEvidence),
        )

        assertEquals(GmsCapabilityStatus.UNTESTED, assessment.status)
    }

    private fun evidence(
        tier: GmsEvidenceTier,
        outcome: GmsEvidenceOutcome,
        capability: GmsCapability = GmsCapability.FCM_MESSAGE,
        failureCode: String? = null,
    ) = GmsCapabilityEvidence(
        evidenceId = "fixture:${tier.name}:1",
        groupId = group,
        releaseId = "release-1",
        capability = capability,
        tier = tier,
        outcome = outcome,
        observedAtEpochMillis = 1_000,
        androidApi = 31,
        appTwinVersion = "1.0.0",
        fixtureOrClientVersion = "fixture-1",
        failureCode = failureCode,
    )
}
