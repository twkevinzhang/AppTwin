package org.apptwin.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileGroupAppRemovalJournalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `operation survives journal restart and can be removed`() {
        val filesRoot = temporaryFolder.newFolder("journal")
        val operation = GroupAppRemovalOperation(
            groupId = GROUP_ID,
            packageName = LINE,
            environmentBinding = EnvironmentBinding(7),
            membershipAddedAtEpochMillis = 123,
            phase = GroupAppRemovalPhase.RUNTIME_REMOVED,
            startedAtEpochMillis = 456,
        )
        FileGroupAppRemovalJournal(filesRoot).write(operation)

        val restarted = FileGroupAppRemovalJournal(filesRoot)

        assertEquals(listOf(operation), restarted.listAll())
        restarted.remove(GROUP_ID, LINE)
        assertTrue(FileGroupAppRemovalJournal(filesRoot).listAll().isEmpty())
    }

    @Test
    fun `different packages in one Group keep independent records`() {
        val filesRoot = temporaryFolder.newFolder("multiple")
        val journal = FileGroupAppRemovalJournal(filesRoot)
        val first = operation(LINE, 10)
        val second = operation(MAPS, 20)

        journal.write(first)
        journal.write(second)
        journal.remove(GROUP_ID, LINE)

        assertEquals(listOf(second), journal.listAll())
    }

    @Test
    fun `interrupted temporary write does not block recovery`() {
        val filesRoot = temporaryFolder.newFolder("interrupted")
        val journal = FileGroupAppRemovalJournal(filesRoot)
        val operation = operation(LINE, 10)
        journal.write(operation)
        val root = filesRoot.resolve("group-app-removals")
        root.resolve(".partial-write.tmp").writeText("incomplete")

        assertEquals(listOf(operation), FileGroupAppRemovalJournal(filesRoot).listAll())
    }

    private fun operation(packageName: String, startedAt: Long) = GroupAppRemovalOperation(
        groupId = GROUP_ID,
        packageName = packageName,
        environmentBinding = EnvironmentBinding(7),
        membershipAddedAtEpochMillis = 123,
        phase = GroupAppRemovalPhase.STARTED,
        startedAtEpochMillis = startedAt,
    )

    private companion object {
        const val GROUP_ID = "00000000-0000-0000-0000-000000000001"
        const val LINE = "jp.naver.line.android"
        const val MAPS = "com.google.android.apps.maps"
    }
}
