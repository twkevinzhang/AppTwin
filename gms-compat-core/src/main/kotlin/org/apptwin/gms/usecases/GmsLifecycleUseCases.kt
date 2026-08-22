package org.apptwin.gms.usecases

import java.util.UUID
import org.apptwin.gms.artifacts.TrustedGmsManifest
import org.apptwin.gms.capabilities.GmsCapability
import org.apptwin.gms.capabilities.GmsCapabilityAssessment
import org.apptwin.gms.capabilities.GmsCapabilityPolicy
import org.apptwin.gms.model.GmsDesiredState
import org.apptwin.gms.model.GmsGroupId
import org.apptwin.gms.model.GmsNetworkConsent
import org.apptwin.gms.model.GmsObservedState
import org.apptwin.gms.model.GmsProfile
import org.apptwin.gms.operations.GmsOperation
import org.apptwin.gms.operations.GmsOperationKind
import org.apptwin.gms.operations.GmsOperationPhase
import org.apptwin.gms.operations.GmsOperationStore
import org.apptwin.gms.ports.ActiveGmsReleasePort
import org.apptwin.gms.ports.CloudMessagingState
import org.apptwin.gms.ports.GmsCapabilityEvidenceRepository
import org.apptwin.gms.ports.GmsProfileRepository
import org.apptwin.gms.ports.GmsResetMode
import org.apptwin.gms.ports.GmsRuntimeMutationResult
import org.apptwin.gms.ports.GmsRuntimeObservation
import org.apptwin.gms.ports.GmsRuntimePort

sealed interface GmsLifecycleResult {
    data class Completed(val profile: GmsProfile) : GmsLifecycleResult
    data class AlreadySatisfied(val profile: GmsProfile) : GmsLifecycleResult
    data object ConsentRequired : GmsLifecycleResult
    data object TrustedReleaseUnavailable : GmsLifecycleResult
    data object DestructiveConfirmationRequired : GmsLifecycleResult
    data class RetryScheduled(val operationId: String, val failureCode: String) : GmsLifecycleResult
    data class Rejected(val operationId: String, val failureCode: String) : GmsLifecycleResult
}

class EnableGmsUseCase(
    private val coordinator: GmsLifecycleCoordinator,
) {
    fun execute(groupId: GmsGroupId): GmsLifecycleResult = coordinator.enable(groupId)
}

class DisableGmsUseCase(
    private val coordinator: GmsLifecycleCoordinator,
) {
    fun execute(groupId: GmsGroupId): GmsLifecycleResult = coordinator.disable(groupId)
}

class ResetGmsUseCase(
    private val coordinator: GmsLifecycleCoordinator,
) {
    fun execute(
        groupId: GmsGroupId,
        reenable: Boolean,
        destructiveConfirmed: Boolean,
    ): GmsLifecycleResult = coordinator.reset(groupId, reenable, destructiveConfirmed)
}

