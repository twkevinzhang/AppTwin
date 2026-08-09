package org.apptwin.runtime

import java.io.File
import org.apptwin.revision.ActiveRuntimeRevision
import org.apptwin.revision.PackageArtifactIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimePackageSynchronizerTest {
    @get:org.junit.Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `artifact reader pairs original split names with copied paths instead of filenames`() {
        val base = temporaryFolder.newFile("base.apk").apply { writeText("base") }
        val first = temporaryFolder.newFile("split-000.apk").apply { writeText("language") }
        val second = temporaryFolder.newFile("split-001.apk").apply { writeText("abi") }

        val identity = RuntimePackageArtifactReader.read(
            baseApk = base,
            splitNames = arrayOf("config.zh", "config.arm64_v8a"),
            splitCodePaths = arrayOf(first.path, second.path),
            sha256 = { file -> file.readText() },
        )

        assertEquals("base", identity.baseSha256)
        assertEquals(
            mapOf("config.zh" to "language", "config.arm64_v8a" to "abi"),
            identity.splitSha256ByName,
        )
    }
    @Test
    fun `missing package installs active revision before binding Group user`() {
        val gateway = FakeGateway()

        RuntimePackageSynchronizer(gateway).synchronize(REVISION, USER_ID)

        assertEquals(listOf(false), gateway.updateCalls)
        assertEquals(IDENTITY, gateway.installedIdentity)
        assertTrue(gateway.userInstalled)
    }

    @Test
    fun `matching artifact identity does not rewrite shared code`() {
        val gateway = FakeGateway(installed = true, installedIdentity = IDENTITY)

        RuntimePackageSynchronizer(gateway).synchronize(REVISION, USER_ID)

        assertTrue(gateway.updateCalls.isEmpty())
        assertTrue(gateway.userInstalled)
    }

    @Test
    fun `different artifact identity updates shared code even when package already exists`() {
        val gateway = FakeGateway(
            installed = true,
            installedIdentity = PackageArtifactIdentity("old-base", emptyMap()),
        )

        RuntimePackageSynchronizer(gateway).synchronize(REVISION, USER_ID)

        assertEquals(listOf(true), gateway.updateCalls)
        assertEquals(IDENTITY, gateway.installedIdentity)
        assertTrue(gateway.userInstalled)
    }

    @Test
    fun `failed update does not bind user or continue toward launch`() {
        val gateway = FakeGateway(
            installed = true,
            installedIdentity = PackageArtifactIdentity("old-base", emptyMap()),
            installResult = RuntimePackageInstallResult(false, "copy failed"),
        )

        try {
            RuntimePackageSynchronizer(gateway).synchronize(REVISION, USER_ID)
            fail("update should fail")
        } catch (error: IllegalStateException) {
            assertEquals("copy failed", error.message)
        }

        assertFalse(gateway.userInstalled)
        assertEquals(0, gateway.bindCalls)
    }

    @Test
    fun `successful install with wrong copied identity does not bind user`() {
        val gateway = FakeGateway(
            installed = true,
            installedIdentity = PackageArtifactIdentity("old-base", emptyMap()),
            identityAfterInstall = PackageArtifactIdentity("mixed-base", emptyMap()),
        )

        try {
            RuntimePackageSynchronizer(gateway).synchronize(REVISION, USER_ID)
            fail("identity verification should fail")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains(REVISION.revisionId))
        }

        assertFalse(gateway.userInstalled)
        assertEquals(0, gateway.bindCalls)
    }

    private class FakeGateway(
        private var installed: Boolean = false,
        var installedIdentity: PackageArtifactIdentity? = null,
        private val installResult: RuntimePackageInstallResult = RuntimePackageInstallResult(true),
        private val identityAfterInstall: PackageArtifactIdentity = IDENTITY,
    ) : RuntimePackageGateway {
        val updateCalls = mutableListOf<Boolean>()
        var userInstalled = false
        var bindCalls = 0

        override fun isInstalled(packageName: String): Boolean = installed

        override fun installedArtifactIdentity(packageName: String): PackageArtifactIdentity? =
            installedIdentity

        override fun installOrUpdate(
            revision: ActiveRuntimeRevision,
            update: Boolean,
        ): RuntimePackageInstallResult {
            updateCalls += update
            if (installResult.isSuccess) {
                installed = true
                installedIdentity = identityAfterInstall
            }
            return installResult
        }

        override fun isInstalledForUser(userId: Int, packageName: String): Boolean = userInstalled

        override fun installForUser(userId: Int, packageName: String): Boolean {
            bindCalls += 1
            userInstalled = true
            return true
        }
    }

    private companion object {
        const val USER_ID = 7
        val IDENTITY = PackageArtifactIdentity(
            baseSha256 = "new-base",
            splitSha256ByName = mapOf("config.arm64_v8a" to "new-split"),
        )
        val REVISION = ActiveRuntimeRevision(
            packageName = "com.example.app",
            revisionId = "42-1000-deadbeef",
            directory = File("revision"),
            artifactIdentity = IDENTITY,
        )
    }
}
