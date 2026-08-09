package org.apptwin.revision

import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileActiveRevisionLookupTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `missing pointer is absent but existing invalid metadata is corrupt`() {
        val root = temporaryFolder.newFolder("revisions")
        val packageRoot = File(root, PACKAGE_NAME).apply { mkdirs() }
        val lookup = FileActiveRevisionLookup(root)

        assertEquals(ActiveRevisionLookupResult.Absent, lookup.read(PACKAGE_NAME))

        File(packageRoot, "active").writeText("revision-1\n")
        File(packageRoot, "revision-1").apply { mkdirs() }

        val result = lookup.read(PACKAGE_NAME)
        assertTrue(result is ActiveRevisionLookupResult.Corrupt)
    }

    @Test
    fun `missing signer is corrupt so transition cannot fail open`() {
        val root = temporaryFolder.newFolder("revisions")
        writeRevision(root, includeSigner = false)

        val result = FileActiveRevisionLookup(root).read(PACKAGE_NAME)

        assertTrue(result is ActiveRevisionLookupResult.Corrupt)
        assertTrue((result as ActiveRevisionLookupResult.Corrupt).reason.contains("currentSigner"))
    }

    @Test
    fun `complete active metadata returns typed record`() {
        val root = temporaryFolder.newFolder("revisions")
        writeRevision(root, includeSigner = true)

        val result = FileActiveRevisionLookup(root).read(PACKAGE_NAME)

        assertTrue(result is ActiveRevisionLookupResult.Found)
        assertEquals(7L, (result as ActiveRevisionLookupResult.Found).summary.versionCode)
    }

    private fun writeRevision(root: File, includeSigner: Boolean) {
        val packageRoot = File(root, PACKAGE_NAME).apply { mkdirs() }
        File(packageRoot, "active").writeText("revision-1\n")
        val revision = File(packageRoot, "revision-1").apply { mkdirs() }
        File(revision, "base.apk").writeText("fixture")
        Properties().apply {
            setProperty("packageName", PACKAGE_NAME)
            setProperty("revisionId", "revision-1")
            setProperty("versionCode", "7")
            if (includeSigner) setProperty("currentSigner", "a".repeat(64))
        }.also { properties ->
            File(revision, "metadata.properties").outputStream().use { properties.store(it, null) }
        }
    }

    private companion object {
        const val PACKAGE_NAME = "org.apptwin.fixture"
    }
}
