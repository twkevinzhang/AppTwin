package org.apptwin.operations

import java.util.UUID

enum class OperationKind {
    CREATE_SPACE,
    ADD_CLONE,
    LAUNCH_CLONE,
    UPDATE_CLONE,
    REMOVE_CLONE,
    DELETE_SPACE,
    REPAIR_SPACE,
    REPAIR_CLONE,
}

enum class OperationPhase {
    STARTED,
    APPLYING,
    COMMITTED,
    ROLLING_BACK,
    FAILED,
}

data class OperationTarget(
    val spaceId: String,
    val packageName: String? = null,
) {
    init {
        require(spaceId.isNotBlank()) { "spaceId must not be blank" }
        require(packageName == null || packageName.isNotBlank()) {
            "packageName must be null or non-blank"
        }
    }
}

/**
 * Persistable description of an application operation that may outlive one process.
 *
 * [failureCode] is deliberately a stable code rather than an exception message. Adapters may log
 * richer details separately, but must not put credentials or user content in this record.
 */
data class OperationRecord(
    val id: String,
    val kind: OperationKind,
    val target: OperationTarget,
    val phase: OperationPhase,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val attempt: Int = 1,
    val failureCode: String? = null,
) {
    init {
        require(runCatching { UUID.fromString(id) }.isSuccess) { "id must be a UUID" }
        require(startedAtEpochMillis >= 0) { "startedAtEpochMillis must not be negative" }
        require(updatedAtEpochMillis >= startedAtEpochMillis) {
            "updatedAtEpochMillis must not precede start"
        }
        require(attempt > 0) { "attempt must be positive" }
        require(failureCode == null || FAILURE_CODE.matches(failureCode)) {
            "failureCode must be a stable machine-readable code"
        }
        require(phase == OperationPhase.FAILED || failureCode == null) {
            "failureCode is valid only for a failed operation"
        }
    }

    private companion object {
        val FAILURE_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
    }
}

interface OperationRecordStore {
    fun listPending(): List<OperationRecord>
    fun find(id: String): OperationRecord?
    fun save(record: OperationRecord)
    fun remove(id: String)
}

/** Field codec for filesystem/database adapters. It intentionally has no Java serialization. */
object OperationRecordCodec {
    private const val SCHEMA_VERSION = 1

    fun encode(record: OperationRecord): Map<String, String> = buildMap {
        put("schemaVersion", SCHEMA_VERSION.toString())
        put("id", record.id)
        put("kind", record.kind.name)
        put("spaceId", record.target.spaceId)
        record.target.packageName?.let { put("packageName", it) }
        put("phase", record.phase.name)
        put("startedAtEpochMillis", record.startedAtEpochMillis.toString())
        put("updatedAtEpochMillis", record.updatedAtEpochMillis.toString())
        put("attempt", record.attempt.toString())
        record.failureCode?.let { put("failureCode", it) }
    }

    fun decode(fields: Map<String, String>): OperationRecord {
        require(fields.required("schemaVersion").toInt() == SCHEMA_VERSION) {
            "Unsupported operation schema"
        }
        return OperationRecord(
            id = fields.required("id"),
            kind = OperationKind.valueOf(fields.required("kind")),
            target = OperationTarget(
                spaceId = fields.required("spaceId"),
                packageName = fields["packageName"],
            ),
            phase = OperationPhase.valueOf(fields.required("phase")),
            startedAtEpochMillis = fields.required("startedAtEpochMillis").toLong(),
            updatedAtEpochMillis = fields.required("updatedAtEpochMillis").toLong(),
            attempt = fields.required("attempt").toInt(),
            failureCode = fields["failureCode"],
        )
    }

    private fun Map<String, String>.required(name: String): String =
        requireNotNull(this[name]) { "Missing operation field: $name" }
}

enum class OperationRecoveryDecision {
    /** The underlying transition reached its business terminal state. */
    COMPLETED,

    /** The operation remains pending and should be retried on a later reconciliation. */
    RETRY_LATER,
}

fun interface OperationRecoveryHandler {
    fun recover(record: OperationRecord): OperationRecoveryDecision
}

data class OperationReconciliationResult(
    val completed: List<String>,
    val retained: List<String>,
    val unsupported: List<String>,
)

