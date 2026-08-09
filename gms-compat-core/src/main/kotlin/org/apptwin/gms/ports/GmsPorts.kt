package org.apptwin.gms.ports

import org.apptwin.gms.artifacts.TrustedGmsManifest
import org.apptwin.gms.capabilities.GmsCapabilityEvidence
import org.apptwin.gms.model.GmsDesiredState
import org.apptwin.gms.model.GmsGroupId
import org.apptwin.gms.model.GmsProfile

interface GmsProfileRepository {
    fun find(groupId: GmsGroupId): GmsProfile?
    fun list(): List<GmsProfile>
    fun save(profile: GmsProfile)
}

/** One active code release is shared by every enabled group on this AppTwin installation. */
fun interface ActiveGmsReleasePort {
    fun current(): TrustedGmsManifest?
}

data class GmsRuntimeObservation(
    val groupId: GmsGroupId,
    val installed: Boolean,
    val releaseId: String? = null,
    val privateStatePresent: Boolean = false,
) {
    init {
        require(installed || releaseId == null) { "absent runtime must not report a release" }
    }

    fun satisfies(desiredState: GmsDesiredState, desiredReleaseId: String?): Boolean = when (desiredState) {
        // Disabled is a reversible suspension: package execution is unavailable while private
        // account/check-in/token state may remain for a later re-enable.
        GmsDesiredState.DISABLED -> !installed
        GmsDesiredState.ENABLED -> installed && releaseId == desiredReleaseId && privateStatePresent
    }

    /** Reset-to-disabled is destructive and therefore has a stricter terminal condition. */
    fun isFullyAbsent(): Boolean = !installed && !privateStatePresent
}

sealed interface GmsRuntimeMutationResult {
    data class Applied(val observation: GmsRuntimeObservation) : GmsRuntimeMutationResult
    data class AlreadySatisfied(val observation: GmsRuntimeObservation) : GmsRuntimeMutationResult
    data class RetryableFailure(val code: String) : GmsRuntimeMutationResult
    data class Rejected(val code: String) : GmsRuntimeMutationResult
}

enum class GmsResetMode {
    DISABLE_AFTER_RESET,
    REENABLE_ACTIVE_RELEASE,
}

/**
 * Android adapters implement these operations with [operationId] as an idempotency key. Replaying
 * the same operation after a crash must converge without duplicating package state or private data.
 */
interface GmsRuntimePort {
    fun observe(groupId: GmsGroupId): GmsRuntimeObservation

    fun ensureEnabled(
        groupId: GmsGroupId,
        release: TrustedGmsManifest,
        operationId: String,
    ): GmsRuntimeMutationResult

    fun ensureDisabled(
        groupId: GmsGroupId,
        operationId: String,
    ): GmsRuntimeMutationResult

    fun resetPrivateState(
        groupId: GmsGroupId,
        mode: GmsResetMode,
        release: TrustedGmsManifest?,
        operationId: String,
    ): GmsRuntimeMutationResult
}

interface GmsCapabilityEvidenceRepository {
    fun list(groupId: GmsGroupId): List<GmsCapabilityEvidence>
    fun save(evidence: GmsCapabilityEvidence)
}
