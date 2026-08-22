package org.apptwin.gms

import android.app.Application
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.apptwin.gms.capabilities.GmsCapability
import org.apptwin.gms.capabilities.GmsCapabilityAssessment
import org.apptwin.gms.model.GmsGroupId
import org.apptwin.gms.model.GmsNetworkConsent
import org.apptwin.gms.model.GmsProfile
import org.apptwin.gms.ports.ActiveGmsReleasePort
import org.apptwin.gms.ports.CloudMessagingHealth
import org.apptwin.gms.ports.CloudMessagingState
import org.apptwin.gms.ports.GmsRuntimeMutationResult
import org.apptwin.gms.ports.GmsRuntimeObservation
import org.apptwin.gms.ports.GmsRuntimePort
import org.apptwin.gms.ports.GmsResetMode
import org.apptwin.gms.usecases.DisableGmsUseCase
import org.apptwin.gms.usecases.EnableGmsUseCase
import org.apptwin.gms.usecases.EvaluateGmsCapabilityUseCase
import org.apptwin.gms.usecases.EvaluateGmsProfileUseCase
import org.apptwin.gms.usecases.GmsLifecycleCoordinator
import org.apptwin.gms.usecases.GmsLifecycleResult
import org.apptwin.gms.usecases.GmsReconciliationResult
import org.apptwin.gms.usecases.ReconcileGmsUseCase
import org.apptwin.gms.usecases.ResetGmsUseCase

data class GmsGroupProductState(
    val profile: GmsProfile,
    val capabilities: List<GmsCapabilityAssessment>,
    val cloudMessaging: CloudMessagingHealth = CloudMessagingHealth(),
    val hasDataWarning: Boolean = false,
)

data class GmsStartupResult(
    val reconciliation: GmsReconciliationResult,
    val profiles: List<GmsProfile>,
    val cloudMessagingRepairFailures: List<String> = emptyList(),
    val productStates: Map<String, GmsGroupProductState> = emptyMap(),
)

/**
 * Android composition root for the GMS bounded context. Runtime and artifact ports are explicit so
 * product code cannot report success until trusted production adapters have been installed.
 */
