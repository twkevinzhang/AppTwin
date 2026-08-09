package org.apptwin.gms.runtime

import com.lody.virtual.remote.TrustedPackageProvenance
import java.util.UUID
import org.apptwin.gms.artifacts.GmsArtifactKind
import org.apptwin.gms.artifacts.TrustedGmsManifest
import org.apptwin.gms.model.GmsDesiredState
import org.apptwin.gms.model.GmsGroupId
import org.apptwin.gms.ports.GmsResetMode
import org.apptwin.gms.ports.GmsRuntimeMutationResult
import org.apptwin.gms.ports.GmsRuntimeObservation
import org.apptwin.gms.ports.GmsRuntimePort
import org.apptwin.microg.artifact.ArtifactSourceException
import org.apptwin.microg.artifact.ArtifactSourceFailure
import org.apptwin.microg.artifact.PinnedMicrogArtifactProvider
import org.apptwin.microg.artifact.PinnedMicrogRelease
import org.apptwin.microg.artifact.ProductionMicrogManifest
import org.apptwin.microg.artifact.ProductionMicrogArtifactKind
import org.apptwin.microg.artifact.StagedMicrogArtifact

fun interface GmsArtifactStageProvider {
    fun stageAll(): List<StagedMicrogArtifact>

    companion object {
        fun pinned(provider: PinnedMicrogArtifactProvider) =
            GmsArtifactStageProvider(provider::stageAll)
    }
}

class GmsRuntimeObservationException : IllegalStateException("RUNTIME_OBSERVE_RETRYABLE")

