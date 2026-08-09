package org.apptwin.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties

class FileGroupStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `group and app state survive store restart`() {
        val filesRoot = temporaryFolder.newFolder("restart")
        val store = FileGroupStore(filesRoot)
        val group = store.create(ID, "工作", EnvironmentBinding(8), 100)
        store.addApp(group.id, LINE, 300)
        store.updateAppState(group.id, LINE, GroupAppState.DISABLED)

        val restored = FileGroupStore(filesRoot).find(group.id)!!

        assertEquals(GroupHealth.HEALTHY, restored.health)
        assertEquals(EnvironmentBinding(8), restored.environmentBinding)
        assertEquals(GroupAppState.DISABLED, restored.apps.single().state)
    }

    @Test
    fun `legacy schema remains on disk but is not loaded or rewritten`() {
        val filesRoot = temporaryFolder.newFolder("legacy")
        val groupRoot = File(filesRoot, "groups/$ID").apply { mkdirs() }
        File(groupRoot, "apps").mkdirs()
        val metadata = File(groupRoot, "group.properties")
        metadata.outputStream().use { output ->
            Properties().apply {
                setProperty("schemaVersion", "3")
                setProperty("id", ID)
                setProperty("name", "舊群組")
                setProperty("createdAtEpochMillis", "100")
                setProperty("environmentBindingId", "8")
                setProperty("health", GroupHealth.HEALTHY.name)
                setProperty("googleServicesState", "READY")
            }.store(output, "legacy AppTwin Group")
        }
        val original = metadata.readBytes()

        val store = FileGroupStore(filesRoot)

        assertTrue(store.listAll().isEmpty())
        assertNull(store.find(ID))
        assertTrue(original.contentEquals(metadata.readBytes()))
    }

    private companion object {
        const val ID = "00000000-0000-0000-0000-000000000001"
        const val LINE = "jp.naver.line.android"
    }
}