internal class AndroidGmsOperations(
    application: Application,
    private val releases: ActiveGmsReleasePort = UnavailableGmsReleasePort,
    private val runtime: GmsRuntimePort = UnavailableGmsRuntimePort,
) {
    companion object {}
    private val issues = GmsDataIssueRecorder()
    private val profiles = FileGmsProfileRepository(
        application.filesDir,
        DurableAndroidDirectorySync,
        issues,
    )
    private val operations = FileGmsOperationStore(
        application.filesDir,
        DurableAndroidDirectorySync,
        issues,
    )
    private val evidence = FileGmsCapabilityEvidenceRepository(
        application.filesDir,
        DurableAndroidDirectorySync,
        issues,
    )
    private val coordinator = GmsLifecycleCoordinator(profiles, operations, releases, runtime)
    private val reconcile = ReconcileGmsUseCase(coordinator, operations, releases)
    private val evaluateProfile = EvaluateGmsProfileUseCase(profiles, releases, runtime)
    private val evaluateCapability = EvaluateGmsCapabilityUseCase(evidence, releases)
    private val enable = EnableGmsUseCase(coordinator)
    private val disable = DisableGmsUseCase(coordinator)
    private val reset = ResetGmsUseCase(coordinator)

    fun startupReconcile(groupIds: List<String>): GmsStartupResult {
        val reconciliation = reconcile.execute()
        val evaluated = groupIds.map { groupId -> evaluateProfile.execute(GmsGroupId(groupId)) }
        val release = releases.current()
        val failures = evaluated
            .filter { profile ->
                profile.desiredState == org.apptwin.gms.model.GmsDesiredState.ENABLED &&
                    profile.networkConsent == GmsNetworkConsent.GRANTED
            }
            .mapNotNull { profile ->
                if (release == null) {
                    "TRUSTED_RELEASE_UNAVAILABLE"
                } else {
                    when (val result = runCatching {
                        runtime.ensureEnabled(
                            profile.groupId,
                            release,
                            cloudMessagingRepairOperationId(profile.groupId),
                        )
                    }.getOrNull()) {
                        is GmsRuntimeMutationResult.RetryableFailure -> result.code
                        is GmsRuntimeMutationResult.Rejected -> result.code
                        null -> "CLOUD_MESSAGING_REPAIR_RETRYABLE"
                        else -> null
                    }
                }
            }
        val productStates = groupIds.associateWith(::snapshot)
        return GmsStartupResult(
            reconciliation = reconciliation,
            profiles = productStates.values.map(GmsGroupProductState::profile),
            cloudMessagingRepairFailures = failures,
            productStates = productStates,
        )
    }

    fun snapshot(groupId: String): GmsGroupProductState {
        val id = GmsGroupId(groupId)
        val profile = runCatching { evaluateProfile.execute(id) }
            .getOrElse { profiles.find(id) ?: GmsProfile.disabled(id) }
        val cloudMessaging = runCatching { runtime.observe(id).cloudMessaging }
            .getOrElse {
                CloudMessagingHealth(
                    state = CloudMessagingState.UNKNOWN,
                    failureCode = "CLOUD_MESSAGING_OBSERVE_RETRYABLE",
                )
            }
        val capabilities = GmsCapability.entries.map { capability ->
            evaluateCapability.execute(id, capability)
        }
        return GmsGroupProductState(
            profile = profile,
            capabilities = capabilities,
            cloudMessaging = cloudMessaging,
            hasDataWarning = issues.list().any { it.groupId == groupId },
        )
    }

    fun grantNetworkConsent(groupId: String) {
        val id = GmsGroupId(groupId)
        val current = profiles.find(id) ?: GmsProfile.disabled(id)
        if (current.networkConsent != GmsNetworkConsent.GRANTED) {
            profiles.save(
                current.copy(
                    networkConsent = GmsNetworkConsent.GRANTED,
                    generation = current.generation + 1,
                ),
            )
        }
    }

    fun enable(groupId: String): GmsLifecycleResult = enable.execute(GmsGroupId(groupId))

    fun disable(groupId: String): GmsLifecycleResult = disable.execute(GmsGroupId(groupId))

    fun reset(groupId: String, reenable: Boolean): GmsLifecycleResult = reset.execute(
        groupId = GmsGroupId(groupId),
        reenable = reenable,
        destructiveConfirmed = true,
    )

    fun warnings(): List<GmsDataWarning> = issues.list()
}

internal fun cloudMessagingRepairOperationId(groupId: GmsGroupId): String =
    UUID.nameUUIDFromBytes(
        "apptwin:cloud-messaging:${groupId.value}".toByteArray(StandardCharsets.UTF_8),
    ).toString()

private val DurableAndroidDirectorySync: (java.io.File) -> Unit = { directory ->
    val descriptor = android.system.Os.open(
        directory.absolutePath,
        android.system.OsConstants.O_RDONLY,
        0,
    )
    try {
        android.system.Os.fsync(descriptor)
    } finally {
        android.system.Os.close(descriptor)
    }
}

private object UnavailableGmsReleasePort : ActiveGmsReleasePort {
    override fun current() = null
}

private object UnavailableGmsRuntimePort : GmsRuntimePort {
    override fun observe(groupId: GmsGroupId) = GmsRuntimeObservation(groupId, installed = false)

    override fun ensureEnabled(
        groupId: GmsGroupId,
        release: org.apptwin.gms.artifacts.TrustedGmsManifest,
        operationId: String,
    ): GmsRuntimeMutationResult = GmsRuntimeMutationResult.Rejected("RUNTIME_ADAPTER_UNAVAILABLE")

    override fun ensureDisabled(
        groupId: GmsGroupId,
        operationId: String,
    ): GmsRuntimeMutationResult = GmsRuntimeMutationResult.Rejected("RUNTIME_ADAPTER_UNAVAILABLE")

    override fun resetPrivateState(
        groupId: GmsGroupId,
        mode: GmsResetMode,
        release: org.apptwin.gms.artifacts.TrustedGmsManifest?,
        operationId: String,
    ): GmsRuntimeMutationResult = GmsRuntimeMutationResult.Rejected("RUNTIME_ADAPTER_UNAVAILABLE")
}
