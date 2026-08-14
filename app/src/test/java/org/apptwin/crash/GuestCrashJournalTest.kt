package org.apptwin.crash

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuestCrashJournalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `journal keeps only the twenty newest accepted records`() {
        val root = temporaryFolder.newFolder("capacity")
        var sequence = 0
        val journal = journal(root, now = { 1_000L }) { uuid(++sequence) }

        repeat(25) { index ->
            assertNotNull(journal.append(PACKAGE, PROCESS, throwable(index), index.toLong()))
        }

        val pending = journal.pending()
        assertEquals(GuestCrashJournal.MAX_RECORDS, pending.size)
        assertEquals((5L..24L).toList(), pending.map(GuestCrashRecord::capturedAtEpochMillis))
    }

    @Test
    fun `expired records are skipped and deleted at seven days`() {
        val root = temporaryFolder.newFolder("ttl")
        var now = GuestCrashJournal.TTL_MILLIS + 9
        val journal = journal(root, now = { now })
        assertNotNull(journal.append(PACKAGE, PROCESS, throwable(1), 10))

        now += GuestCrashJournal.TTL_MILLIS

        assertTrue(journal.pending().isEmpty())
        assertTrue(recordFiles(root).isEmpty())
        assertNull(journal.append(PACKAGE, PROCESS, throwable(2), 10))
    }

    @Test
    fun `persisted data omits raw identifiers message metadata and paths`() {
        val root = temporaryFolder.newFolder("redaction")
        val journal = journal(root)
        val throwable = IllegalStateException("account@example.com intent=SECRET_MESSAGE").apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "safe.Example",
                    "run intent payload",
                    "/data/user/0/$PACKAGE/files/private-token.kt",
                    42,
                ),
            )
        }

        val record = requireNotNull(journal.append(PACKAGE, PROCESS, throwable, 500))
        val persisted = recordFiles(root).single().readText()

        assertFalse(persisted.contains(PACKAGE))
        assertFalse(persisted.contains(PROCESS))
        assertFalse(persisted.contains("account@example.com"))
        assertFalse(persisted.contains("SECRET_MESSAGE"))
        assertFalse(persisted.contains("/data/user"))
        assertFalse(persisted.contains("intent payload"))
        assertEquals(64, record.packageNameHash.length)
        assertEquals(64, record.processNameHash.length)
        assertNotEquals(PACKAGE, record.packageNameHash)
        assertEquals("private-token.kt", record.stackFrames.single().fileName)
        assertEquals("run_intent_payload", record.stackFrames.single().methodName)

        val restartedRecord = journal(root).pending().single()
        assertEquals(record.packageNameHash, restartedRecord.packageNameHash)
        assertEquals(record.processNameHash, restartedRecord.processNameHash)
    }

    @Test
    fun `corrupt and oversized records are removed without blocking valid records`() {
        val root = temporaryFolder.newFolder("corrupt")
        val journal = journal(root)
        val valid = requireNotNull(journal.append(PACKAGE, PROCESS, throwable(1), 500))
        val directory = File(root, GuestCrashJournal.DIRECTORY_NAME)
        val corrupt = File(directory, "${uuid(90)}.properties").apply {
            writeText("version=1\nid=${uuid(90)}\nunexpected=raw-account\n")
        }
        val oversized = File(directory, "${uuid(91)}.properties").apply {
            writeBytes(ByteArray(GuestCrashJournal.MAX_RECORD_BYTES + 1) { 'x'.code.toByte() })
        }

        assertEquals(listOf(valid), journal.pending())
        assertFalse(corrupt.exists())
        assertFalse(oversized.exists())
    }

    @Test
    fun `pending record reconstructs bounded sanitized stack and can be acknowledged`() {
        val root = temporaryFolder.newFolder("replay")
        val source = throwable(7).apply {
            stackTrace = Array(GuestCrashRedactor.MAX_STACK_FRAMES + 20) { index ->
                StackTraceElement(
                    "guest.feature.Worker$index",
                    "run-$index",
                    "/secret/path/Worker$index.kt",
                    index + 1,
                )
            }
        }
        val created = requireNotNull(journal(root).append(PACKAGE, PROCESS, source, 500))
        assertTrue(recordFiles(root).single().length() <= GuestCrashJournal.MAX_RECORD_BYTES)

        val restarted = journal(root)
        val replay = restarted.pending().single()
        val reconstructed = replay.reconstructThrowable()

        assertEquals(GuestCrashRedactor.MAX_STACK_FRAMES, replay.stackFrames.size)
        assertNull(reconstructed.message)
        assertEquals(replay.stackFrames.map(GuestCrashStackFrame::toStackTraceElement), reconstructed.stackTrace.toList())
        assertTrue(restarted.acknowledge(created.id))
        assertTrue(restarted.acknowledge(created.id))
        assertTrue(restarted.pending().isEmpty())
    }

    @Test
    fun `virtual runtime wrapper records the message-free guest root cause`() {
        val root = temporaryFolder.newFolder("wrapped-root-cause")
        val guestCause = IllegalStateException("guest-account@example.com").apply {
            stackTrace = arrayOf(
                StackTraceElement("guest.Feature", "start", "/private/Feature.kt", 19),
            )
        }
        val wrapper = RuntimeException("Unable to create guest application", guestCause)

        val record = requireNotNull(journal(root).append(PACKAGE, PROCESS, wrapper, 500))
        val persisted = recordFiles(root).single().readText()

        assertEquals("java.lang.IllegalStateException", record.exceptionClassName)
        assertEquals("guest.Feature", record.stackFrames.single().className)
        assertFalse(persisted.contains("Unable to create guest application"))
        assertFalse(persisted.contains("guest-account@example.com"))
        assertFalse(persisted.contains("/private/"))
        assertNull(record.reconstructThrowable().message)
    }

    private fun journal(
        root: File,
        now: () -> Long = { 1_000L },
        id: () -> String = { uuid(1) },
    ) = GuestCrashJournal(root, now, id)

    private fun throwable(index: Int) = IllegalArgumentException("must-not-persist-$index")

    private fun recordFiles(root: File): List<File> = File(root, GuestCrashJournal.DIRECTORY_NAME)
        .listFiles()
        .orEmpty()
        .filter { it.extension == "properties" }

    private fun uuid(value: Int): String = UUID(0, value.toLong()).toString()

    private companion object {
        const val PACKAGE = "com.private.guest"
        const val PROCESS = "com.private.guest:account-space"
    }
}
