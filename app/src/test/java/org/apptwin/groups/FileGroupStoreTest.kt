package org.apptwin.groups

import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileGroupStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `schema 2 app defaults to system import and migrates to schema 3`() {
        val filesRoot = temporaryFolder.newFolder("schema-migration")
        writeSchema2Group(filesRoot, ID_1, LINE)

        val migrated = FileGroupStore(filesRoot).find(ID_1)!!

        assertEquals(CURRENT_GROUP_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(GroupHealth.HEALTHY, migrated.health)
        assertEquals(GroupAppState.ENABLED, migrated.apps.single().state)
        assertEquals(GroupAppOrigin.SYSTEM_IMPORT, migrated.apps.single().origin)
        assertEquals(
            "3",
            readProperties(File(filesRoot, "groups/$ID_1/group.properties"))
                .getProperty("schemaVersion"),
        )
        assertEquals(
            GroupAppOrigin.SYSTEM_IMPORT.name,
            readProperties(File(filesRoot, "groups/$ID_1/apps/$LINE.properties"))
                .getProperty("origin"),
        )
    }

    @Test
    fun `origins remain isolated after store restart`() {
        val filesRoot = temporaryFolder.newFolder("restart")
        val store = FileGroupStore(filesRoot)
        val imported = store.create(ID_1, "匯入", EnvironmentBinding(8), 100)
        val playStore = store.create(ID_2, "商店", EnvironmentBinding(9), 200)
        store.addApp(imported.id, LINE, 300, GroupAppOrigin.SYSTEM_IMPORT)
        store.addApp(playStore.id, LINE, 400, GroupAppOrigin.PLAY_STORE)
        store.updateAppState(imported.id, LINE, GroupAppState.DISABLED)
        store.updateAppState(playStore.id, LINE, GroupAppState.ENABLED)

        val restarted = FileGroupStore(filesRoot)

        assertEquals(GroupAppState.DISABLED, restarted.find(imported.id)!!.apps.single().state)
        assertEquals(GroupAppOrigin.SYSTEM_IMPORT, restarted.find(imported.id)!!.apps.single().origin)
        assertEquals(GroupAppState.ENABLED, restarted.find(playStore.id)!!.apps.single().state)
        assertEquals(GroupAppOrigin.PLAY_STORE, restarted.find(playStore.id)!!.apps.single().origin)
    }

    private fun writeSchema2Group(filesRoot: File, groupId: String, packageName: String) {
        val groupRoot = File(filesRoot, "groups/$groupId")
        check(File(groupRoot, "data").mkdirs())
        check(File(groupRoot, "apps").mkdirs())
        writeProperties(
            File(groupRoot, "group.properties"),
            Properties().apply {
                setProperty("schemaVersion", "2")
                setProperty("id", groupId)
                setProperty("name", "舊群組")
                setProperty("createdAtEpochMillis", "100")
                setProperty("environmentBindingId", "8")
                setProperty("health", GroupHealth.HEALTHY.name)
                setProperty("googleServicesState", GoogleServicesState.NOT_PREPARED.name)
            },
        )
        writeProperties(
            File(groupRoot, "apps/$packageName.properties"),
            Properties().apply {
                setProperty("packageName", packageName)
                setProperty("addedAtEpochMillis", "200")
                setProperty("state", GroupAppState.ENABLED.name)
            },
        )
    }

    private fun readProperties(file: File): Properties =
        Properties().apply { file.inputStream().use(::load) }

    private fun writeProperties(file: File, properties: Properties) {
        FileOutputStream(file).use { properties.store(it, null) }
    }

    private companion object {
        const val ID_1 = "00000000-0000-0000-0000-000000000001"
        const val ID_2 = "00000000-0000-0000-0000-000000000002"
        const val LINE = "jp.naver.line.android"
    }
}
