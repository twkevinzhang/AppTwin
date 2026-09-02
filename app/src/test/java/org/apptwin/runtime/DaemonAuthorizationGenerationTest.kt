package org.apptwin.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DaemonAuthorizationGenerationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `durable authorization mutation invalidates cached visible token`() {
        val root = temporaryFolder.newFolder("files")
        val initial = DaemonAuthorizationGeneration.current(root)

        DaemonAuthorizationGeneration.invalidateBeforeMutation(root)
        val changed = DaemonAuthorizationGeneration.current(root)

        assertEquals("initial", initial)
        assertNotNull(changed)
        assertNotEquals(initial, changed)
        assertEquals(changed, root.resolve("daemon_authorization_generation").readText())
    }
}
