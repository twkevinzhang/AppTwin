package org.apptwin.gms.operations

import java.util.UUID
import org.apptwin.gms.model.GmsDesiredState
import org.apptwin.gms.model.GmsGroupId

enum class GmsOperationKind {
    ENABLE,
    DISABLE,
    RESET,
}

enum class GmsOperationPhase {
    STARTED,
    APPLYING,
    COMMITTED,
    FAILED_RETRYABLE,
    FAILED_TERMINAL,
}

data class GmsOperation(
    val id: String,
    val groupId: GmsGroupId,
    val kind: GmsOperationKind,
    val targetDesiredState: GmsDesiredState,
    val targetReleaseId: String? = null,
    val phase: GmsOperationPhase,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val attempt: Int = 1,
    val failureCode: String? = null,
) {
    init {
        require(runCatching { UUID.fromString(id) }.isSuccess) { "operation id must be a UUID" }
        require(targetReleaseId == null || RELEASE_ID.matches(targetReleaseId)) {
            "targetReleaseId must be a stable token"
        }
        require(startedAtEpochMillis >= 0) { "startedAtEpochMillis must not be negative" }
        require(updatedAtEpochMillis >= startedAtEpochMillis) {
            "updatedAtEpochMillis must not precede start"
        }
        require(attempt > 0) { "attempt must be positive" }
        require(failureCode == null || STABLE_CODE.matches(failureCode)) {
            "failureCode must be a stable machine-readable code"
        }
        require(
            phase in setOf(GmsOperationPhase.FAILED_RETRYABLE, GmsOperationPhase.FAILED_TERMINAL) ||
                failureCode == null,
        ) { "failureCode is valid only for a failed operation" }
        require(targetDesiredState == GmsDesiredState.ENABLED || targetReleaseId == null) {
            "disabled target must not retain a release id"
        }
    }

    private companion object {
        val RELEASE_ID = Regex("[A-Za-z0-9._+-]{1,96}")
        val STABLE_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
    }
}

interface GmsOperationStore {
    fun list(): List<GmsOperation>
    fun find(id: String): GmsOperation?
    fun save(operation: GmsOperation)
    fun remove(id: String)
}
