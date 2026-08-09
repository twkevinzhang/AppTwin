package org.apptwin.operations

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OperationRecordTest {
    @Test
    fun `codec round trips every persisted operation field`() {
        val record = record(
            phase = OperationPhase.FAILED,
            failureCode = "SOURCE_MISSING",
        )

        assertEquals(record, OperationRecordCodec.decode(OperationRecordCodec.encode(record)))
    }

    @Test
    fun `reconcile completes one record and retains another without starvation`() {
        val completed = record(id = UUID.randomUUID().toString(), kind = OperationKind.ADD_CLONE)
        val retry = record(id = UUID.randomUUID().toString(), kind = OperationKind.UPDATE_CLONE)
        val store = MemoryOperationStore(completed, retry)
        val result = OperationReconciler(
            store = store,
            handlers = mapOf(
                OperationKind.ADD_CLONE to OperationRecoveryHandler {
                    OperationRecoveryDecision.COMPLETED
                },
                OperationKind.UPDATE_CLONE to OperationRecoveryHandler {
                    OperationRecoveryDecision.RETRY_LATER
                },
            ),
            clock = { 200L },
        ).reconcile()

        assertEquals(listOf(completed.id), result.completed)
        assertEquals(listOf(retry.id), result.retained)
        assertNull(store.find(completed.id))
        assertEquals(2, store.find(retry.id)?.attempt)
        assertEquals(OperationPhase.APPLYING, store.find(retry.id)?.phase)
    }

    @Test
    fun `recovery exception becomes stable failure and remains retryable`() {
        val pending = record()
        val store = MemoryOperationStore(pending)

        val result = OperationReconciler(
            store,
            mapOf(pending.kind to OperationRecoveryHandler { error("secret detail") }),
            clock = { 300L },
        ).reconcile()

        assertEquals(listOf(pending.id), result.retained)
        val retained = requireNotNull(store.find(pending.id))
        assertEquals(OperationPhase.FAILED, retained.phase)
        assertEquals("RECOVERY_FAILED", retained.failureCode)
        assertEquals(2, retained.attempt)
    }

    @Test
    fun `tracker persists commit marker before removing pending record`() {
        val store = RecordingOperationStore()
        val tracker = OperationTracker(
            store,
            idFactory = { UUID.randomUUID().toString() },
            clock = sequenceOf(10L, 20L, 30L).iterator()::next,
        )
        val started = tracker.start(
            OperationKind.REMOVE_CLONE,
            OperationTarget("space", "com.example.app"),
        )
        val applying = tracker.applying(started)

        tracker.complete(applying)

        assertEquals(
            listOf(OperationPhase.STARTED, OperationPhase.APPLYING, OperationPhase.COMMITTED),
            store.saved.map(OperationRecord::phase),
        )
        assertEquals(listOf(started.id), store.removed)
    }

    @Test
    fun `retry supersedes an older failed operation for the same target`() {
        val failed = record(phase = OperationPhase.FAILED, failureCode = "LAUNCH_FAILED")
        val unrelated = record(
            id = UUID.randomUUID().toString(),
            kind = OperationKind.REPAIR_CLONE,
            phase = OperationPhase.FAILED,
            failureCode = "REPAIR_FAILED",
        )
        val store = MemoryOperationStore(failed, unrelated)
        val tracker = OperationTracker(store, clock = { 200L })

        val retry = tracker.start(OperationKind.LAUNCH_CLONE, failed.target)

        assertNull(store.find(failed.id))
        assertEquals(OperationPhase.STARTED, store.find(retry.id)?.phase)
        assertEquals(unrelated, store.find(unrelated.id))
    }

    @Test
    fun `committed record is cleaned without replaying business operation`() {
        val committed = record(phase = OperationPhase.COMMITTED)
        val store = MemoryOperationStore(committed)
        var recoveries = 0

        val result = OperationReconciler(
            store,
            mapOf(committed.kind to OperationRecoveryHandler {
                recoveries += 1
                OperationRecoveryDecision.COMPLETED
            }),
        ).reconcile()

        assertEquals(listOf(committed.id), result.completed)
        assertEquals(0, recoveries)
        assertNull(store.find(committed.id))
    }

    @Test
    fun `cleanup failure preserves committed marker and does not fail business completion`() {
        val store = FailingCleanupStore()
        val tracker = OperationTracker(
            store,
            idFactory = { UUID.randomUUID().toString() },
            clock = { 10L },
        )
        val started = tracker.start(
            OperationKind.ADD_CLONE,
            OperationTarget("space", "com.example.app"),
        )

        tracker.complete(started)

        assertEquals(OperationPhase.COMMITTED, store.find(started.id)?.phase)
    }

    private fun record(
        id: String = UUID.randomUUID().toString(),
        kind: OperationKind = OperationKind.LAUNCH_CLONE,
        phase: OperationPhase = OperationPhase.STARTED,
        failureCode: String? = null,
    ) = OperationRecord(
        id = id,
        kind = kind,
        target = OperationTarget("space-1", "com.example.app"),
        phase = phase,
        startedAtEpochMillis = 100L,
        updatedAtEpochMillis = 100L,
        failureCode = failureCode,
    )
}

private open class MemoryOperationStore(vararg initial: OperationRecord) : OperationRecordStore {
    protected val records = initial.associateByTo(linkedMapOf(), OperationRecord::id)

    override fun listPending(): List<OperationRecord> = records.values.toList()
    override fun find(id: String): OperationRecord? = records[id]
    override fun save(record: OperationRecord) {
        records[record.id] = record
    }
    override fun remove(id: String) {
        records.remove(id)
    }
}

private class RecordingOperationStore : MemoryOperationStore() {
    val saved = mutableListOf<OperationRecord>()
    val removed = mutableListOf<String>()

    override fun save(record: OperationRecord) {
        saved += record
        super.save(record)
    }

    override fun remove(id: String) {
        removed += id
        super.remove(id)
    }
}

private class FailingCleanupStore : MemoryOperationStore() {
    override fun remove(id: String) {
        error("cleanup unavailable")
    }
}
