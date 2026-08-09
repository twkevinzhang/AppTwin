package org.apptwin.compatibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityAssessmentPolicyTest {
    @Test
    fun `blocking source and artifact facts are unsupported`() {
        val assessment = CompatibilityAssessmentPolicy.assess(
            facts(sourceInstalled = false, splitSetComplete = false),
        )

        assertEquals(CompatibilityLevel.UNSUPPORTED, assessment.level)
        assertTrue(assessment.issues.any { it.code == CompatibilityIssueCode.SOURCE_MISSING })
        assertTrue(assessment.issues.any { it.code == CompatibilityIssueCode.SPLIT_SET_INCOMPLETE })
    }

    @Test
    fun `unknown fact never becomes basic compatibility`() {
        val assessment = CompatibilityAssessmentPolicy.assess(facts(abiSupported = null))

        assertEquals(CompatibilityLevel.UNTESTED, assessment.level)
        assertTrue(assessment.issues.any { it.severity == CompatibilityIssueSeverity.UNKNOWN })
    }

    @Test
    fun `validation applies only to the exact package version`() {
        val assessment = CompatibilityAssessmentPolicy.assess(
            facts(
                validations = setOf(
                    DeviceValidation(androidApi = 31, versionCode = 6),
                    DeviceValidation(androidApi = 36, versionCode = 7),
                ),
            ),
        )

        assertEquals(CompatibilityLevel.VERIFIED, assessment.level)
        assertEquals(setOf(36), assessment.validatedAndroidApis)
    }

    @Test
    fun `known limitation produces partial support even when device validated`() {
        val assessment = CompatibilityAssessmentPolicy.assess(
            facts(
                knownLimitations = setOf(CompatibilityLimitation.NOTIFICATION_ROUTING),
                validations = setOf(DeviceValidation(31, 7)),
            ),
        )

        assertEquals(CompatibilityLevel.PARTIAL, assessment.level)
    }

    private fun facts(
        sourceInstalled: Boolean = true,
        hasLauncher: Boolean? = true,
        abiSupported: Boolean? = true,
        baseArtifactReadable: Boolean? = true,
        splitSetComplete: Boolean? = true,
        signatureTrusted: Boolean? = true,
        requiredFeaturesSatisfied: Boolean? = true,
        knownLimitations: Set<CompatibilityLimitation> = emptySet(),
        validations: Set<DeviceValidation> = emptySet(),
    ) = PackageCompatibilityFacts(
        packageName = "com.example.app",
        versionCode = 7L,
        sourceInstalled = sourceInstalled,
        hasLauncher = hasLauncher,
        abiSupported = abiSupported,
        baseArtifactReadable = baseArtifactReadable,
        splitSetComplete = splitSetComplete,
        signatureTrusted = signatureTrusted,
        requiredFeaturesSatisfied = requiredFeaturesSatisfied,
        knownLimitations = knownLimitations,
        validations = validations,
    )
}
