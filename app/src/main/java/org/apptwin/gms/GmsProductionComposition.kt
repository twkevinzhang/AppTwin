package org.apptwin.gms

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.apptwin.gms.artifacts.GmsArtifactIdentity
import org.apptwin.gms.artifacts.GmsArtifactKind
import org.apptwin.gms.artifacts.GmsReleaseIdentity
import org.apptwin.gms.artifacts.TrustedGmsArtifact
import org.apptwin.gms.artifacts.TrustedGmsManifest
import org.apptwin.gms.ports.ActiveGmsReleasePort
import org.apptwin.gms.runtime.AndroidGmsRuntimeAdapter
import org.apptwin.gms.runtime.GmsArtifactStageProvider
import org.apptwin.gms.runtime.GmsGroupBindingResolver
import org.apptwin.gms.runtime.GmsOperationReceiptStore
import org.apptwin.gms.runtime.SharedPreferencesGmsOperationReceiptStore
import org.apptwin.gms.runtime.VirtualCoreGmsRuntimeGateway
import org.apptwin.groups.FileGroupStore
import org.apptwin.microg.artifact.ArtifactSourceException
import org.apptwin.microg.artifact.ArtifactSourceFailure
import org.apptwin.microg.artifact.MicrogArtifactSource
import org.apptwin.microg.artifact.PinnedMicrogArtifactProvider
import org.apptwin.microg.artifact.PinnedMicrogRelease
import org.apptwin.microg.artifact.ProductionMicrogArtifactKind
import org.apptwin.microg.artifact.ProductionMicrogManifest
import org.apptwin.microg.artifact.ProductionMicrogReleaseManifest

