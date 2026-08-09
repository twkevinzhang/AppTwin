package org.apptwin.microg.artifact

import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkUtils
import com.android.apksig.internal.apk.AndroidBinXmlParser
import com.android.apksig.util.DataSources
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

enum class ArtifactSourceFailure {
    MISSING,
    MANIFEST_MISMATCH,
    APK_DIGEST_MISMATCH,
    SIGNER_MISMATCH,
    PACKAGE_MISMATCH,
    VERSION_MISMATCH,
    APK_SIGNATURE_INVALID,
    STAGING_FAILED,
}

class ArtifactSourceException(
    val failure: ArtifactSourceFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

fun interface MicrogArtifactSource {
    /** Returns bytes from an explicit local/embedded source; implementations must not resolve URLs. */
    fun locate(manifest: ProductionMicrogManifest): Path
}

class LocalMicrogArtifactSource(private val root: Path) : MicrogArtifactSource {
    override fun locate(manifest: ProductionMicrogManifest): Path = root.resolve(manifest.apkFileName)
}

data class InspectedApk(
    val signerSha256: List<String>,
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
)

fun interface ApkArtifactInspector {
    fun inspect(apk: Path): InspectedApk
}

class ApksigArtifactInspector : ApkArtifactInspector {
    override fun inspect(apk: Path): InspectedApk {
        val result = ApkVerifier.Builder(apk.toFile()).build().verify()
        if (!result.isVerified) {
            throw ArtifactSourceException(
                ArtifactSourceFailure.APK_SIGNATURE_INVALID,
                "Pinned artifact APK signature verification failed",
            )
        }
        val signers = result.signerCertificates.map { sha256(it.encoded) }.sorted()
        RandomAccessFile(apk.toFile(), "r").use { file ->
            val manifest = ApkUtils.getAndroidManifest(DataSources.asDataSource(file))
            return InspectedApk(
                signerSha256 = signers,
                packageName = ApkUtils.getPackageNameFromBinaryAndroidManifest(manifest.duplicate()),
                versionCode = ApkUtils.getLongVersionCodeFromBinaryAndroidManifest(
                    manifest.duplicate(),
                ),
                versionName = readVersionName(manifest.duplicate()),
            )
        }
    }

    private fun readVersionName(manifest: java.nio.ByteBuffer): String? {
        val parser = AndroidBinXmlParser(manifest)
        while (parser.eventType != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
            if (
                parser.eventType == AndroidBinXmlParser.EVENT_START_ELEMENT &&
                parser.name == "manifest"
            ) {
                repeat(parser.attributeCount) { index ->
                    if (
                        parser.getAttributeNameResourceId(index) == VERSION_NAME_RESOURCE_ID &&
                        parser.getAttributeValueType(index) == AndroidBinXmlParser.VALUE_TYPE_STRING
                    ) {
                        return parser.getAttributeStringValue(index)
                    }
                }
            }
            parser.next()
        }
        return null
    }

    private companion object {
        const val VERSION_NAME_RESOURCE_ID = 0x0101021c
    }
}

data class StagedMicrogArtifact(
    /** Adapter-only path. Never include this field in exported diagnostics. */
    val apkPath: Path,
    val manifest: ProductionMicrogManifest,
    val byteCount: Long,
) {
    fun redactedDiagnostics(): RedactedArtifactDiagnostics = RedactedArtifactDiagnostics(
        kind = manifest.kind,
        releaseId = manifest.releaseId,
        versionName = manifest.versionName,
        apkSha256 = manifest.apkSha256,
        realSignerSha256 = manifest.realSignerSha256,
        exposedCertificateSha256 = manifest.exposedCertificateSha256,
        byteCount = byteCount,
    )
}

/** Contains no filesystem path, URL, account, token, credential, or arbitrary exception text. */
data class RedactedArtifactDiagnostics(
    val kind: ProductionMicrogArtifactKind,
    val releaseId: String,
    val versionName: String,
    val apkSha256: String,
    val realSignerSha256: String,
    val exposedCertificateSha256: String,
    val byteCount: Long,
)

class PinnedMicrogArtifactProvider private constructor(
    private val source: MicrogArtifactSource,
    private val stagingRoot: Path,
    private val inspector: ApkArtifactInspector,
    private val release: ProductionMicrogReleaseManifest,
    private val enforceProductionPin: Boolean,
) {
    constructor(
        source: MicrogArtifactSource,
        stagingRoot: Path,
        inspector: ApkArtifactInspector = ApksigArtifactInspector(),
    ) : this(source, stagingRoot, inspector, PinnedMicrogRelease.manifest, true)

    internal constructor(
        source: MicrogArtifactSource,
        stagingRoot: Path,
        inspector: ApkArtifactInspector,
        release: ProductionMicrogReleaseManifest,
    ) : this(source, stagingRoot, inspector, release, false)

    internal companion object {
        fun enforcingManifestForTest(
            source: MicrogArtifactSource,
            stagingRoot: Path,
            inspector: ApkArtifactInspector,
            release: ProductionMicrogReleaseManifest,
        ) = PinnedMicrogArtifactProvider(
            source,
            stagingRoot,
            inspector,
            release,
            enforceProductionPin = true,
        )
    }

    /** Stages the complete reviewed pair. No partial result is returned if either artifact fails. */
    fun stageAll(): List<StagedMicrogArtifact> {
        verifyReleaseManifest()
        val sourceFiles = release.artifacts.associateWith { manifest ->
            source.locate(manifest).also { path ->
                if (!Files.isRegularFile(path)) {
                    throw ArtifactSourceException(
                        ArtifactSourceFailure.MISSING,
                        "A required pinned microG release artifact is unavailable",
                    )
                }
                verifyArtifact(path, manifest)
            }
        }
        Files.createDirectories(stagingRoot)
        return release.artifacts.map { manifest ->
            stageVerifiedSource(requireNotNull(sourceFiles[manifest]), manifest)
        }
    }

    /** Production still verifies the whole release before exposing any individual artifact. */
    fun stage(kind: ProductionMicrogArtifactKind): StagedMicrogArtifact =
        stageAll().single { it.manifest.kind == kind }

    private fun stageVerifiedSource(
        sourceFile: Path,
        manifest: ProductionMicrogManifest,
    ): StagedMicrogArtifact {
        val destination = stagingRoot.resolve(manifest.apkFileName)
        if (
            Files.isRegularFile(destination) &&
            runCatching { verifyArtifact(destination, manifest) }.isSuccess
        ) {
            return staged(destination, manifest)
        }
        val temporary = stagingRoot.resolve(".${manifest.apkFileName}.${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(sourceFile, StandardOpenOption.READ).use { input ->
                FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                ).use { output ->
                    var position = 0L
                    while (position < input.size()) {
                        position += input.transferTo(position, input.size() - position, output)
                    }
                    output.force(true)
                }
            }
            verifyArtifact(temporary, manifest)
            Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            verifyArtifact(destination, manifest)
            return staged(destination, manifest)
        } catch (failure: ArtifactSourceException) {
            throw failure
        } catch (failure: Exception) {
            throw ArtifactSourceException(
                ArtifactSourceFailure.STAGING_FAILED,
                "Pinned microG artifact staging failed",
                failure,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun verifyReleaseManifest() {
        val pinned = PinnedMicrogRelease.manifest
        if (enforceProductionPin && !release.sameIdentityAs(pinned)) {
            throw ArtifactSourceException(
                ArtifactSourceFailure.MANIFEST_MISMATCH,
                "Artifact manifest does not match the reviewed production release",
            )
        }
        release.artifacts.forEach { manifest ->
            if (sha256(manifest.exposedCertificateDer) != manifest.exposedCertificateSha256) {
                throw ArtifactSourceException(
                    ArtifactSourceFailure.MANIFEST_MISMATCH,
                    "Exposed certificate DER does not match its pinned digest",
                )
            }
        }
    }

    private fun verifyArtifact(file: Path, manifest: ProductionMicrogManifest) {
        if (sha256(file) != manifest.apkSha256) {
            throw ArtifactSourceException(
                ArtifactSourceFailure.APK_DIGEST_MISMATCH,
                "Pinned microG APK digest mismatch",
            )
        }
        val inspected = inspector.inspect(file)
        if (inspected.signerSha256 != listOf(manifest.realSignerSha256)) {
            throw ArtifactSourceException(
                ArtifactSourceFailure.SIGNER_MISMATCH,
                "Pinned microG APK signer mismatch",
            )
        }
        if (inspected.packageName != manifest.packageName) {
            throw ArtifactSourceException(
                ArtifactSourceFailure.PACKAGE_MISMATCH,
                "Pinned microG APK package mismatch",
            )
        }
        if (
            inspected.versionCode != manifest.versionCode ||
            inspected.versionName != manifest.versionName
        ) {
            throw ArtifactSourceException(
                ArtifactSourceFailure.VERSION_MISMATCH,
                "Pinned microG APK version mismatch",
            )
        }
    }

    private fun staged(path: Path, manifest: ProductionMicrogManifest) = StagedMicrogArtifact(
        apkPath = path,
        manifest = manifest,
        byteCount = Files.size(path),
    )
}

private fun ProductionMicrogReleaseManifest.sameIdentityAs(
    other: ProductionMicrogReleaseManifest,
): Boolean =
    releaseId == other.releaseId &&
        versionName == other.versionName &&
        artifacts.size == other.artifacts.size &&
        artifacts.zip(other.artifacts).all { (left, right) -> left.sameIdentityAs(right) }

private fun ProductionMicrogManifest.sameIdentityAs(other: ProductionMicrogManifest): Boolean =
    kind == other.kind &&
        releaseId == other.releaseId &&
        releaseVersionName == other.releaseVersionName &&
        versionName == other.versionName &&
        packageName == other.packageName &&
        versionCode == other.versionCode &&
        apkFileName == other.apkFileName &&
        apkSha256 == other.apkSha256 &&
        realSignerSha256 == other.realSignerSha256 &&
        exposedCertificateDer.contentEquals(other.exposedCertificateDer) &&
        exposedCertificateSha256 == other.exposedCertificateSha256 &&
        splitApkSha256 == other.splitApkSha256

internal fun sha256(file: Path): String = FileChannel.open(file, StandardOpenOption.READ).use {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = java.nio.ByteBuffer.allocate(32 * 1024)
    while (it.read(buffer) >= 0) {
        buffer.flip()
        digest.update(buffer)
        buffer.clear()
    }
    digest.digest().toHex()
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
