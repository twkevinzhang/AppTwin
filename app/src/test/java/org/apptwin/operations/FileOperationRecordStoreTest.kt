package org.apptwin.operations

import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FileOperationRecordStoreTest {
    @Test
    fun `record survives store recreation and removal`() {
        val root = Files.createTempDirectory("apptwin-operation-store").toFile()
        val record = record()
        FileOperationRecordStore(root) { }.save(record)

        val recreated = FileOperationRecordStore(root) { }
        assertEquals(record, recreated.find(record.id))
        assertEquals(listOf(record), recreated.listPending())

        recreated.remove(record.id)
        assertNull(recreated.find(record.id))
        assertEquals(emptyList<OperationRecord>(), recreated.listPending())
    }

    @Test
    fun `corrupt record fails closed instead of disappearing`() {
        val root = Files.createTempDirectory("apptwin-operation-store-corrupt").toFile()
        val operationRoot = root.resolve("application-operations").apply { mkdirs() }
        operationRoot.resolve("${UUID.randomUUID()}.properties").writeText("schemaVersion=1\n")

        assertThrows(IllegalStateException::class.java) {
            FileOperationRecordStore(root) { }.listPending()
        }
    }

    private fun record(): OperationRecord = OperationRecord(
        id = UUID.randomUUID().toString(),
        kind = OperationKind.ADD_CLONE,
        target = OperationTarget(
            spaceId = "00000000-0000-0000-0000-000000000001",
            packageName = "org.apptwin.fixture",
        ),
        phase = OperationPhase.APPLYING,
        startedAtEpochMillis = 100,
        updatedAtEpochMillis = 101,
    )
}
