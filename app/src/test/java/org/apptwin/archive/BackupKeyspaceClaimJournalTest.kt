package org.apptwin.archive

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupKeyspaceClaimJournalTest {
    @Test fun `pending ownership transaction survives journal recreation until explicit removal`() {
        val root = Files.createTempDirectory("backup-keyspace-journal").toFile()
        try {
            val record = BackupKeyspaceClaim("00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002", "00000000-0000-0000-0000-000000000003",
                "00000000-0000-0000-0000-000000000004", "00000000-0000-0000-0000-000000000005", "a".repeat(64))
            BackupKeyspaceClaimJournal(root).write(record)
            val recreated = BackupKeyspaceClaimJournal(root)
            assertEquals(listOf(record), recreated.records())
            recreated.remove(record)
            assertTrue(BackupKeyspaceClaimJournal(root).records().isEmpty())
        } finally { root.deleteRecursively() }
    }
}
