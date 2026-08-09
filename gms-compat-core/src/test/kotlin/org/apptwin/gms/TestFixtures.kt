package org.apptwin.gms

import org.apptwin.gms.artifacts.GmsArtifactIdentity
import org.apptwin.gms.artifacts.GmsArtifactKind
import org.apptwin.gms.artifacts.GmsReleaseIdentity
import org.apptwin.gms.artifacts.TrustedGmsArtifact
import org.apptwin.gms.artifacts.TrustedGmsManifest

internal const val SIGNER_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
internal const val SIGNER_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
internal const val APK_A = "1111111111111111111111111111111111111111111111111111111111111111"
internal const val APK_B = "2222222222222222222222222222222222222222222222222222222222222222"
internal const val MANIFEST_A = "3333333333333333333333333333333333333333333333333333333333333333"
internal const val COMPAT_SIGNATURE =
    "4444444444444444444444444444444444444444444444444444444444444444"

internal fun trustedManifest(releaseId: String = "release-1"): TrustedGmsManifest =
    TrustedGmsManifest(
        release = GmsReleaseIdentity(
            releaseId = releaseId,
            microGVersion = "0.3.10.250932",
            manifestSha256 = MANIFEST_A,
            manifestSignerSha256 = SIGNER_A,
        ),
        artifacts = listOf(
            TrustedGmsArtifact(
                identity = GmsArtifactIdentity(
                    kind = GmsArtifactKind.GMS_CORE,
                    packageName = "com.google.android.gms",
                    versionCode = 250932001,
                    realSignerSha256 = SIGNER_A,
                    apkSha256 = APK_A,
                ),
                exposedCompatibilitySignatureSha256 = COMPAT_SIGNATURE,
            ),
        ),
        issuedAtEpochMillis = 1_000,
        expiresAtEpochMillis = 10_000,
    )