/** Reconciles independently so one broken record never starves another operation. */
class OperationReconciler(
    private val store: OperationRecordStore,
    private val handlers: Map<OperationKind, OperationRecoveryHandler>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun reconcile(): OperationReconciliationResult {
        val completed = mutableListOf<String>()
        val retained = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        store.listPending()
            .sortedWith(compareBy(OperationRecord::startedAtEpochMillis, OperationRecord::id))
            .forEach { record ->
                when (runCatching { reconcileOne(record) }.getOrElse { ReconcileOne.RETAINED }) {
                    ReconcileOne.COMPLETED -> completed += record.id
                    ReconcileOne.RETAINED -> retained += record.id
                    ReconcileOne.UNSUPPORTED -> unsupported += record.id
                }
            }
        return OperationReconciliationResult(completed, retained, unsupported)
    }

    private fun reconcileOne(record: OperationRecord): ReconcileOne {
        // A crash can happen after the durable commit marker and before cleanup. The business
        // transition is already terminal, so recovery must not execute it again.
        if (record.phase == OperationPhase.COMMITTED) {
            store.remove(record.id)
            return ReconcileOne.COMPLETED
        }
        val handler = handlers[record.kind] ?: return ReconcileOne.UNSUPPORTED
        val decision = runCatching { handler.recover(record) }.getOrElse {
            store.save(
                record.copy(
                    phase = OperationPhase.FAILED,
                    updatedAtEpochMillis = maxOf(clock(), record.updatedAtEpochMillis),
                    attempt = record.attempt + 1,
                    failureCode = "RECOVERY_FAILED",
                ),
            )
            return ReconcileOne.RETAINED
        }
        return when (decision) {
            OperationRecoveryDecision.COMPLETED -> {
                val committed = record.copy(
                    phase = OperationPhase.COMMITTED,
                    updatedAtEpochMillis = maxOf(clock(), record.updatedAtEpochMillis),
                    failureCode = null,
                )
                store.save(committed)
                store.remove(record.id)
                ReconcileOne.COMPLETED
            }
            OperationRecoveryDecision.RETRY_LATER -> {
                store.save(
                    record.copy(
                        phase = OperationPhase.APPLYING,
                        updatedAtEpochMillis = maxOf(clock(), record.updatedAtEpochMillis),
                        attempt = record.attempt + 1,
                        failureCode = null,
                    ),
                )
                ReconcileOne.RETAINED
            }
        }
    }

    private enum class ReconcileOne { COMPLETED, RETAINED, UNSUPPORTED }
}

class OperationTracker(
    private val store: OperationRecordStore,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun start(kind: OperationKind, target: OperationTarget): OperationRecord {
        clearFailures(target, setOf(kind))
        val now = clock()
        return OperationRecord(
            id = idFactory(),
            kind = kind,
            target = target,
            phase = OperationPhase.STARTED,
            startedAtEpochMillis = now,
            updatedAtEpochMillis = now,
        ).also(store::save)
    }

    fun clearFailures(target: OperationTarget, kinds: Set<OperationKind>) {
        store.listPending()
            .filter { record ->
                record.phase == OperationPhase.FAILED &&
                    record.target == target &&
                    record.kind in kinds
            }
            .forEach { store.remove(it.id) }
    }

    fun applying(record: OperationRecord): OperationRecord = transition(
        record,
        OperationPhase.APPLYING,
    )

    fun fail(record: OperationRecord, failureCode: String): OperationRecord = transition(
        record,
        OperationPhase.FAILED,
        failureCode,
    )

    /** Commit is persisted before the pending record is removed. */
    fun complete(record: OperationRecord) {
        val committed = transition(record, OperationPhase.COMMITTED)
        // Cleanup is post-commit. A failure leaves the COMMITTED marker for reconciliation and
        // must not make a successful business operation look failed or replay it.
        runCatching { store.remove(committed.id) }
    }

    private fun transition(
        record: OperationRecord,
        phase: OperationPhase,
        failureCode: String? = null,
    ): OperationRecord = record.copy(
        phase = phase,
        updatedAtEpochMillis = maxOf(clock(), record.updatedAtEpochMillis),
        failureCode = failureCode,
    ).also(store::save)
}
