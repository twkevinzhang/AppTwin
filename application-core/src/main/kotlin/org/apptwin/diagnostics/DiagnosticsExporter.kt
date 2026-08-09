package org.apptwin.diagnostics

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.apptwin.compatibility.CompatibilityLevel
import org.apptwin.operations.OperationKind
import org.apptwin.operations.OperationPhase
import org.apptwin.spaces.CloneLifecycleState
import org.apptwin.spaces.SpaceLifecycleState

data class DiagnosticCloneSnapshot(
    val packageName: String,
    val lifecycle: CloneLifecycleState,
    val compatibility: CompatibilityLevel,
)

data class DiagnosticSpaceSnapshot(
    val spaceId: String,
    val lifecycle: SpaceLifecycleState,
    val clones: List<DiagnosticCloneSnapshot>,
)

data class DiagnosticOperationSnapshot(
    val kind: OperationKind,
    val phase: OperationPhase,
    val failureCode: String? = null,
) {
    init {
        require(failureCode == null || STABLE_CODE.matches(failureCode)) {
            "Only stable failure codes may be exported"
        }
    }

    private companion object {
        val STABLE_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
    }
}

/**
 * Deliberately allowlisted diagnostics input. There are no fields for account data, notifications,
 * intent payloads, exception messages, filesystem paths, or user files.
 */
data class DiagnosticSnapshot(
    val appVersion: String,
    val androidApi: Int,
    val spaces: List<DiagnosticSpaceSnapshot>,
    val operations: List<DiagnosticOperationSnapshot>,
) {
    init {
        require(appVersion.isNotBlank() && SAFE_VERSION.matches(appVersion)) {
            "appVersion must be a non-sensitive version token"
        }
        require(androidApi > 0) { "androidApi must be positive" }
    }

    private companion object {
        val SAFE_VERSION = Regex("[A-Za-z0-9._+-]{1,64}")
    }
}

data class RedactedDiagnosticReport(val text: String)

/** Produces a deterministic, line-oriented report with per-export pseudonyms. */
object DiagnosticsExporter {
    fun export(snapshot: DiagnosticSnapshot, exportSalt: ByteArray): RedactedDiagnosticReport {
        require(exportSalt.size >= 16) { "exportSalt must contain at least 128 bits" }
        val lines = mutableListOf(
            "schema=1",
            "appVersion=${snapshot.appVersion}",
            "androidApi=${snapshot.androidApi}",
            "spaceCount=${snapshot.spaces.size}",
        )
        snapshot.spaces
            .sortedBy { pseudonym(exportSalt, "space", it.spaceId) }
            .forEach { space ->
                val spaceKey = pseudonym(exportSalt, "space", space.spaceId)
                lines += "space=$spaceKey,state=${space.lifecycle.name},cloneCount=${space.clones.size}"
                space.clones
                    .sortedBy { pseudonym(exportSalt, "package", it.packageName) }
                    .forEach { clone ->
                        lines += buildString {
                            append("clone=")
                            append(pseudonym(exportSalt, "package", clone.packageName))
                            append(",space=")
                            append(spaceKey)
                            append(",state=")
                            append(clone.lifecycle.name)
                            append(",compatibility=")
                            append(clone.compatibility.name)
                        }
                    }
            }
        snapshot.operations
            .sortedWith(compareBy(DiagnosticOperationSnapshot::kind, DiagnosticOperationSnapshot::phase))
            .forEach { operation ->
                lines += buildString {
                    append("operation=${operation.kind.name},phase=${operation.phase.name}")
                    operation.failureCode?.let { append(",failureCode=$it") }
                }
            }
        return RedactedDiagnosticReport(lines.joinToString(separator = "\n", postfix = "\n"))
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
