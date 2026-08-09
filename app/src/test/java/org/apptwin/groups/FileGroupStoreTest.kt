package org.apptwin.groups

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    fun `unsupported schema is reported and remains on disk without rewrite`() {
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

        val snapshot = store.loadSnapshot()
        assertTrue(snapshot.groups.isEmpty())
        val issue = snapshot.issues.single() as GroupStoreLoadIssue.UnsupportedSchema
        assertEquals(ID, issue.groupId)
        assertEquals(GroupMetadataKind.GROUP, issue.metadataKind)
        assertEquals(3, issue.actualVersion)
        assertEquals(CURRENT_GROUP_SCHEMA_VERSION, issue.supportedVersion)
        assertFindFailsWith(store, ID, issue)
        assertTrue(original.contentEquals(metadata.readBytes()))
    }

    @Test
    fun `corrupt Group metadata is reported instead of treated as missing`() {
        val filesRoot = temporaryFolder.newFolder("corrupt-group")
        val groupRoot = File(filesRoot, "groups/$ID").apply { mkdirs() }
        File(groupRoot, "apps").mkdirs()
        File(groupRoot, "group.properties").storeProperties {
            setProperty("schemaVersion", CURRENT_GROUP_SCHEMA_VERSION.toString())
            setProperty("id", ID)
            setProperty("name", "毀損群組")
            setProperty("createdAtEpochMillis", "not-a-number")
            setProperty("environmentBindingId", "8")
            setProperty("health", GroupHealth.HEALTHY.name)
        }

        val store = FileGroupStore(filesRoot)
        val issue = store.loadSnapshot().issues.single() as GroupStoreLoadIssue.CorruptMetadata

        assertEquals(ID, issue.groupId)
        assertEquals(GroupMetadataKind.GROUP, issue.metadataKind)
        assertEquals("group.properties", issue.metadataName)
        assertFindFailsWith(store, ID, issue)
    }

    @Test
    fun `corrupt App metadata fails the owning Group explicitly`() {
        val filesRoot = temporaryFolder.newFolder("corrupt-app")
        val store = FileGroupStore(filesRoot)
        store.create(ID, "工作", EnvironmentBinding(8), 100)
        store.addApp(ID, LINE, 300)
        File(filesRoot, "groups/$ID/apps/$LINE.properties").storeProperties {
            setProperty("packageName", LINE)
            setProperty("addedAtEpochMillis", "invalid")
            setProperty("state", GroupAppState.ENABLED.name)
        }

        val issue = store.loadSnapshot().issues.single() as GroupStoreLoadIssue.CorruptMetadata

        assertEquals(ID, issue.groupId)
        assertEquals(GroupMetadataKind.APP, issue.metadataKind)
        assertEquals("$LINE.properties", issue.metadataName)
        assertFindFailsWith(store, ID, issue)
    }

    @Test
    fun `healthy Groups remain available beside corrupt Group metadata`() {
        val filesRoot = temporaryFolder.newFolder("mixed")
        val store = FileGroupStore(filesRoot)
        val healthy = store.create(ID, "工作", EnvironmentBinding(8), 100)
        val corruptRoot = File(filesRoot, "groups/$SECOND_ID").apply { mkdirs() }
        File(corruptRoot, "apps").mkdirs()
        File(corruptRoot, "group.properties").storeProperties {
            setProperty("schemaVersion", CURRENT_GROUP_SCHEMA_VERSION.toString())
            setProperty("id", SECOND_ID)
            setProperty("name", "生活")
            setProperty("createdAtEpochMillis", "200")
            setProperty("environmentBindingId", "invalid")
            setProperty("health", GroupHealth.HEALTHY.name)
        }

        val snapshot = FileGroupStore(filesRoot).loadSnapshot()

        assertEquals(listOf(healthy), snapshot.groups)
        assertEquals(SECOND_ID, snapshot.issues.single().groupId)
        assertEquals(listOf(healthy), FileGroupStore(filesRoot).listAll())
    }

    private fun assertFindFailsWith(
        store: FileGroupStore,
        groupId: String,
        expectedIssue: GroupStoreLoadIssue,
    ) {
        assertEquals(GroupLookupResult.Failed(expectedIssue), store.lookup(groupId))
        try {
            store.find(groupId)
            fail("Corrupt metadata must not be treated as a missing Group")
        } catch (error: GroupStoreLoadException) {
            assertEquals(expectedIssue, error.issue)
        }
    }

    private fun File.storeProperties(block: Properties.() -> Unit) {
        outputStream().use { output -> Properties().apply(block).store(output, "test") }
    }

    private companion object {
        const val ID = "00000000-0000-0000-0000-000000000001"
        const val SECOND_ID = "00000000-0000-0000-0000-000000000002"
        const val LINE = "jp.naver.line.android"
    }
}
