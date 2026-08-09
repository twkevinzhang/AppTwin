package org.apptwin.microg.artifact

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PinnedMicrogProductionArtifactTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `real reviewed release pair passes hash signer package version and staging policy`() {
        val core = configured("pinnedMicrogApk")
        val companion = configured("pinnedMicrogCompanionApk")
        val sourceRoot = temporary.newFolder("source").toPath()
        Files.copy(core, sourceRoot.resolve(PinnedMicrogRelease.APK_FILE_NAME))
        Files.copy(companion, sourceRoot.resolve(PinnedMicrogRelease.COMPANION_APK_FILE_NAME))
        val provider = PinnedMicrogArtifactProvider(
            LocalMicrogArtifactSource(sourceRoot),
            temporary.newFolder("staging").toPath(),
        )

        val staged = provider.stageAll()

        assertEquals(2, staged.size)
        assertEquals(
            setOf(PinnedMicrogRelease.APK_SHA256, PinnedMicrogRelease.COMPANION_APK_SHA256),
            staged.map { sha256(it.apkPath) }.toSet(),
        )
        assertTrue(staged.single { it.manifest.kind == ProductionMicrogArtifactKind.GMS_CORE }.byteCount > 100_000_000L)
        assertTrue(staged.single { it.manifest.kind == ProductionMicrogArtifactKind.COMPANION_STORE }.byteCount > 1_000_000L)
    }

    private fun configured(property: String): Path {
        val value = System.getProperty(property).orEmpty()
        assumeTrue("$property is required for production artifact smoke", value.isNotBlank())
        return Path.of(value).also {
            assumeTrue("configured pinned artifact must exist", Files.isRegularFile(it))
        }
    }
}
