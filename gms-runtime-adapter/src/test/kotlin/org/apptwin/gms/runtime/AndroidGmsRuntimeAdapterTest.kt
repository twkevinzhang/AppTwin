package org.apptwin.gms.runtime

import com.lody.virtual.remote.TrustedPackageProvenance
import java.nio.file.Path
import java.util.ArrayDeque
import org.apptwin.gms.artifacts.GmsArtifactIdentity
import org.apptwin.gms.artifacts.GmsArtifactKind
import org.apptwin.gms.artifacts.GmsReleaseIdentity
import org.apptwin.gms.artifacts.TrustedGmsArtifact
import org.apptwin.gms.artifacts.TrustedGmsManifest
import org.apptwin.gms.model.GmsGroupId
import org.apptwin.gms.ports.GmsResetMode
import org.apptwin.gms.ports.GmsRuntimeMutationResult
import org.apptwin.microg.artifact.ArtifactSourceException
import org.apptwin.microg.artifact.ArtifactSourceFailure
import org.apptwin.microg.artifact.PinnedMicrogRelease
import org.apptwin.microg.artifact.StagedMicrogArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGmsRuntimeAdapterTest {
    private val groupA = GmsGroupId("group-a")
    private val groupB = GmsGroupId("group-b")
    private val operationA = "00000000-0000-0000-0000-000000000001"
    private val operationB = "00000000-0000-0000-0000-000000000002"

    @Test
    fun `enabling A installs only A with exact runtime provenance`() {
        val fixture = Fixture()

        val result = fixture.adapter.ensureEnabled(groupA, release(), operationA)

        assertTrue(result is GmsRuntimeMutationResult.Applied)
        assertTrue(fixture.engine.state(11).installed)
        assertFalse(fixture.engine.state(12).installed)
        assertEquals(listOf(11, 11), fixture.engine.installUsers)
        with(fixture.engine.provenances.single { it.packageName == PinnedMicrogRelease.PACKAGE_NAME }) {
            assertEquals(PinnedMicrogRelease.PACKAGE_NAME, packageName)
            assertEquals(PinnedMicrogRelease.VERSION_CODE.toInt(), versionCode)
            assertEquals(listOf(PinnedMicrogRelease.REAL_SIGNER_SHA256), realSignerSha256)
            assertEquals(PinnedMicrogRelease.APK_SHA256, baseApkSha256)
            assertTrue(effectiveSignature.contentEquals(
                PinnedMicrogRelease.gmsCore.exposedCertificateDer,
            ))
        }
    }

    @Test
    fun `same operation retries transient engine failure then converges`() {
        val fixture = Fixture()
        fixture.engine.installResults += RuntimeEngineResult.Retryable("ENGINE_INSTALL_RETRYABLE")

        val first = fixture.adapter.ensureEnabled(groupA, release(), operationA)
        val second = fixture.adapter.ensureEnabled(groupA, release(), operationA)
        val third = fixture.adapter.ensureEnabled(groupA, release(), operationA)

        assertEquals(
            GmsRuntimeMutationResult.RetryableFailure("ENGINE_INSTALL_RETRYABLE"),
            first,
        )
        assertTrue(second is GmsRuntimeMutationResult.Applied)
        assertTrue(third is GmsRuntimeMutationResult.AlreadySatisfied)
        assertEquals(3, fixture.engine.installUsers.size)
    }

    @Test
    fun `disable stops only A background and preserves its private data`() {
        val fixture = Fixture()
        fixture.adapter.ensureEnabled(groupA, release(), operationA)
        fixture.adapter.ensureEnabled(groupB, release(), operationB)
        fixture.engine.state(11).apply {
            dataSentinel = "account-checkin-token-a"
            jobs = true
            notifications = true
            pendingIntents = true
        }
        fixture.engine.state(12).apply {
            dataSentinel = "account-checkin-token-b"
            jobs = true
        }

        val result = fixture.adapter.ensureDisabled(groupA, operationB)

        assertTrue(result is GmsRuntimeMutationResult.Applied)
        assertFalse(fixture.engine.state(11).installed)
        assertTrue(fixture.engine.state(11).privateState)
        assertEquals("account-checkin-token-a", fixture.engine.state(11).dataSentinel)
        assertFalse(fixture.engine.hasBackgroundOwnership(11))
        assertTrue(fixture.engine.state(12).installed)
        assertEquals("account-checkin-token-b", fixture.engine.state(12).dataSentinel)
        assertTrue(fixture.engine.hasBackgroundOwnership(12))
        assertEquals(listOf(11), fixture.engine.suspendUsers)
        assertTrue(fixture.engine.uninstallUsers.isEmpty())
    }

    @Test
    fun `disable then enable rebinds code without deleting private data`() {
        val fixture = Fixture()
        fixture.adapter.ensureEnabled(groupA, release(), operationA)
        fixture.engine.state(11).dataSentinel = "preserved-account-token"

        val disabled = fixture.adapter.ensureDisabled(groupA, operationB)
        val reenabled = fixture.adapter.ensureEnabled(
            groupA,
            release(),
            "00000000-0000-0000-0000-000000000003",
        )

        assertTrue(disabled is GmsRuntimeMutationResult.Applied)
        assertTrue(reenabled is GmsRuntimeMutationResult.Applied)
        assertTrue(fixture.engine.state(11).installed)
        assertTrue(fixture.engine.state(11).privateState)
        assertEquals("preserved-account-token", fixture.engine.state(11).dataSentinel)
        assertTrue(fixture.engine.uninstallUsers.isEmpty())
    }

    @Test
    fun `missing disable receipt retries suspension even after package flag was committed absent`() {
        val fixture = Fixture()
        fixture.engine.state(11).apply {
            installed = false
            privateState = true
            dataSentinel = "preserved"
            notifications = true
        }
        fixture.engine.suspendResults += RuntimeEngineResult.Retryable("ENGINE_SUSPEND_RETRYABLE")

        val first = fixture.adapter.ensureDisabled(groupA, operationA)
        val retry = fixture.adapter.ensureDisabled(groupA, operationA)

        assertEquals(
            GmsRuntimeMutationResult.RetryableFailure("ENGINE_SUSPEND_RETRYABLE"),
            first,
        )
        assertTrue(retry is GmsRuntimeMutationResult.Applied)
        assertEquals(listOf(11, 11), fixture.engine.suspendUsers)
        assertFalse(fixture.engine.hasBackgroundOwnership(11))
        assertEquals("preserved", fixture.engine.state(11).dataSentinel)
    }

    @Test
    fun `reset to disabled clears private data and background ownership`() {
        val fixture = Fixture()
        fixture.adapter.ensureEnabled(groupA, release(), operationA)
        fixture.engine.state(11).apply {
            dataSentinel = "must-be-cleared"
            jobs = true
            notifications = true
            pendingIntents = true
        }

        val result = fixture.adapter.resetPrivateState(
            groupA,
            GmsResetMode.DISABLE_AFTER_RESET,
            null,
            operationB,
        )

        assertTrue(result is GmsRuntimeMutationResult.Applied)
        assertFalse(fixture.engine.state(11).installed)
        assertFalse(fixture.engine.state(11).privateState)
        assertEquals(null, fixture.engine.state(11).dataSentinel)
        assertFalse(fixture.engine.hasBackgroundOwnership(11))
        assertEquals(listOf(11), fixture.engine.uninstallUsers)
    }

    @Test
    fun `reset reenable is idempotent for the same operation`() {
        val fixture = Fixture()
        fixture.adapter.ensureEnabled(groupA, release(), operationA)

        val first = fixture.adapter.resetPrivateState(
            groupA,
            GmsResetMode.REENABLE_ACTIVE_RELEASE,
            release(),
            operationB,
        )
        val installs = fixture.engine.installUsers.size
        val uninstalls = fixture.engine.uninstallUsers.size
        val replay = fixture.adapter.resetPrivateState(
            groupA,
            GmsResetMode.REENABLE_ACTIVE_RELEASE,
            release(),
            operationB,
        )

        assertTrue(first is GmsRuntimeMutationResult.Applied)
        assertTrue(replay is GmsRuntimeMutationResult.AlreadySatisfied)
        assertEquals(installs, fixture.engine.installUsers.size)
        assertEquals(uninstalls, fixture.engine.uninstallUsers.size)
    }

    @Test
    fun `deleted group binding and reused operation cannot short circuit another user`() {
        val fixture = Fixture()
        fixture.adapter.ensureEnabled(groupA, release(), operationA)
        fixture.engine.presentUsers.remove(11)
        fixture.bindings["group-a"] = 13
        fixture.engine.presentUsers += 13

        val rebound = fixture.adapter.ensureEnabled(groupA, release(), operationA)

        assertTrue(rebound is GmsRuntimeMutationResult.Applied)
        assertEquals(listOf(11, 11, 13, 13), fixture.engine.installUsers)
    }

    @Test
    fun `artifact tamper and release mismatch are rejected without engine mutation`() {
        val fixture = Fixture()
        fixture.artifactFailure = ArtifactSourceFailure.APK_DIGEST_MISMATCH

        val tampered = fixture.adapter.ensureEnabled(groupA, release(), operationA)
        fixture.artifactFailure = null
        val mismatched = fixture.adapter.ensureEnabled(
            groupA,
            release().copy(
                release = release().release.copy(microGVersion = "0.3.99.future"),
            ),
            operationB,
        )

        assertEquals(
            GmsRuntimeMutationResult.Rejected("ARTIFACT_APK_DIGEST_MISMATCH"),
            tampered,
        )
        assertEquals(GmsRuntimeMutationResult.Rejected("RELEASE_MISMATCH"), mismatched)
        assertTrue(fixture.engine.installUsers.isEmpty())
    }

    @Test
    fun `release with an extra component is rejected fail closed`() {
        val fixture = Fixture()
        val extra = TrustedGmsArtifact(
            GmsArtifactIdentity(
                GmsArtifactKind.GSF_PROXY,
                "com.google.android.gsf",
                1,
                "a".repeat(64),
                "b".repeat(64),
            ),
        )

        val result = fixture.adapter.ensureEnabled(
            groupA,
            release().copy(artifacts = release().artifacts + extra),
            operationA,
        )

        assertEquals(GmsRuntimeMutationResult.Rejected("RELEASE_GMS_BUNDLE_INVALID"), result)
        assertTrue(fixture.engine.installUsers.isEmpty())
    }

    @Test
    fun `default user is forbidden and observation failures never look absent`() {
        val fixture = Fixture()
        fixture.bindings["group-a"] = 0
        assertEquals(
            GmsRuntimeMutationResult.Rejected("DEFAULT_USER_FORBIDDEN"),
            fixture.adapter.ensureEnabled(groupA, release(), operationA),
        )

        fixture.bindings["group-a"] = 11
        fixture.engine.throwOnObservation = true
        assertThrows(GmsRuntimeObservationException::class.java) {
            fixture.adapter.observe(groupA)
        }
        assertEquals(
            GmsRuntimeMutationResult.RetryableFailure("RUNTIME_OBSERVE_RETRYABLE"),
            fixture.adapter.ensureDisabled(groupA, operationB),
        )
    }

    private inner class Fixture {
        val bindings = linkedMapOf("group-a" to 11, "group-b" to 12)
        val engine = FakeEngine().apply { presentUsers += listOf(11, 12) }
        val receipts = MemoryReceipts()
        var artifactFailure: ArtifactSourceFailure? = null
        val adapter = AndroidGmsRuntimeAdapter(
            GmsGroupBindingResolver { bindings[it] },
            GmsArtifactStageProvider {
                artifactFailure?.let {
                    throw ArtifactSourceException(it, "raw /private/path token=secret")
                }
                PinnedMicrogRelease.manifest.artifacts.map { manifest ->
                    StagedMicrogArtifact(
                        Path.of("/fixture/${manifest.apkFileName}"),
                        manifest,
                        105_000_000,
                    )
                }
            },
            engine,
            receipts,
        )
    }

    private class MemoryReceipts : GmsOperationReceiptStore {
        private val values = mutableSetOf<GmsOperationReceipt>()
        override fun contains(receipt: GmsOperationReceipt): Boolean = receipt in values
        override fun record(receipt: GmsOperationReceipt) {
            values += receipt
        }
    }

    private class FakeEngine : GmsVirtualRuntimeGateway {
        data class State(
            var installed: Boolean = false,
            var version: Long? = null,
            var privateState: Boolean = false,
            var dataSentinel: String? = null,
            var jobs: Boolean = false,
            var notifications: Boolean = false,
            var pendingIntents: Boolean = false,
            val packages: MutableSet<String> = mutableSetOf(),
        )

        val presentUsers = mutableSetOf<Int>()
        private val states = mutableMapOf<Int, State>()
        val installResults = ArrayDeque<RuntimeEngineResult>()
        val suspendResults = ArrayDeque<RuntimeEngineResult>()
        val installUsers = mutableListOf<Int>()
        val suspendUsers = mutableListOf<Int>()
        val uninstallUsers = mutableListOf<Int>()
        val provenances = mutableListOf<TrustedPackageProvenance>()
        var throwOnObservation = false

        fun state(userId: Int): State = states.getOrPut(userId, ::State)

        override fun isVirtualUserPresent(userId: Int): Boolean = userId in presentUsers
        override fun isInstalled(userId: Int): Boolean {
            if (throwOnObservation) error("raw path and token")
            return state(userId).installed
        }
        override fun installedVersionCode(userId: Int): Long? = state(userId).version
        override fun hasPrivateState(userId: Int): Boolean {
            if (throwOnObservation) error("raw path and token")
            return state(userId).privateState
        }
        override fun hasBackgroundOwnership(userId: Int): Boolean {
            if (throwOnObservation) error("raw path and token")
            return state(userId).run { jobs || notifications || pendingIntents }
        }
        override fun installTrusted(
            userId: Int,
            apk: Path,
            provenance: TrustedPackageProvenance,
        ): RuntimeEngineResult {
            installUsers += userId
            provenances += provenance
            installResults.pollFirst()?.let { return it }
            state(userId).apply {
                packages += provenance.packageName
                installed = packages.containsAll(
                    listOf(
                        PinnedMicrogRelease.PACKAGE_NAME,
                        PinnedMicrogRelease.COMPANION_PACKAGE_NAME,
                    ),
                )
                version = if (installed) PinnedMicrogRelease.VERSION_CODE else null
            }
            return RuntimeEngineResult.Success
        }
        override fun preparePrivateState(userId: Int): RuntimeEngineResult {
            state(userId).privateState = true
            return RuntimeEngineResult.Success
        }
        override fun suspendPreservingData(userId: Int): RuntimeEngineResult {
            suspendUsers += userId
            suspendResults.pollFirst()?.let { return it }
            state(userId).apply {
                installed = false
                version = null
                packages.clear()
                jobs = false
                notifications = false
                pendingIntents = false
            }
            return RuntimeEngineResult.Success
        }
        override fun uninstallAndClear(userId: Int): RuntimeEngineResult {
            uninstallUsers += userId
            state(userId).apply {
                installed = false
                version = null
                packages.clear()
                privateState = false
                dataSentinel = null
                jobs = false
                notifications = false
                pendingIntents = false
            }
            return RuntimeEngineResult.Success
        }
    }

    private fun release() = TrustedGmsManifest(
        release = GmsReleaseIdentity(
            releaseId = PinnedMicrogRelease.RELEASE_ID,
            microGVersion = PinnedMicrogRelease.VERSION_NAME,
            manifestSha256 = "c".repeat(64),
            manifestSignerSha256 = "d".repeat(64),
        ),
        artifacts = listOf(
            TrustedGmsArtifact(
                GmsArtifactIdentity(
                    GmsArtifactKind.GMS_CORE,
                    PinnedMicrogRelease.PACKAGE_NAME,
                    PinnedMicrogRelease.VERSION_CODE,
                    PinnedMicrogRelease.REAL_SIGNER_SHA256,
                    PinnedMicrogRelease.APK_SHA256,
                ),
                PinnedMicrogRelease.EXPOSED_CERTIFICATE_SHA256,
            ),
            TrustedGmsArtifact(
                GmsArtifactIdentity(
                    GmsArtifactKind.FAKE_STORE,
                    PinnedMicrogRelease.COMPANION_PACKAGE_NAME,
                    PinnedMicrogRelease.COMPANION_VERSION_CODE,
                    PinnedMicrogRelease.REAL_SIGNER_SHA256,
                    PinnedMicrogRelease.COMPANION_APK_SHA256,
                ),
                PinnedMicrogRelease.EXPOSED_CERTIFICATE_SHA256,
            ),
        ),
        issuedAtEpochMillis = 1,
    )
}
