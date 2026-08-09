package org.apptwin.gms

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.apptwin.groups.EnvironmentBinding
import org.apptwin.groups.FileGroupStore
import org.apptwin.microg.artifact.PinnedMicrogRelease
import org.apptwin.microg.artifact.ProductionMicrogArtifactKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsProductionCompositionTest {
    @Test
    fun `active release uses unique host signer and exact pinned artifact`() {
        val hostSigner = "a".repeat(64)

        val release = requireNotNull(PinnedActiveGmsReleasePort { hostSigner }.current())

        assertEquals(PinnedMicrogRelease.RELEASE_ID, release.release.releaseId)
        assertEquals(hostSigner, release.release.manifestSignerSha256)
        assertEquals(
            PinnedMicrogRelease.APK_SHA256,
            release.artifacts.single { it.identity.kind.name == "GMS_CORE" }.identity.apkSha256,
        )
        assertEquals(2, release.artifacts.size)
        val companion = release.artifacts.single { it.identity.packageName == "com.android.vending" }
        assertEquals(PinnedMicrogRelease.COMPANION_VERSION_CODE, companion.identity.versionCode)
        assertEquals(PinnedMicrogRelease.COMPANION_APK_SHA256, companion.identity.apkSha256)
        assertEquals(
            PinnedMicrogRelease.EXPOSED_CERTIFICATE_SHA256,
            companion.exposedCompatibilitySignatureSha256,
        )
        assertEquals(1_777_027_763_000L, release.issuedAtEpochMillis)
        assertNull(release.expiresAtEpochMillis)
    }

    @Test
    fun `missing ambiguous or malformed host signer revokes active release`() {
        assertNull(PinnedActiveGmsReleasePort { null }.current())
        assertNull(PinnedActiveGmsReleasePort { "not-a-sha256" }.current())
    }

    @Test
    fun `canonical manifest digest is deterministic and field sensitive`() {
        val manifest = PinnedMicrogRelease.manifest

        val first = canonicalManifestSha256(manifest)
        val second = canonicalManifestSha256(manifest)
        val companion = manifest.artifact(ProductionMicrogArtifactKind.COMPANION_STORE)
        val altered = canonicalManifestSha256(
            manifest.copy(
                artifacts = manifest.artifacts.map {
                    if (it.kind == companion.kind) it.copy(versionCode = it.versionCode + 1) else it
                },
            ),
        )

        assertEquals(first, second)
        assertTrue(Regex("[0-9a-f]{64}").matches(first))
        assertFalse(first == altered)
    }

    @Test
    fun `asset source copies once with durable atomic activation`() {
        val root = Files.createTempDirectory("apptwin-microg-asset").resolve("source").toFile()
        val bytes = "fixture bytes".toByteArray()
        var opens = 0
        val synced = mutableListOf<String>()
        val source = AssetMicrogArtifactSource(
            sourceRoot = root,
            openAsset = {
                opens += 1
                ByteArrayInputStream(bytes)
            },
            directorySync = { synced += it.absolutePath },
            testOnly = Unit,
        )

        val manifest = PinnedMicrogRelease.companionStore
        val first = source.locate(manifest)
        val second = source.locate(manifest)

        assertEquals(first, second)
        assertEquals(bytes.toList(), Files.readAllBytes(first).toList())
        assertEquals(1, opens)
        assertTrue(synced.any { it.endsWith("/source") })
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `asset source materializes both pinned artifacts independently`() {
        val root = Files.createTempDirectory("apptwin-microg-pair").resolve("source").toFile()
        val opened = mutableListOf<String>()
        val source = AssetMicrogArtifactSource(
            sourceRoot = root,
            openAsset = { name ->
                opened += name
                ByteArrayInputStream(name.toByteArray())
            },
            directorySync = {},
            testOnly = Unit,
        )

        PinnedMicrogRelease.manifest.artifacts.forEach(source::locate)

        assertEquals(
            PinnedMicrogRelease.manifest.artifacts.map { "microg/${it.apkFileName}" },
            opened,
        )
        assertEquals(2, root.listFiles().orEmpty().size)
    }

    @Test
    fun `binding resolver reads only group store and rejects user zero`() {
        val root = Files.createTempDirectory("apptwin-gms-binding").toFile()
        val groups = FileGroupStore(root)
        val validId = "11111111-1111-1111-1111-111111111111"
        val zeroId = "22222222-2222-2222-2222-222222222222"
        groups.create(validId, "valid", EnvironmentBinding(7), 1)
        groups.create(zeroId, "invalid", EnvironmentBinding(0), 2)
        val resolver = fileGroupBindingResolver(groups)

        assertEquals(7, resolver.virtualUserId(validId))
        assertNull(resolver.virtualUserId(zeroId))
        assertNull(resolver.virtualUserId("33333333-3333-3333-3333-333333333333"))
    }
}