/** Copies the pinned APK from the runtimeProbe asset only when an enable operation first stages it. */
internal class AssetMicrogArtifactSource private constructor(
    private val sourceRoot: File,
    private val openAsset: (String) -> InputStream,
    private val directorySync: (File) -> Unit,
) : MicrogArtifactSource {
    constructor(context: Context) : this(
        sourceRoot = File(context.applicationContext.filesDir, "microg-artifact-source"),
        openAsset = { name -> context.applicationContext.assets.open(name) },
        directorySync = ::syncDirectory,
    )

    internal constructor(
        sourceRoot: File,
        openAsset: (String) -> InputStream,
        directorySync: (File) -> Unit,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit,
    ) : this(sourceRoot, openAsset, directorySync)

    @Synchronized
    override fun locate(manifest: ProductionMicrogManifest): java.nio.file.Path {
        val pinned = PinnedMicrogRelease.manifest.artifact(manifest.kind)
        require(manifest.samePinnedIdentity(pinned)) {
            "Only a pinned artifact from the complete reviewed microG release may be materialized"
        }
        ensureSourceRoot()
        val destination = File(sourceRoot, manifest.apkFileName)
        if (destination.isFile) return destination.toPath()
        val temporary = File(sourceRoot, ".${manifest.apkFileName}-${java.util.UUID.randomUUID()}.tmp")
        try {
            val input = try {
                openAsset("microg/${manifest.apkFileName}")
            } catch (error: Exception) {
                throw ArtifactSourceException(
                    ArtifactSourceFailure.MISSING,
                    "Pinned microG asset is unavailable in this build",
                    error,
                )
            }
            input.use { source ->
                FileOutputStream(temporary).use { output ->
                    source.copyTo(output, 32 * 1024)
                    output.fd.sync()
                }
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            directorySync(sourceRoot)
            return destination.toPath()
        } catch (failure: ArtifactSourceException) {
            throw failure
        } catch (error: Exception) {
            throw ArtifactSourceException(
                ArtifactSourceFailure.STAGING_FAILED,
                "Pinned microG asset materialization failed",
                error,
            )
        } finally {
            temporary.delete()
        }
    }

    private fun ensureSourceRoot() {
        if (sourceRoot.isDirectory) return
        val parent = sourceRoot.parentFile ?: error("microG source directory has no parent")
        check(parent.isDirectory) { "App private files directory is unavailable" }
        check(sourceRoot.mkdir()) { "Unable to create microG source directory" }
        directorySync(parent)
        directorySync(sourceRoot)
    }

    private companion object {
        fun syncDirectory(directory: File) {
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        }
    }
}

internal class PinnedActiveGmsReleasePort(
    private val hostSignerSha256: () -> String?,
) : ActiveGmsReleasePort {
    override fun current(): TrustedGmsManifest? {
        val hostSigner = hostSignerSha256() ?: return null
        if (!SHA256.matches(hostSigner)) return null
        val pinned = PinnedMicrogRelease.manifest
        val core = pinned.artifact(ProductionMicrogArtifactKind.GMS_CORE)
        val companion = pinned.artifact(ProductionMicrogArtifactKind.COMPANION_STORE)
        return TrustedGmsManifest(
            release = GmsReleaseIdentity(
                releaseId = pinned.releaseId,
                microGVersion = pinned.versionName,
                manifestSha256 = canonicalManifestSha256(pinned),
                manifestSignerSha256 = hostSigner,
            ),
            artifacts = listOf(
                TrustedGmsArtifact(
                    identity = GmsArtifactIdentity(
                        kind = GmsArtifactKind.GMS_CORE,
                        packageName = core.packageName,
                        versionCode = core.versionCode,
                        realSignerSha256 = core.realSignerSha256,
                        apkSha256 = core.apkSha256,
                    ),
                    exposedCompatibilitySignatureSha256 = core.exposedCertificateSha256,
                ),
                TrustedGmsArtifact(
                    identity = GmsArtifactIdentity(
                        kind = GmsArtifactKind.FAKE_STORE,
                        packageName = companion.packageName,
                        versionCode = companion.versionCode,
                        realSignerSha256 = companion.realSignerSha256,
                        apkSha256 = companion.apkSha256,
                    ),
                    exposedCompatibilitySignatureSha256 = companion.exposedCertificateSha256,
                ),
            ),
            issuedAtEpochMillis = OFFICIAL_RELEASE_EPOCH_MILLIS,
            expiresAtEpochMillis = null,
        )
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
        const val OFFICIAL_RELEASE_EPOCH_MILLIS = 1_777_027_763_000L
    }
}

internal fun AndroidGmsOperations.Companion.production(
    application: Application,
    groups: FileGroupStore,
    receipts: GmsOperationReceiptStore = SharedPreferencesGmsOperationReceiptStore(application),
): AndroidGmsOperations {
    val artifactProvider = PinnedMicrogArtifactProvider(
        source = AssetMicrogArtifactSource(application),
        stagingRoot = File(application.filesDir, "microg-artifact-staging").toPath(),
    )
    val runtime = AndroidGmsRuntimeAdapter(
        bindings = fileGroupBindingResolver(groups),
        artifacts = GmsArtifactStageProvider.pinned(artifactProvider),
        engine = VirtualCoreGmsRuntimeGateway(),
        receipts = receipts,
    )
    return AndroidGmsOperations(
        application = application,
        releases = PinnedActiveGmsReleasePort { uniqueHostSignerSha256(application) },
        runtime = runtime,
    )
}

internal fun fileGroupBindingResolver(groups: FileGroupStore) = GmsGroupBindingResolver { groupId ->
    // FileGroupStore is the only authority; unknown/deleted/corrupt groups never fall back to an
    // Android or host user. Real/default user 0 is never a valid AppTwin runtime binding.
    groups.find(groupId)?.environmentBinding?.internalId?.takeIf { it > 0 }
}

internal fun canonicalManifestSha256(manifest: ProductionMicrogReleaseManifest): String {
    val canonical = buildString {
        listOf(manifest.releaseId, manifest.versionName).forEach { field ->
            append(field.length).append(':').append(field).append('\n')
        }
        manifest.artifacts.sortedBy { it.kind.name }.forEach { artifact ->
            listOf(
                artifact.kind.name,
                artifact.releaseId,
                artifact.releaseVersionName,
                artifact.versionName,
                artifact.packageName,
                artifact.versionCode.toString(),
                artifact.apkFileName,
                artifact.apkSha256,
                artifact.realSignerSha256,
                artifact.exposedCertificateSha256,
            ).forEach { field -> append(field.length).append(':').append(field).append('\n') }
            artifact.splitApkSha256.toSortedMap().forEach { (name, digest) ->
                append(name.length).append(':').append(name)
                append(digest.length).append(':').append(digest).append('\n')
            }
        }
    }
    return sha256(canonical.toByteArray(StandardCharsets.UTF_8))
}

private fun ProductionMicrogManifest.samePinnedIdentity(other: ProductionMicrogManifest): Boolean =
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

@Suppress("DEPRECATION")
private fun uniqueHostSignerSha256(context: Context): String? = runCatching {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
    } else {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
    }
    val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.signingInfo?.apkContentsSigners.orEmpty()
    } else {
        packageInfo.signatures.orEmpty()
    }
    signers.singleOrNull()?.toByteArray()?.let(::sha256)
}.getOrNull()

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte) }
