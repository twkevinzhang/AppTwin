package org.maskaccounts.revisionstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.maskaccounts.packagesource.PackageArtifact
import org.maskaccounts.packagesource.PackageSourceSnapshot

class InMemoryRevisionStoreTest {
    private val signerA = "a".repeat(64)
    private val signerB = "b".repeat(64)

    @Test
    fun `staging does not replace active revision until activation`() {
        val store = InMemoryRevisionStore()
        val first = store.stage(source(1, listOf(signerA)), 100) as StageResult.Staged
        store.activate(first.revision.source.packageName, first.revision.id)
        val second = store.stage(source(2, listOf(signerA)), 200) as StageResult.Staged

        assertEquals(1L, store.active(PACKAGE_NAME)?.source?.versionCode)

        store.activate(PACKAGE_NAME, second.revision.id)
        assertEquals(2L, store.active(PACKAGE_NAME)?.source?.versionCode)
        assertEquals(
            RevisionState.RETIRED,
            store.revisions(PACKAGE_NAME).first().state,
        )
    }

    @Test
    fun `compatible signing rotation can activate`() {
        val store = activeStore(source(1, listOf(signerA)))
        val result = store.stage(source(2, listOf(signerA, signerB)), 200)

        val staged = result as StageResult.Staged
        store.activate(PACKAGE_NAME, staged.revision.id)

        assertEquals(signerB, store.active(PACKAGE_NAME)?.source?.currentSignerSha256)
    }

    @Test
    fun `unrelated signer is rejected without changing active revision`() {
        val store = activeStore(source(1, listOf(signerA)))

        val result = store.stage(source(2, listOf(signerB)), 200)

        assertEquals(
            StageResult.Rejected(RejectionReason.INCOMPATIBLE_SIGNING_LINEAGE),
            result,
        )
        assertEquals(1L, store.active(PACKAGE_NAME)?.source?.versionCode)
        assertEquals(1, store.revisions(PACKAGE_NAME).size)
    }

    @Test
    fun `version rollback is rejected`() {
        val store = activeStore(source(2, listOf(signerA)))

        val result = store.stage(source(1, listOf(signerA)), 200)

        assertEquals(StageResult.Rejected(RejectionReason.VERSION_ROLLBACK), result)
        assertEquals(2L, store.active(PACKAGE_NAME)?.source?.versionCode)
    }

    @Test
    fun `unknown revision cannot be activated`() {
        val store = InMemoryRevisionStore()
        assertNull(store.active(PACKAGE_NAME))
        assertThrows(NoSuchElementException::class.java) {
            store.activate(PACKAGE_NAME, "missing")
        }
    }

    private fun activeStore(initial: PackageSourceSnapshot): InMemoryRevisionStore =
        InMemoryRevisionStore().also { store ->
            val staged = store.stage(initial, 100) as StageResult.Staged
            store.activate(PACKAGE_NAME, staged.revision.id)
        }

    private fun source(version: Long, lineage: List<String>) = PackageSourceSnapshot(
        packageName = PACKAGE_NAME,
        versionCode = version,
        signingCertificateLineageSha256 = lineage,
        baseApk = PackageArtifact(
            path = "/source/base-$version.apk",
            sizeBytes = 1024,
            sha256 = "c".repeat(64),
        ),
        requiredSplitNames = emptySet(),
        splitApks = emptyList(),
        supportedAbis = setOf("arm64-v8a"),
    )

    private companion object {
        const val PACKAGE_NAME = "org.example.fixture"
    }
}
