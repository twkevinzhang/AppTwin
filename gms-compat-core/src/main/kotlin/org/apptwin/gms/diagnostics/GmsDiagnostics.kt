package org.apptwin.gms.diagnostics

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.apptwin.gms.capabilities.GmsCapability
import org.apptwin.gms.capabilities.GmsCapabilityStatus
import org.apptwin.gms.capabilities.GmsEvidenceTier
import org.apptwin.gms.model.GmsDesiredState
import org.apptwin.gms.model.GmsObservedState
import org.apptwin.gms.operations.GmsOperationKind
import org.apptwin.gms.operations.GmsOperationPhase

/**
 * Deliberately allowlisted diagnostic input. It has no account, check-in, FCM token, notification,
 * intent, filesystem path, exception message, API key, OAuth credential, or raw package fields.
 */
data class GmsDiagnosticSnapshot(
    val appTwinVersion: String,
    val androidApi: Int,
    val activeReleaseVersion: String?,
    val profiles: List<GmsDiagnosticProfile>,
    val operations: List<GmsDiagnosticOperation>,
    val capabilities: List<GmsDiagnosticCapability>,
) {
    init {
        require(SAFE_VERSION.matches(appTwinVersion)) { "appTwinVersion must be a safe token" }
        require(androidApi > 0) { "androidApi must be positive" }
        require(activeReleaseVersion == null || SAFE_VERSION.matches(activeReleaseVersion)) {
            "activeReleaseVersion must be a safe token"
        }
    }

    private companion object {
        val SAFE_VERSION = Regex("[A-Za-z0-9._+-]{1,96}")
    }
}

data class GmsDiagnosticProfile(
    val groupId: String,
    val desiredState: GmsDesiredState,
    val observedState: GmsObservedState,
    val failureCode: String? = null,
) {
    init {
        require(groupId.isNotBlank()) { "groupId must not be blank" }
        require(failureCode == null || STABLE_CODE.matches(failureCode)) {
            "failureCode must be a stable code"
        }
    }

    private companion object {
        val STABLE_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
    }
}

data class GmsDiagnosticOperation(
    val kind: GmsOperationKind,
    val phase: GmsOperationPhase,
    val attempt: Int,
    val failureCode: String? = null,
) {
    init {
        require(attempt > 0) { "attempt must be positive" }
        require(failureCode == null || STABLE_CODE.matches(failureCode)) {
            "failureCode must be a stable code"
        }
    }

    private companion object {
        val STABLE_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
    }
}

data class GmsDiagnosticCapability(
    val capability: GmsCapability,
    val status: GmsCapabilityStatus,
    val evidenceTier: GmsEvidenceTier? = null,
    val failureCode: String? = null,
) {
    init {
        require(failureCode == null || STABLE_CODE.matches(failureCode)) {
            "failureCode must be a stable code"
        }
    }

    private companion object {
        val STABLE_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
    }
}

data class RedactedGmsDiagnosticReport(val text: String)

object GmsDiagnosticsExporter {
    fun export(
        snapshot: GmsDiagnosticSnapshot,
        exportSalt: ByteArray,
    ): RedactedGmsDiagnosticReport {
        require(exportSalt.size >= 16) { "exportSalt must contain at least 128 bits" }
        val lines = mutableListOf(
            "schema=1",
            "appTwinVersion=${snapshot.appTwinVersion}",
            "androidApi=${snapshot.androidApi}",
            "activeReleaseVersion=${snapshot.activeReleaseVersion ?: "none"}",
            "profileCount=${snapshot.profiles.size}",
        )
        snapshot.profiles
            .sortedBy { pseudonym(exportSalt, "group", it.groupId) }
            .forEach { profile ->
                lines += buildString {
                    append("profile=")
                    append(pseudonym(exportSalt, "group", profile.groupId))
                    append(",desired=")
                    append(profile.desiredState.name)
                    append(",observed=")
                    append(profile.observedState.name)
                    profile.failureCode?.let { append(",failureCode=$it") }
                }
            }
        snapshot.operations
            .sortedWith(compareBy(GmsDiagnosticOperation::kind, GmsDiagnosticOperation::phase))
            .forEach { operation ->
                lines += buildString {
                    append("operation=${operation.kind.name},phase=${operation.phase.name}")
                    append(",attempt=${operation.attempt}")
                    operation.failureCode?.let { append(",failureCode=$it") }
                }
            }
        snapshot.capabilities
            .sortedBy(GmsDiagnosticCapability::capability)
            .forEach { capability ->
                lines += buildString {
                    append("capability=${capability.capability.name},status=${capability.status.name}")
                    capability.evidenceTier?.let { append(",tier=${it.name}") }
                    capability.failureCode?.let { append(",failureCode=$it") }
                }
            }
        return RedactedGmsDiagnosticReport(lines.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun pseudonym(salt: ByteArray, namespace: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(0)
        digest.update(namespace.toByteArray(StandardCharsets.UTF_8))
        digest.update(0)
        digest.update(value.toByteArray(StandardCharsets.UTF_8))
        return digest.digest().take(10).joinToString("") { byte -> "%02x".format(byte) }
    }
}
