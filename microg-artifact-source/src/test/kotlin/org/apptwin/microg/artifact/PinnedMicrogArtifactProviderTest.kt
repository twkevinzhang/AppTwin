package org.apptwin.microg.artifact

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PinnedMicrogArtifactProviderTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `stages complete verified release atomically and is idempotent`() {
        val sourceRoot = temporary.newFolder("source").toPath()
        val stagingRoot = temporary.newFolder("staging").toPath()
        val release = fixtureRelease()
        writeRelease(sourceRoot, release)
        val provider = fixtureProvider(sourceRoot, stagingRoot, release)

        val first = provider.stageAll()
        val modifiedAt = first.associate { it.manifest.kind to Files.getLastModifiedTime(it.apkPath) }
        val second = provider.stageAll()

        assertEquals(ProductionMicrogArtifactKind.entries.toSet(), second.map { it.manifest.kind }.toSet())
        assertEquals(first.map { it.apkPath }, second.map { it.apkPath })
        second.forEach { assertEquals(modifiedAt[it.manifest.kind], Files.getLastModifiedTime(it.apkPath)) }
        assertTrue(Files.list(stagingRoot).use { files ->
            files.noneMatch { it.fileName.toString().endsWith(".tmp") }
        })
    }

    @Test
    fun `missing either required artifact fails the whole release closed`() {
        val sourceRoot = temporary.newFolder("source").toPath()
        val release = fixtureRelease()
        Files.write(sourceRoot.resolve(release.artifacts.first().apkFileName), fixtureBytes("core"))
        val provider = fixtureProvider(
            sourceRoot,
            temporary.newFolder("staging").toPath(),
            release,
        )

        val failure = assertThrows(ArtifactSourceException::class.java) { provider.stageAll() }

        assertEquals(ArtifactSourceFailure.MISSING, failure.failure)
        assertFalse(failure.message!!.contains(temporary.root.absolutePath))
    }

    @Test
    fun `tampered companion source fails before any artifact is returned`() {
        val sourceRoot = temporary.newFolder("source").toPath()
        val stagingRoot = temporary.newFolder("staging").toPath()
        val release = fixtureRelease()
        writeRelease(sourceRoot, release)
        Files.write(
            sourceRoot.resolve(release.artifact(ProductionMicrogArtifactKind.COMPANION_STORE).apkFileName),
            "tampered".toByteArray(),
        )

        val failure = assertThrows(ArtifactSourceException::class.java) {
            fixtureProvider(sourceRoot, stagingRoot, release).stageAll()
        }

        assertEquals(ArtifactSourceFailure.APK_DIGEST_MISMATCH, failure.failure)
        assertTrue(Files.list(stagingRoot).use { it.findAny().isEmpty })
    }

    @Test
    fun `signer package and version mismatches fail closed`() {
        val sourceRoot = temporary.newFolder("source").toPath()
        val release = fixtureRelease()
        writeRelease(sourceRoot, release)
        val staging = temporary.newFolder("staging").toPath()
        val core = release.artifact(ProductionMicrogArtifactKind.GMS_CORE)

        fun failure(inspected: InspectedApk) = assertThrows(ArtifactSourceException::class.java) {
            PinnedMicrogArtifactProvider(
                LocalMicrogArtifactSource(sourceRoot),
                staging,
                ApkArtifactInspector { inspected },
                release,
            ).stageAll()
        }.failure

        assertEquals(
            ArtifactSourceFailure.SIGNER_MISMATCH,
            failure(
                InspectedApk(
                    listOf("f".repeat(64)),
                    core.packageName,
                    core.versionCode,
                    core.versionName,
                ),
            ),
        )
        assertEquals(
            ArtifactSourceFailure.PACKAGE_MISMATCH,
            failure(
                InspectedApk(
                    listOf(core.realSignerSha256),
                    "wrong.package",
                    core.versionCode,
                    core.versionName,
                ),
            ),
        )
        assertEquals(
            ArtifactSourceFailure.VERSION_MISMATCH,
            failure(
                InspectedApk(
                    listOf(core.realSignerSha256),
                    core.packageName,
                    core.versionCode + 1,
                    core.versionName,
                ),
            ),
        )
        assertEquals(
            ArtifactSourceFailure.VERSION_MISMATCH,
            failure(
                InspectedApk(
                    listOf(core.realSignerSha256),
                    core.packageName,
                    core.versionCode,
                    "wrong-version-name",
                ),
            ),
        )
    }

    @Test
    fun `production provider rejects companion manifest mismatch before reading source`() {
        val production = PinnedMicrogRelease.manifest
        val altered = production.copy(
            artifacts = production.artifacts.map {
                if (it.kind == ProductionMicrogArtifactKind.COMPANION_STORE) {
                    it.copy(versionCode = it.versionCode + 1)
                } else {
                    it
                }
            },
        )
        val provider = PinnedMicrogArtifactProvider.enforcingManifestForTest(
            LocalMicrogArtifactSource(temporary.newFolder("missing-source").toPath()),
            temporary.newFolder("staging").toPath(),
            inspectorFor(altered),
            altered,
        )

        val failure = assertThrows(ArtifactSourceException::class.java) { provider.stageAll() }

        assertEquals(ArtifactSourceFailure.MANIFEST_MISMATCH, failure.failure)
        assertEquals(
            PinnedMicrogRelease.EXPOSED_CERTIFICATE_SHA256,
            sha256(PinnedMicrogRelease.gmsCore.exposedCertificateDer),
        )
    }

    @Test
    fun `manifest DER mismatch and staged companion tampering are rejected and repaired`() {
        val sourceRoot = temporary.newFolder("source").toPath()
        val stagingRoot = temporary.newFolder("staging").toPath()
        val release = fixtureRelease()
        writeRelease(sourceRoot, release)
        val badRelease = release.copy(
            artifacts = release.artifacts.map {
                if (it.kind == ProductionMicrogArtifactKind.COMPANION_STORE) {
                    it.copy(exposedCertificateSha256 = "0".repeat(64))
                } else {
                    it
                }
            },
        )

        val manifestFailure = assertThrows(ArtifactSourceException::class.java) {
            fixtureProvider(sourceRoot, stagingRoot, badRelease).stageAll()
        }
        assertEquals(ArtifactSourceFailure.MANIFEST_MISMATCH, manifestFailure.failure)

        val provider = fixtureProvider(sourceRoot, stagingRoot, release)
        val first = provider.stageAll()
        val companion = first.single { it.manifest.kind == ProductionMicrogArtifactKind.COMPANION_STORE }
        Files.write(companion.apkPath, "tampered-staging".toByteArray())
        val repaired = provider.stageAll().single {
            it.manifest.kind == ProductionMicrogArtifactKind.COMPANION_STORE
        }
        assertEquals(
            fixtureBytes("companion").toList(),
            Files.readAllBytes(repaired.apkPath).toList(),
        )
    }

    @Test
    fun `diagnostics never expose local path or token shaped data`() {
        val sourceRoot = temporary.newFolder("private-token-123").toPath()
        val stagingRoot = temporary.newFolder("secret-path").toPath()
        val release = fixtureRelease()
        writeRelease(sourceRoot, release)

        val diagnostics = fixtureProvider(sourceRoot, stagingRoot, release)
            .stageAll()
            .joinToString { it.redactedDiagnostics().toString() }

        assertFalse(diagnostics.contains(sourceRoot.toString()))
        assertFalse(diagnostics.contains(stagingRoot.toString()))
        assertFalse(diagnostics.contains("token", ignoreCase = true))
    }

    private fun fixtureProvider(
        sourceRoot: Path,
        stagingRoot: Path,
        release: ProductionMicrogReleaseManifest,
    ) = PinnedMicrogArtifactProvider(
        LocalMicrogArtifactSource(sourceRoot),
        stagingRoot,
        inspectorFor(release),
        release,
    )

    private fun inspectorFor(release: ProductionMicrogReleaseManifest) = ApkArtifactInspector { apk ->
        val bytes = Files.readAllBytes(apk)
        val manifest = release.artifacts.single { fixtureBytes(label(it)).contentEquals(bytes) }
        InspectedApk(
            listOf(manifest.realSignerSha256),
            manifest.packageName,
            manifest.versionCode,
            manifest.versionName,
        )
    }

    private fun fixtureRelease(): ProductionMicrogReleaseManifest {
        val certificate = "fixture-certificate".toByteArray()
        fun artifact(
            kind: ProductionMicrogArtifactKind,
            packageName: String,
            label: String,
        ) = ProductionMicrogManifest(
            kind = kind,
            releaseId = "fixture-release",
            releaseVersionName = "fixture-release-version",
            versionName = "fixture-$label-version",
            packageName = packageName,
            versionCode = if (kind == ProductionMicrogArtifactKind.GMS_CORE) 1 else 2,
            apkFileName = "$label.apk",
            apkSha256 = sha256(fixtureBytes(label)),
            realSignerSha256 = "a".repeat(64),
            exposedCertificateDer = certificate,
            exposedCertificateSha256 = sha256(certificate),
        )
        return ProductionMicrogReleaseManifest(
            releaseId = "fixture-release",
            versionName = "fixture-release-version",
            artifacts = listOf(
                artifact(ProductionMicrogArtifactKind.GMS_CORE, "com.google.android.gms", "core"),
                artifact(
                    ProductionMicrogArtifactKind.COMPANION_STORE,
                    "com.android.vending",
                    "companion",
                ),
            ),
        )
    }

    private fun writeRelease(root: Path, release: ProductionMicrogReleaseManifest) {
        release.artifacts.forEach { Files.write(root.resolve(it.apkFileName), fixtureBytes(label(it))) }
    }

    private fun label(manifest: ProductionMicrogManifest) =
        if (manifest.kind == ProductionMicrogArtifactKind.GMS_CORE) "core" else "companion"

    private fun fixtureBytes(label: String) = "reviewed-$label-fixture".toByteArray()
}
