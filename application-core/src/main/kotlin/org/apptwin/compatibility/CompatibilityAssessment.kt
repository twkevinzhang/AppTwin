package org.apptwin.compatibility

enum class CompatibilityLevel {
    VERIFIED,
    BASIC,
    PARTIAL,
    UNSUPPORTED,
    UNTESTED,
}

enum class CompatibilityIssueCode {
    SOURCE_MISSING,
    LAUNCHER_MISSING,
    ABI_UNSUPPORTED,
    BASE_ARTIFACT_UNREADABLE,
    SPLIT_SET_INCOMPLETE,
    SIGNATURE_UNTRUSTED,
    REQUIRED_FEATURE_MISSING,
    CAPABILITY_UNKNOWN,
    KNOWN_LIMITATION,
}

enum class CompatibilityIssueSeverity {
    BLOCKING,
    LIMITATION,
    UNKNOWN,
}

data class CompatibilityIssue(
    val code: CompatibilityIssueCode,
    val severity: CompatibilityIssueSeverity,
    val capability: CompatibilityCapability? = null,
    val limitation: CompatibilityLimitation? = null,
)

enum class CompatibilityCapability {
    LAUNCHER,
    ABI,
    BASE_ARTIFACT,
    SPLIT_SET,
    SIGNATURE,
    REQUIRED_FEATURES,
}

data class DeviceValidation(
    val androidApi: Int,
    val versionCode: Long,
) {
    init {
        require(androidApi > 0) { "androidApi must be positive" }
        require(versionCode >= 0) { "versionCode must not be negative" }
    }
}

/** Facts are supplied by adapters; null means not assessed, never implicitly compatible. */
data class PackageCompatibilityFacts(
    val packageName: String,
    val versionCode: Long,
    val sourceInstalled: Boolean,
    val hasLauncher: Boolean?,
    val abiSupported: Boolean?,
    val baseArtifactReadable: Boolean?,
    val splitSetComplete: Boolean?,
    val signatureTrusted: Boolean?,
    val requiredFeaturesSatisfied: Boolean?,
    val knownLimitations: Set<CompatibilityLimitation> = emptySet(),
    val validations: Set<DeviceValidation> = emptySet(),
) {
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(versionCode >= 0) { "versionCode must not be negative" }
    }
}

enum class CompatibilityLimitation {
    NOTIFICATION_ROUTING,
    PUSH_DELIVERY,
    DEEP_LINK_ROUTING,
    CAMERA,
    MICROPHONE,
    BACKGROUND_EXECUTION,
    INTEGRITY_ATTESTATION,
}

data class CompatibilityAssessment(
    val packageName: String,
    val level: CompatibilityLevel,
    val issues: List<CompatibilityIssue>,
    val validatedAndroidApis: Set<Int>,
)

object CompatibilityAssessmentPolicy {
    fun assess(facts: PackageCompatibilityFacts): CompatibilityAssessment {
        val issues = buildList {
            if (!facts.sourceInstalled) blocking(CompatibilityIssueCode.SOURCE_MISSING)
            check(
                facts.hasLauncher,
                CompatibilityCapability.LAUNCHER,
                CompatibilityIssueCode.LAUNCHER_MISSING,
            )
            check(
                facts.abiSupported,
                CompatibilityCapability.ABI,
                CompatibilityIssueCode.ABI_UNSUPPORTED,
            )
            check(
                facts.baseArtifactReadable,
                CompatibilityCapability.BASE_ARTIFACT,
                CompatibilityIssueCode.BASE_ARTIFACT_UNREADABLE,
            )
            check(
                facts.splitSetComplete,
                CompatibilityCapability.SPLIT_SET,
                CompatibilityIssueCode.SPLIT_SET_INCOMPLETE,
            )
            check(
                facts.signatureTrusted,
                CompatibilityCapability.SIGNATURE,
                CompatibilityIssueCode.SIGNATURE_UNTRUSTED,
            )
            check(
                facts.requiredFeaturesSatisfied,
                CompatibilityCapability.REQUIRED_FEATURES,
                CompatibilityIssueCode.REQUIRED_FEATURE_MISSING,
            )
            facts.knownLimitations.sorted().forEach { limitation ->
                add(
                    CompatibilityIssue(
                        CompatibilityIssueCode.KNOWN_LIMITATION,
                        CompatibilityIssueSeverity.LIMITATION,
                        limitation = limitation,
                    ),
                )
            }
        }
        val validatedApis = facts.validations
            .filter { it.versionCode == facts.versionCode }
            .mapTo(sortedSetOf(), DeviceValidation::androidApi)
        val level = when {
            issues.any { it.severity == CompatibilityIssueSeverity.BLOCKING } ->
                CompatibilityLevel.UNSUPPORTED
            issues.any { it.severity == CompatibilityIssueSeverity.UNKNOWN } ->
                CompatibilityLevel.UNTESTED
            issues.any { it.severity == CompatibilityIssueSeverity.LIMITATION } ->
                CompatibilityLevel.PARTIAL
            validatedApis.isNotEmpty() -> CompatibilityLevel.VERIFIED
            else -> CompatibilityLevel.BASIC
        }
        return CompatibilityAssessment(facts.packageName, level, issues, validatedApis)
    }

    private fun MutableList<CompatibilityIssue>.blocking(code: CompatibilityIssueCode) {
        add(CompatibilityIssue(code, CompatibilityIssueSeverity.BLOCKING))
    }

    private fun MutableList<CompatibilityIssue>.check(
        result: Boolean?,
        capability: CompatibilityCapability,
        failure: CompatibilityIssueCode,
    ) {
        when (result) {
            true -> Unit
            false -> blocking(failure)
            null -> add(
                CompatibilityIssue(
                    CompatibilityIssueCode.CAPABILITY_UNKNOWN,
                    CompatibilityIssueSeverity.UNKNOWN,
                    capability = capability,
                ),
            )
        }
    }
}