/** Durable lifecycle coordinator shared by the command use cases and startup reconciliation. */
class GmsLifecycleCoordinator(
    private val profiles: GmsProfileRepository,
    private val operations: GmsOperationStore,
    private val releases: ActiveGmsReleasePort,
    private val runtime: GmsRuntimePort,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun enable(groupId: GmsGroupId): GmsLifecycleResult {
        val profile = profile(groupId)
        if (profile.networkConsent != GmsNetworkConsent.GRANTED) {
            return GmsLifecycleResult.ConsentRequired
        }
        val release = releases.current() ?: return GmsLifecycleResult.TrustedReleaseUnavailable
        if (release.isExpired(clock())) return GmsLifecycleResult.TrustedReleaseUnavailable
        val targetRelease = release.release.releaseId
        val observation = runtime.observe(groupId)
        if (profile.desiredState == GmsDesiredState.ENABLED &&
            profile.observedState == GmsObservedState.READY_PARTIAL &&
            profile.observedReleaseId == targetRelease &&
            observation.satisfies(GmsDesiredState.ENABLED, targetRelease) &&
            observation.cloudMessaging.state == CloudMessagingState.CONNECTED
        ) {
            return GmsLifecycleResult.AlreadySatisfied(profile)
        }
        val operation = pendingOrNew(
            groupId = groupId,
            kind = GmsOperationKind.ENABLE,
            desired = GmsDesiredState.ENABLED,
            releaseId = targetRelease,
        )
        profiles.save(
            profile.next(
                desiredState = GmsDesiredState.ENABLED,
                observedState = GmsObservedState.ENABLING,
                observedReleaseId = profile.observedReleaseId,
            ),
        )
        return apply(operation, release)
    }

    fun disable(groupId: GmsGroupId): GmsLifecycleResult {
        val profile = profile(groupId)
        val observation = runtime.observe(groupId)
        if (profile.desiredState == GmsDesiredState.DISABLED &&
            profile.observedState == GmsObservedState.ABSENT &&
            observation.satisfies(GmsDesiredState.DISABLED, null)
        ) {
            return GmsLifecycleResult.AlreadySatisfied(profile)
        }
        val operation = pendingOrNew(
            groupId = groupId,
            kind = GmsOperationKind.DISABLE,
            desired = GmsDesiredState.DISABLED,
            releaseId = null,
        )
        profiles.save(
            profile.next(
                desiredState = GmsDesiredState.DISABLED,
                observedState = GmsObservedState.DISABLING,
                observedReleaseId = profile.observedReleaseId,
            ),
        )
        return apply(operation, release = null)
    }

    fun reset(
        groupId: GmsGroupId,
        reenable: Boolean,
        destructiveConfirmed: Boolean,
    ): GmsLifecycleResult {
        if (!destructiveConfirmed) return GmsLifecycleResult.DestructiveConfirmationRequired
        val profile = profile(groupId)
        val release = if (reenable) {
            releases.current() ?: return GmsLifecycleResult.TrustedReleaseUnavailable
        } else {
            null
        }
        if (release?.isExpired(clock()) == true) {
            return GmsLifecycleResult.TrustedReleaseUnavailable
        }
        if (reenable && profile.networkConsent != GmsNetworkConsent.GRANTED) {
            return GmsLifecycleResult.ConsentRequired
        }
        val desired = if (reenable) GmsDesiredState.ENABLED else GmsDesiredState.DISABLED
        val operation = pendingOrNew(
            groupId = groupId,
            kind = GmsOperationKind.RESET,
            desired = desired,
            releaseId = release?.release?.releaseId,
        )
        profiles.save(
            profile.next(
                desiredState = desired,
                observedState = GmsObservedState.RESETTING,
                observedReleaseId = profile.observedReleaseId,
            ),
        )
        return apply(operation, release)
    }

    internal fun apply(operation: GmsOperation, release: TrustedGmsManifest?): GmsLifecycleResult {
        if (operation.phase == GmsOperationPhase.COMMITTED) {
            operations.remove(operation.id)
            return GmsLifecycleResult.AlreadySatisfied(profile(operation.groupId))
        }
        if (operation.phase == GmsOperationPhase.FAILED_TERMINAL) {
            return GmsLifecycleResult.Rejected(
                operation.id,
                requireNotNull(operation.failureCode),
            )
        }
        val applying = transition(operation, GmsOperationPhase.APPLYING)
        val result = when (applying.kind) {
            GmsOperationKind.ENABLE -> {
                val requiredRelease = requireNotNull(release) { "enable requires active release" }
                check(requiredRelease.release.releaseId == applying.targetReleaseId) {
                    "active release changed while operation was pending"
                }
                runtime.ensureEnabled(applying.groupId, requiredRelease, applying.id)
            }
            GmsOperationKind.DISABLE -> runtime.ensureDisabled(applying.groupId, applying.id)
            GmsOperationKind.RESET -> runtime.resetPrivateState(
                groupId = applying.groupId,
                mode = if (applying.targetDesiredState == GmsDesiredState.ENABLED) {
                    GmsResetMode.REENABLE_ACTIVE_RELEASE
                } else {
                    GmsResetMode.DISABLE_AFTER_RESET
                },
                release = release,
                operationId = applying.id,
            )
        }
        return finish(applying, result)
    }

    private fun finish(
        operation: GmsOperation,
        result: GmsRuntimeMutationResult,
    ): GmsLifecycleResult = when (result) {
        is GmsRuntimeMutationResult.Applied -> complete(operation, result.observation, false)
        is GmsRuntimeMutationResult.AlreadySatisfied ->
            complete(operation, result.observation, true)
        is GmsRuntimeMutationResult.RetryableFailure -> fail(
            operation,
            result.code,
            GmsOperationPhase.FAILED_RETRYABLE,
        )
        is GmsRuntimeMutationResult.Rejected -> fail(
            operation,
            result.code,
            GmsOperationPhase.FAILED_TERMINAL,
        )
    }

    private fun complete(
        operation: GmsOperation,
        observation: GmsRuntimeObservation,
        alreadySatisfied: Boolean,
    ): GmsLifecycleResult {
        check(observation.groupId == operation.groupId) { "runtime returned the wrong group" }
        val reachedTerminalState = if (
            operation.kind == GmsOperationKind.RESET &&
            operation.targetDesiredState == GmsDesiredState.DISABLED
        ) {
            observation.isFullyAbsent()
        } else {
            observation.satisfies(operation.targetDesiredState, operation.targetReleaseId)
        }
        check(reachedTerminalState) {
            "runtime result did not reach the requested terminal state"
        }
        val current = profile(operation.groupId)
        val finalProfile = current.next(
            desiredState = operation.targetDesiredState,
            observedState = if (operation.targetDesiredState == GmsDesiredState.ENABLED) {
                GmsObservedState.READY_PARTIAL
            } else {
                GmsObservedState.ABSENT
            },
            observedReleaseId = operation.targetReleaseId,
        )
        profiles.save(finalProfile)
        val committed = transition(operation, GmsOperationPhase.COMMITTED)
        runCatching { operations.remove(committed.id) }
        return if (alreadySatisfied) {
            GmsLifecycleResult.AlreadySatisfied(finalProfile)
        } else {
            GmsLifecycleResult.Completed(finalProfile)
        }
    }

    private fun fail(
        operation: GmsOperation,
        code: String,
        phase: GmsOperationPhase,
    ): GmsLifecycleResult {
        require(STABLE_CODE.matches(code)) { "runtime failure must be a stable code" }
        val failed = transition(operation, phase, code, incrementAttempt = true)
        profiles.save(
            profile(operation.groupId).next(
                desiredState = operation.targetDesiredState,
                observedState = GmsObservedState.DEGRADED,
                observedReleaseId = operation.targetReleaseId,
                failureCode = code,
            ),
        )
        return if (phase == GmsOperationPhase.FAILED_RETRYABLE) {
            GmsLifecycleResult.RetryScheduled(failed.id, code)
        } else {
            GmsLifecycleResult.Rejected(failed.id, code)
        }
    }

    private fun pendingOrNew(
        groupId: GmsGroupId,
        kind: GmsOperationKind,
        desired: GmsDesiredState,
        releaseId: String?,
    ): GmsOperation {
        // The most recent user intent wins. Retaining an opposite retryable command could replay
        // after a later disable/reset and silently reverse that intent during startup recovery.
        operations.list()
            .filter { existing ->
                existing.groupId == groupId &&
                    existing.phase != GmsOperationPhase.FAILED_TERMINAL &&
                    (
                        existing.kind != kind ||
                            existing.targetDesiredState != desired ||
                            existing.targetReleaseId != releaseId
                    )
            }
            .forEach { operations.remove(it.id) }
        operations.list()
            .filter { it.groupId == groupId && it.kind == kind }
            .sortedByDescending(GmsOperation::updatedAtEpochMillis)
            .firstOrNull { it.phase != GmsOperationPhase.FAILED_TERMINAL }
            ?.let { existing ->
                return existing
            }
        val now = clock()
        return GmsOperation(
            id = idFactory(),
            groupId = groupId,
            kind = kind,
            targetDesiredState = desired,
            targetReleaseId = releaseId,
            phase = GmsOperationPhase.STARTED,
            startedAtEpochMillis = now,
            updatedAtEpochMillis = now,
        ).also(operations::save)
    }

    private fun transition(
        operation: GmsOperation,
        phase: GmsOperationPhase,
        failureCode: String? = null,
        incrementAttempt: Boolean = false,
    ): GmsOperation = operation.copy(
        phase = phase,
        updatedAtEpochMillis = maxOf(clock(), operation.updatedAtEpochMillis),
        attempt = operation.attempt + if (incrementAttempt) 1 else 0,
        failureCode = failureCode,
    ).also(operations::save)

    private fun profile(groupId: GmsGroupId): GmsProfile =
        profiles.find(groupId) ?: GmsProfile.disabled(groupId)

    private companion object {
        val STABLE_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
    }
}

