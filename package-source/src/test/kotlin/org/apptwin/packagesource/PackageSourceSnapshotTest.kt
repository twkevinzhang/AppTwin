package org.apptwin.packagesource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PackageSourceSnapshotTest {
    @Test
    fun `complete split set is accepted`() {
        val snapshot = snapshot(
            required = setOf("config.arm64_v8a", "config.zh"),
            actual = listOf("config.arm64_v8a", "config.zh"),
        )

        assertEquals("b".repeat(64), snapshot.currentSignerSha256)
        assertEquals(2, snapshot.splitApks.size)
    }

    @Test
    fun `missing split is rejected before staging`() {
        assertThrows(IllegalArgumentException::class.java) {
            snapshot(
                required = setOf("config.arm64_v8a", "config.zh"),
                actual = listOf("config.arm64_v8a"),
            )
        }
    }

    @Test
    fun `duplicate split is rejected before staging`() {
        assertThrows(IllegalArgumentException::class.java) {
            snapshot(
                required = setOf("config.zh"),
                actual = listOf("config.zh", "config.zh"),
            )
        }
    }

    private fun snapshot(required: Set<String>, actual: List<String>) = PackageSourceSnapshot(
        packageName = "org.example.fixture",
        versionCode = 10,
        signingCertificateLineageSha256 = listOf("a".repeat(64), "b".repeat(64)),
        baseApk = artifact("/source/base.apk"),
        requiredSplitNames = required,
        splitApks = actual.map { SplitArtifact(it, artifact("/source/$it.apk")) },
        supportedAbis = setOf("arm64-v8a"),
    )

    private fun artifact(path: String) = PackageArtifact(
        path = path,
        sizeBytes = 1024,
        sha256 = "c".repeat(64),
    )
}