class AndroidGmsRuntimeAdapter(
    private val bindings: GmsGroupBindingResolver,
    private val artifacts: GmsArtifactStageProvider,
    private val engine: GmsVirtualRuntimeGateway,
    private val receipts: GmsOperationReceiptStore,
) : GmsRuntimePort {
    override fun observe(groupId: GmsGroupId): GmsRuntimeObservation = try {
        val userId = bindings.virtualUserId(groupId.value)
        if (userId == null || userId <= 0 || !engine.isVirtualUserPresent(userId)) {
            GmsRuntimeObservation(groupId, installed = false)
        } else {
            observeBound(groupId, userId)
        }
    } catch (_: Exception) {
        throw GmsRuntimeObservationException()
    }

    override fun ensureEnabled(
        groupId: GmsGroupId,
        release: TrustedGmsManifest,
        operationId: String,
    ): GmsRuntimeMutationResult {
        val binding = when (val resolved = resolveBinding(groupId)) {
            is BindingResolution.Valid -> resolved.userId
            is BindingResolution.Failure -> return resolved.result
        }
        invalidOperation(operationId)?.let { return it }
        releaseMismatch(release)?.let { return it }
        val receipt = receipt(groupId, binding, operationId, ACTION_ENABLE, release.release.releaseId)
        val before = observeForMutation(groupId, binding)
            ?: return GmsRuntimeMutationResult.RetryableFailure("RUNTIME_OBSERVE_RETRYABLE")
        if (before.satisfies(GmsDesiredState.ENABLED, release.release.releaseId)) {
            if (!receipts.contains(receipt)) receipts.record(receipt)
            return GmsRuntimeMutationResult.AlreadySatisfied(before)
        }
        return enableBound(groupId, binding, release, receipt)
    }

    override fun ensureDisabled(
        groupId: GmsGroupId,
        operationId: String,
    ): GmsRuntimeMutationResult {
        val binding = when (val resolved = resolveBinding(groupId)) {
            is BindingResolution.Valid -> resolved.userId
            is BindingResolution.Failure -> return resolved.result
        }
        invalidOperation(operationId)?.let { return it }
        val receipt = receipt(groupId, binding, operationId, ACTION_DISABLE, null)
        val before = observeForMutation(groupId, binding)
            ?: return GmsRuntimeMutationResult.RetryableFailure("RUNTIME_OBSERVE_RETRYABLE")
        val backgroundBefore = backgroundForMutation(binding)
            ?: return GmsRuntimeMutationResult.RetryableFailure("RUNTIME_OBSERVE_RETRYABLE")
        if (
            receipts.contains(receipt) &&
            before.satisfies(GmsDesiredState.DISABLED, null) &&
            !backgroundBefore
        ) {
            return GmsRuntimeMutationResult.AlreadySatisfied(before)
        }
        return when (val suspended = safeEngine { engine.suspendPreservingData(binding) }) {
            RuntimeEngineResult.Success -> terminal(
                groupId,
                binding,
                receipt,
                releaseId = null,
                requireFullyAbsent = false,
                requireBackgroundStopped = true,
            )
            is RuntimeEngineResult.Rejected -> GmsRuntimeMutationResult.Rejected(suspended.code)
            is RuntimeEngineResult.Retryable -> GmsRuntimeMutationResult.RetryableFailure(suspended.code)
        }
    }

    override fun resetPrivateState(
        groupId: GmsGroupId,
        mode: GmsResetMode,
        release: TrustedGmsManifest?,
        operationId: String,
    ): GmsRuntimeMutationResult {
        val binding = when (val resolved = resolveBinding(groupId)) {
            is BindingResolution.Valid -> resolved.userId
            is BindingResolution.Failure -> return resolved.result
        }
        invalidOperation(operationId)?.let { return it }
        if (mode == GmsResetMode.REENABLE_ACTIVE_RELEASE) {
            if (release == null) return GmsRuntimeMutationResult.Rejected("RELEASE_REQUIRED")
            releaseMismatch(release)?.let { return it }
        } else if (release != null) {
            return GmsRuntimeMutationResult.Rejected("UNEXPECTED_RELEASE")
        }
        val releaseId = release?.release?.releaseId
        val receipt = receipt(groupId, binding, operationId, "RESET_${mode.name}", releaseId)
        val target = if (mode == GmsResetMode.REENABLE_ACTIVE_RELEASE) {
            GmsDesiredState.ENABLED
        } else {
            GmsDesiredState.DISABLED
        }
        val before = observeForMutation(groupId, binding)
            ?: return GmsRuntimeMutationResult.RetryableFailure("RUNTIME_OBSERVE_RETRYABLE")
        val replayReachedTarget = if (mode == GmsResetMode.DISABLE_AFTER_RESET) {
            before.isFullyAbsent() && backgroundForMutation(binding) == false
        } else {
            before.satisfies(target, releaseId)
        }
        if (receipts.contains(receipt) && replayReachedTarget) {
            return GmsRuntimeMutationResult.AlreadySatisfied(before)
        }
        when (val removed = safeEngine { engine.uninstallAndClear(binding) }) {
            RuntimeEngineResult.Success -> Unit
            is RuntimeEngineResult.Rejected -> return GmsRuntimeMutationResult.Rejected(removed.code)
            is RuntimeEngineResult.Retryable ->
                return GmsRuntimeMutationResult.RetryableFailure(removed.code)
        }
        if (mode == GmsResetMode.DISABLE_AFTER_RESET) {
            return terminal(
                groupId,
                binding,
                receipt,
                releaseId = null,
                requireFullyAbsent = true,
                requireBackgroundStopped = true,
            )
        }
        return enableBound(groupId, binding, requireNotNull(release), receipt)
    }

    private fun enableBound(
        groupId: GmsGroupId,
        userId: Int,
        release: TrustedGmsManifest,
        receipt: GmsOperationReceipt,
    ): GmsRuntimeMutationResult {
        val staged = try {
            artifacts.stageAll()
        } catch (failure: ArtifactSourceException) {
            return artifactFailure(failure.failure)
        } catch (_: Exception) {
            return GmsRuntimeMutationResult.RetryableFailure("ARTIFACT_STAGING_RETRYABLE")
        }
        if (staged.map { it.manifest.kind }.toSet() != ProductionMicrogArtifactKind.entries.toSet()) {
            return GmsRuntimeMutationResult.Rejected("ARTIFACT_RELEASE_MISMATCH")
        }
        for (artifact in staged.sortedBy { it.manifest.kind.ordinal }) {
            stagedMismatch(artifact, release)?.let { return it }
            val provenance = provenance(artifact.manifest, release)
            when (val installed = safeEngine {
                engine.installTrusted(userId, artifact.apkPath, provenance)
            }) {
                RuntimeEngineResult.Success -> Unit
                is RuntimeEngineResult.Rejected ->
                    return GmsRuntimeMutationResult.Rejected(installed.code)
                is RuntimeEngineResult.Retryable ->
                    return GmsRuntimeMutationResult.RetryableFailure(installed.code)
            }
        }
        when (val prepared = safeEngine { engine.preparePrivateState(userId) }) {
            RuntimeEngineResult.Success -> Unit
            is RuntimeEngineResult.Rejected -> return GmsRuntimeMutationResult.Rejected(prepared.code)
            is RuntimeEngineResult.Retryable ->
                return GmsRuntimeMutationResult.RetryableFailure(prepared.code)
        }
        return terminal(groupId, userId, receipt, release.release.releaseId)
    }

    private fun terminal(
        groupId: GmsGroupId,
        userId: Int,
        receipt: GmsOperationReceipt,
        releaseId: String?,
        requireFullyAbsent: Boolean = false,
        requireBackgroundStopped: Boolean = false,
    ): GmsRuntimeMutationResult {
        val observation = observeForMutation(groupId, userId)
            ?: return GmsRuntimeMutationResult.RetryableFailure("RUNTIME_OBSERVE_RETRYABLE")
        val desired = if (releaseId == null) GmsDesiredState.DISABLED else GmsDesiredState.ENABLED
        val reachedState = if (requireFullyAbsent) {
            observation.isFullyAbsent()
        } else {
            observation.satisfies(desired, releaseId)
        }
        val backgroundStopped = !requireBackgroundStopped ||
            backgroundForMutation(userId) == false
        if (!reachedState || !backgroundStopped) {
            return GmsRuntimeMutationResult.RetryableFailure("RUNTIME_TERMINAL_STATE_RETRYABLE")
        }
        receipts.record(receipt)
        return GmsRuntimeMutationResult.Applied(observation)
    }

    private fun observeBound(groupId: GmsGroupId, userId: Int): GmsRuntimeObservation {
        val privateState = engine.hasPrivateState(userId)
        val packageInstalled = engine.isInstalled(userId)
        val version = if (packageInstalled) engine.installedVersionCode(userId) else null
        return GmsRuntimeObservation(
            groupId = groupId,
            installed = packageInstalled,
            releaseId = if (version == PinnedMicrogRelease.VERSION_CODE) {
                PinnedMicrogRelease.RELEASE_ID
            } else {
                null
            },
            privateStatePresent = privateState,
        )
    }

    private fun observeForMutation(groupId: GmsGroupId, userId: Int): GmsRuntimeObservation? =
        try {
            observeBound(groupId, userId)
        } catch (_: Exception) {
            null
        }

    private fun backgroundForMutation(userId: Int): Boolean? = try {
        engine.hasBackgroundOwnership(userId)
    } catch (_: Exception) {
        null
    }

    private fun resolveBinding(groupId: GmsGroupId): BindingResolution {
        val userId = try {
            bindings.virtualUserId(groupId.value)
        } catch (_: Exception) {
            return BindingResolution.Failure(
                GmsRuntimeMutationResult.RetryableFailure("GROUP_BINDING_RETRYABLE"),
            )
        }
        if (userId == null) {
            return BindingResolution.Failure(
                GmsRuntimeMutationResult.Rejected("GROUP_BINDING_MISSING"),
            )
        }
        if (userId == 0) {
            return BindingResolution.Failure(
                GmsRuntimeMutationResult.Rejected("DEFAULT_USER_FORBIDDEN"),
            )
        }
        if (userId < 0) {
            return BindingResolution.Failure(
                GmsRuntimeMutationResult.Rejected("GROUP_BINDING_INVALID"),
            )
        }
        val present = try {
            engine.isVirtualUserPresent(userId)
        } catch (_: Exception) {
            return BindingResolution.Failure(
                GmsRuntimeMutationResult.RetryableFailure("ENGINE_IO_RETRYABLE"),
            )
        }
        return if (present) {
            BindingResolution.Valid(userId)
        } else {
            BindingResolution.Failure(
                GmsRuntimeMutationResult.Rejected("VIRTUAL_USER_MISSING"),
            )
        }
    }

    private fun releaseMismatch(release: TrustedGmsManifest): GmsRuntimeMutationResult.Rejected? {
        val artifacts = release.artifacts.filter {
            it.identity.kind in setOf(GmsArtifactKind.GMS_CORE, GmsArtifactKind.FAKE_STORE)
        }.associateBy { it.identity.kind }
        if (
            release.artifacts.size != 2 ||
            artifacts.keys != setOf(GmsArtifactKind.GMS_CORE, GmsArtifactKind.FAKE_STORE)
        ) {
            return GmsRuntimeMutationResult.Rejected("RELEASE_GMS_BUNDLE_INVALID")
        }
        val pinned = PinnedMicrogRelease.manifest
        val exact = listOf(
            GmsArtifactKind.GMS_CORE to pinned.artifact(ProductionMicrogArtifactKind.GMS_CORE),
            GmsArtifactKind.FAKE_STORE to pinned.artifact(ProductionMicrogArtifactKind.COMPANION_STORE),
        ).all { (kind, expected) ->
            val artifact = artifacts.getValue(kind)
            artifact.identity.packageName == expected.packageName &&
                artifact.identity.versionCode == expected.versionCode &&
                artifact.identity.apkSha256 == expected.apkSha256 &&
                artifact.identity.realSignerSha256 == expected.realSignerSha256 &&
                artifact.exposedCompatibilitySignatureSha256 == expected.exposedCertificateSha256
        }
        return if (
            release.release.releaseId != pinned.releaseId ||
            release.release.microGVersion != pinned.versionName ||
            !exact
        ) {
            GmsRuntimeMutationResult.Rejected("RELEASE_MISMATCH")
        } else {
            null
        }
    }

    private fun stagedMismatch(
        staged: StagedMicrogArtifact,
        release: TrustedGmsManifest,
    ): GmsRuntimeMutationResult.Rejected? {
        val pinnedKind = when (staged.manifest.kind) {
            ProductionMicrogArtifactKind.GMS_CORE -> GmsArtifactKind.GMS_CORE
            ProductionMicrogArtifactKind.COMPANION_STORE -> GmsArtifactKind.FAKE_STORE
        }
        val pinned = PinnedMicrogRelease.manifest.artifact(staged.manifest.kind)
        val core = release.artifacts.single { it.identity.kind == pinnedKind }
        return if (
            staged.manifest.releaseId != release.release.releaseId ||
            staged.manifest.releaseVersionName != release.release.microGVersion ||
            staged.manifest.packageName != core.identity.packageName ||
            staged.manifest.versionCode != core.identity.versionCode ||
            staged.manifest.apkSha256 != core.identity.apkSha256 ||
            staged.manifest.realSignerSha256 != core.identity.realSignerSha256 ||
            staged.manifest.exposedCertificateSha256 !=
                core.exposedCompatibilitySignatureSha256 ||
            !staged.manifest.exposedCertificateDer.contentEquals(pinned.exposedCertificateDer) ||
            staged.manifest.splitApkSha256 != pinned.splitApkSha256
        ) {
            GmsRuntimeMutationResult.Rejected("ARTIFACT_RELEASE_MISMATCH")
        } else {
            null
        }
    }

    private fun provenance(
        manifest: ProductionMicrogManifest,
        release: TrustedGmsManifest,
    ) = TrustedPackageProvenance(
        "${release.release.releaseId}:${release.release.manifestSha256}",
        manifest.packageName,
        manifest.versionCode.toInt(),
        listOf(manifest.realSignerSha256),
        manifest.apkSha256,
        manifest.splitApkSha256,
        manifest.exposedCertificateDer,
    )

    private fun artifactFailure(failure: ArtifactSourceFailure): GmsRuntimeMutationResult =
        if (failure == ArtifactSourceFailure.STAGING_FAILED) {
            GmsRuntimeMutationResult.RetryableFailure("ARTIFACT_STAGING_RETRYABLE")
        } else {
            GmsRuntimeMutationResult.Rejected("ARTIFACT_${failure.name}")
        }

    private fun safeEngine(block: () -> RuntimeEngineResult): RuntimeEngineResult = try {
        block()
    } catch (_: Exception) {
        RuntimeEngineResult.Retryable("ENGINE_IO_RETRYABLE")
    }

    private fun invalidOperation(operationId: String): GmsRuntimeMutationResult.Rejected? =
        if (runCatching { UUID.fromString(operationId) }.isFailure) {
            GmsRuntimeMutationResult.Rejected("OPERATION_ID_INVALID")
        } else {
            null
        }

    private fun receipt(
        groupId: GmsGroupId,
        userId: Int,
        operationId: String,
        action: String,
        releaseId: String?,
    ) = GmsOperationReceipt(groupId.value, userId, operationId, action, releaseId)

    private companion object {
        const val ACTION_ENABLE = "ENABLE"
        const val ACTION_DISABLE = "DISABLE"
    }

    private sealed interface BindingResolution {
        data class Valid(val userId: Int) : BindingResolution
        data class Failure(val result: GmsRuntimeMutationResult) : BindingResolution
    }
}
