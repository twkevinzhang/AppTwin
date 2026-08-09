package org.apptwin.runtime

import java.io.File
import java.io.FileInputStream
import java.util.Properties
import org.apptwin.groups.FileGroupStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeDiagnosticsSinkTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `diagnostics failure does not change successful launch result`() {
        val failure = IllegalStateException("disk full")
        val failures = mutableListOf<Throwable>()
        val recorder = RuntimeLaunchRecorder(
            sink = RuntimeDiagnosticsSink { throw failure },
            onFailure = failures::add,
        )
        val started = RuntimeLaunchResult.Started("com.example.app", "org.apptwin:p", "/data/app")

        val result = recorder.record(started, diagnostics("com.example.app", "/data/app"))

        assertSame(started, result)
        assertEquals(listOf(failure), failures)
    }

    @Test
    fun `file sink preserves mappings for other packages`() {
        val filesRoot = temporaryFolder.newFolder("files")
        val groupData = File(filesRoot, "groups/$GROUP_ID/${FileGroupStore.DATA_DIRECTORY}")
        assertTrue(groupData.mkdirs())
        val sink = FileRuntimeDiagnosticsSink(filesRoot)

        sink.persist(diagnostics("com.example.first", "/data/first"))
        sink.persist(diagnostics("com.example.second", "/data/second"))

        val properties = Properties().apply {
            FileInputStream(File(groupData, FileGroupStore.RUNTIME_METADATA)).use(::load)
        }
        assertEquals(GROUP_ID, properties.getProperty("groupId"))
        assertEquals("7", properties.getProperty("environmentBindingId"))
        assertEquals("/data/first", properties.getProperty("runtimeDataDirectory.com.example.first"))
        assertEquals("/data/second", properties.getProperty("runtimeDataDirectory.com.example.second"))
        assertTrue(groupData.listFiles().orEmpty().none { it.name.startsWith(".runtime.properties-") })
    }

    private fun diagnostics(packageName: String, dataDirectory: String) = RuntimeDiagnostics(
        groupId = GROUP_ID,
        environmentBindingId = 7,
        packageName = packageName,
        runtimeDataDirectory = dataDirectory,
    )

    private companion object {
        const val GROUP_ID = "00000000-0000-0000-0000-000000000001"
    }
}
