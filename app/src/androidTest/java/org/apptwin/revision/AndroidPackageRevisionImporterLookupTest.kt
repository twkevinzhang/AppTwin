package org.apptwin.revision

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPackageRevisionImporterLookupTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun exactLookupMatchesDeviceWideEligibilityResult() {
        val importer = AndroidPackageRevisionImporter(context)
        val listed = importer.listCloneableApps()
        assumeTrue("device must expose at least one cloneable app", listed.isNotEmpty())

        listed.forEach { expected ->
            assertEquals(expected, importer.findCloneableApp(expected.packageName))
        }
    }

    @Test
    fun exactLookupExcludesHostPackage() {
        val importer = AndroidPackageRevisionImporter(context)

        assertNull(importer.findCloneableApp(context.packageName))
    }

    @Test
    fun exactLookupReturnsMissingForUnknownPackage() {
        val importer = AndroidPackageRevisionImporter(context)

        assertNull(importer.findCloneableApp("org.apptwin.test.package.that.does.not.exist"))
    }

    @Test
    fun legacySourceStatsAreUpgradedAfterFullVerification() {
        assumeTrue(
            "fixture package must be installed",
            runCatching { context.packageManager.getPackageInfo(FIXTURE_PACKAGE, 0) }.isSuccess,
        )
        val importer = AndroidPackageRevisionImporter(context)
        assertSuccessful(importer.sync(FIXTURE_PACKAGE))
        val revisionDirectory = requireNotNull(importer.activeRevisionDirectory(FIXTURE_PACKAGE))
        val metadataFile = File(revisionDirectory, "metadata.properties")
        val originalMetadata = metadataFile.readBytes()
        val metadata = metadataFile.loadProperties()
        metadata.remove("baseSourceSize")
        metadata.remove("baseSourceMtime")
        metadata.keys
            .map(Any::toString)
            .filter { key ->
                key.endsWith(".sourcePath") ||
                    key.endsWith(".sourceSize") ||
                    key.endsWith(".sourceMtime")
            }
            .forEach(metadata::remove)

        try {
            metadataFile.atomicReplace(metadata.toByteArray())
            assertFalse(importer.isSourceCurrent(FIXTURE_PACKAGE))
            val upgraded = importer.sync(FIXTURE_PACKAGE)

            assertTrue(upgraded is RevisionImportResult.AlreadyCurrent)
            assertTrue(importer.isSourceCurrent(FIXTURE_PACKAGE))
            val upgradedMetadata = metadataFile.loadProperties()
            assertTrue(upgradedMetadata.getProperty("baseSourceSize").toLong() > 0L)
            assertTrue(upgradedMetadata.getProperty("baseSourceMtime").toLong() > 0L)
        } finally {
            metadataFile.atomicReplace(originalMetadata)
        }
    }

    private fun File.loadProperties(): Properties = Properties().also { properties ->
        FileInputStream(this).use(properties::load)
    }

    private fun Properties.toByteArray(): ByteArray = ByteArrayOutputStream().use { output ->
        store(output, null)
        output.toByteArray()
    }

    private fun File.atomicReplace(bytes: ByteArray) {
        val pending = File.createTempFile("metadata-test-", ".properties", parentFile)
        try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            check(pending.setReadOnly()) { "unable to make fixture metadata read-only" }
            Files.move(
                pending.toPath(),
                toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            pending.delete()
        }
    }

    private fun assertSuccessful(result: RevisionImportResult) {
        assertTrue(
            "revision sync failed: $result",
            result is RevisionImportResult.Activated ||
                result is RevisionImportResult.AlreadyCurrent,
        )
    }

    private companion object {
        const val FIXTURE_PACKAGE = "org.apptwin.fixture"
    }
}
