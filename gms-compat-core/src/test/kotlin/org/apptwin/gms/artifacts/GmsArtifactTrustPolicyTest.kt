package org.apptwin.gms.artifacts

import org.apptwin.gms.APK_A
import org.apptwin.gms.APK_B
import org.apptwin.gms.COMPAT_SIGNATURE
import org.apptwin.gms.SIGNER_A
import org.apptwin.gms.SIGNER_B
import org.apptwin.gms.trustedManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GmsArtifactTrustPolicyTest {
    @Test
    fun `trusts only exact artifact identity in active release`() {
        val manifest = trustedManifest()

        assertEquals(
            ArtifactTrustDecision.Trusted,
            GmsArtifactTrustPolicy.verify(
                candidateReleaseId = "release-1",
                candidate = manifest.artifacts.single().identity,
                manifest = manifest,
                nowEpochMillis = 2_000,
            ),
        )
    }

    @Test
    fun `rejects signer digest and release mismatches fail closed`() {
        val manifest = trustedManifest()
        val expected = manifest.artifacts.single().identity

        assertEquals(
            ArtifactTrustDecision.Rejected(ArtifactTrustFailure.SIGNER_MISMATCH),
            GmsArtifactTrustPolicy.verify(
                "release-1",
                expected.copy(realSignerSha256 = SIGNER_B),
                manifest,
                2_000,
            ),
        )
        assertEquals(
            ArtifactTrustDecision.Rejected(ArtifactTrustFailure.APK_DIGEST_MISMATCH),
            GmsArtifactTrustPolicy.verify(
                "release-1",
                expected.copy(apkSha256 = APK_B),
                manifest,
                2_000,
            ),
        )
        assertEquals(
            ArtifactTrustDecision.Rejected(ArtifactTrustFailure.RELEASE_MISMATCH),
            GmsArtifactTrustPolicy.verify("release-2", expected, manifest, 2_000),
        )
    }

    @Test
    fun `rejects expired manifest`() {
        val manifest = trustedManifest()

        assertEquals(
            ArtifactTrustDecision.Rejected(ArtifactTrustFailure.MANIFEST_EXPIRED),
            GmsArtifactTrustPolicy.verify(
                "release-1",
                manifest.artifacts.single().identity,
                manifest,
                10_000,
            ),
        )
    }

    @Test
    fun `artifact kind fixes package name and only gms core can spoof`() {
        assertThrows(IllegalArgumentException::class.java) {
            GmsArtifactIdentity(
                GmsArtifactKind.GMS_CORE,
                "org.attacker.gms",
                1,
                SIGNER_A,
                APK_A,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TrustedGmsArtifact(
                GmsArtifactIdentity(
                    GmsArtifactKind.GSF_PROXY,
                    "com.google.android.gsf",
                    1,
                    SIGNER_A,
                    APK_A,
                ),
                COMPAT_SIGNATURE,
            )
        }
    }
}