data class GmsReconciliationResult(
    val completed: List<String>,
    val retainedForRetry: List<String>,
    val terminalFailures: List<String>,
    val releaseMismatches: List<String>,
)

class ReconcileGmsUseCase(
    private val coordinator: GmsLifecycleCoordinator,
    private val operations: GmsOperationStore,
    private val releases: ActiveGmsReleasePort,
) {
    fun execute(): GmsReconciliationResult {
        val completed = mutableListOf<String>()
        val retained = mutableListOf<String>()
        val terminal = mutableListOf<String>()
        val releaseMismatches = mutableListOf<String>()
        val activeRelease = releases.current()
        operations.list()
            .sortedWith(compareBy(GmsOperation::startedAtEpochMillis, GmsOperation::id))
            .forEach { operation ->
                if (operation.phase == GmsOperationPhase.FAILED_TERMINAL) {
                    terminal += operation.id
                    return@forEach
                }
                val release = if (operation.targetDesiredState == GmsDesiredState.ENABLED) {
                    activeRelease?.takeIf { it.release.releaseId == operation.targetReleaseId }
                } else {
                    null
                }
                if (operation.targetDesiredState == GmsDesiredState.ENABLED && release == null) {
                    releaseMismatches += operation.id
                    return@forEach
                }
                when (coordinator.apply(operation, release)) {
                    is GmsLifecycleResult.Completed,
                    is GmsLifecycleResult.AlreadySatisfied,
                    -> completed += operation.id
                    is GmsLifecycleResult.RetryScheduled -> retained += operation.id
                    is GmsLifecycleResult.Rejected -> terminal += operation.id
                    else -> retained += operation.id
                }
            }
        return GmsReconciliationResult(completed, retained, terminal, releaseMismatches)
    }
}

class EvaluateGmsCapabilityUseCase(
    private val evidence: GmsCapabilityEvidenceRepository,
    private val releases: ActiveGmsReleasePort,
) {
    fun execute(groupId: GmsGroupId, capability: GmsCapability): GmsCapabilityAssessment {
        val releaseId = releases.current()?.release?.releaseId
            ?: return GmsCapabilityAssessment(
                capability,
                if (capability in setOf(GmsCapability.PLAY_BILLING, GmsCapability.PLAY_INTEGRITY)) {
                    org.apptwin.gms.capabilities.GmsCapabilityStatus.UNSUPPORTED
                } else {
                    org.apptwin.gms.capabilities.GmsCapabilityStatus.UNTESTED
                },
            )
        return GmsCapabilityPolicy.evaluate(groupId, capability, releaseId, evidence.list(groupId))
    }
}

/** Re-evaluates persisted profile state against runtime facts and the one shared active release. */
class EvaluateGmsProfileUseCase(
    private val profiles: GmsProfileRepository,
    private val releases: ActiveGmsReleasePort,
    private val runtime: GmsRuntimePort,
) {
    fun execute(groupId: GmsGroupId): GmsProfile {
        val profile = profiles.find(groupId) ?: GmsProfile.disabled(groupId)
        val observation = runtime.observe(groupId)
        val activeReleaseId = releases.current()?.release?.releaseId
        val evaluated = when {
            profile.desiredState == GmsDesiredState.DISABLED &&
                observation.satisfies(GmsDesiredState.DISABLED, null) ->
                profile.next(
                    observedState = GmsObservedState.ABSENT,
                    observedReleaseId = null,
                )
            profile.desiredState == GmsDesiredState.ENABLED && activeReleaseId == null ->
                profile.next(
                    observedState = GmsObservedState.REVOKED,
                    observedReleaseId = observation.releaseId,
                )
            profile.desiredState == GmsDesiredState.ENABLED &&
                observation.installed && observation.releaseId != activeReleaseId ->
                profile.next(
                    observedState = GmsObservedState.UPDATE_REQUIRED,
                    observedReleaseId = observation.releaseId,
                )
            profile.desiredState == GmsDesiredState.ENABLED &&
                observation.satisfies(GmsDesiredState.ENABLED, activeReleaseId) ->
                profile.next(
                    observedState = GmsObservedState.READY_PARTIAL,
                    observedReleaseId = activeReleaseId,
                )
            else -> profile.next(
                observedState = GmsObservedState.DEGRADED,
                observedReleaseId = observation.releaseId,
                failureCode = "RUNTIME_STATE_MISMATCH",
            )
        }
        profiles.save(evaluated)
        return evaluated
    }
}
