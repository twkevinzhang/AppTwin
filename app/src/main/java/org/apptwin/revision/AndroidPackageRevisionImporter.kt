package org.apptwin.revision

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipFile
import org.apptwin.packagesource.PackageArtifact
import org.apptwin.packagesource.PackageSourceSnapshot
import org.apptwin.packagesource.SplitArtifact
import org.apptwin.revisionstore.RejectionReason
import org.apptwin.revisionstore.RevisionTransitionPolicy

data class InstalledAppEntry(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
)

data class ActiveRevisionSummary(
    val packageName: String,
    val versionCode: Long,
    val revisionId: String,
)

internal fun isCloneableAppEligible(
    hostPackageName: String,
    candidatePackageName: String,
    packageExists: Boolean,
    enabled: Boolean,
    sourceReadable: () -> Boolean,
    hasLauncherActivity: () -> Boolean,
): Boolean = packageExists &&
    candidatePackageName != hostPackageName &&
    enabled &&
    sourceReadable() &&
    hasLauncherActivity()

sealed interface RevisionImportResult {
    data class Activated(
        val summary: ActiveRevisionSummary,
        val artifactCount: Int,
        val bytesCopied: Long,
    ) : RevisionImportResult

    data class AlreadyCurrent(val summary: ActiveRevisionSummary) : RevisionImportResult
    data class Rejected(val reason: String) : RevisionImportResult
    data class Failed(val reason: String) : RevisionImportResult
}

/**
 * Imports an installed package into a host-private immutable revision directory.
 *
 * The source is queried both before and after copying. A Play update that replaces any base/split
 * path while a copy is running invalidates the staging directory instead of producing a mixed
 * revision. GroupApp data is deliberately outside this directory and is never touched here.
 */
class AndroidPackageRevisionImporter(context: Context) : ActiveRuntimeRevisionProvider {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val revisionsRoot = File(appContext.filesDir, "package-revisions")
    private val activeLookup = FileActiveRevisionLookup(revisionsRoot)

    fun listCloneableApps(): List<InstalledAppEntry> = installedApplications()
        .asSequence()
        .mapNotNull(::cloneableAppEntry)
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, InstalledAppEntry::label))
        .toList()

    /**
     * Resolves one installed package without enumerating every application on the device.
     *
     * This uses the same eligibility checks as [listCloneableApps], so callers on the launch path
     * do not trade correctness for avoiding the device-wide package scan.
     */
    fun findCloneableApp(packageName: String): InstalledAppEntry? {
        if (packageName == appContext.packageName) return null
        val applicationInfo = try {
            applicationInfo(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        return cloneableAppEntry(applicationInfo)
    }

    /** Cheap, read-only source gate for launch. A miss falls back to durable [sync]. */
    fun isSourceCurrent(packageName: String): Boolean {
        if (findCloneableApp(packageName) == null) return false
        val source = runCatching { captureSource(packageName) }.getOrNull() ?: return false
        val active = activeLookup.read(packageName) as? ActiveRevisionLookupResult.Found
            ?: return false
        return active.metadata.matches(source)
    }

    fun active(packageName: String): ActiveRevisionSummary? = when (
        val result = activeLookup.read(packageName)
    ) {
        ActiveRevisionLookupResult.Absent -> null
        is ActiveRevisionLookupResult.Found -> result.summary
        is ActiveRevisionLookupResult.Corrupt ->
            throw ActiveRevisionMetadataException(result.reason)
    }

    /** Returns the immutable base+split directory currently selected for runtime loading. */
    fun activeRevisionDirectory(packageName: String): File? {
        return when (val result = activeLookup.read(packageName)) {
            ActiveRevisionLookupResult.Absent -> null
            is ActiveRevisionLookupResult.Found -> result.directory
            is ActiveRevisionLookupResult.Corrupt ->
                throw ActiveRevisionMetadataException(result.reason)
        }
    }

    override fun activeRuntimeRevision(packageName: String): ActiveRuntimeRevision? {
        val active = when (val result = activeLookup.read(packageName)) {
            ActiveRevisionLookupResult.Absent -> return null
            is ActiveRevisionLookupResult.Found -> result
            is ActiveRevisionLookupResult.Corrupt ->
                throw ActiveRevisionMetadataException(result.reason)
        }
        val metadata = active.metadata
        val splitNames = metadata.getProperty("splitNames")
            .orEmpty()
            .split(',')
            .filter(String::isNotBlank)
            .sorted()
        val splitDigests = splitNames.mapIndexed { index, splitName ->
            val storedName = metadata.getProperty("split.$index.name")
                ?: throw ActiveRevisionMetadataException("active split name is missing")
            if (storedName != splitName) {
                throw ActiveRevisionMetadataException("active split order is inconsistent")
            }
            splitName to (metadata.getProperty("split.$index.sha256")
                ?: throw ActiveRevisionMetadataException("active split digest is missing"))
        }.toMap()
        return ActiveRuntimeRevision(
            packageName = active.summary.packageName,
            revisionId = active.summary.revisionId,
            directory = active.directory,
            artifactIdentity = PackageArtifactIdentity(
                baseSha256 = metadata.getProperty("baseSha256")
                    ?: throw ActiveRevisionMetadataException("active base digest is missing"),
                splitSha256ByName = splitDigests,
            ),
        )
    }

    fun sync(packageName: String): RevisionImportResult {
        val before = runCatching { captureSource(packageName) }
            .getOrElse { return RevisionImportResult.Failed(it.safeMessage()) }
        val activeResult = activeLookup.read(packageName)
        if (activeResult is ActiveRevisionLookupResult.Corrupt) {
            return RevisionImportResult.Failed(
                "active revision 資料損毀，已保留原始資料：${activeResult.reason}",
            )
        }
        val current = (activeResult as? ActiveRevisionLookupResult.Found)?.summary
        val currentMetadata = (activeResult as? ActiveRevisionLookupResult.Found)?.metadata
        val rejection = RevisionTransitionPolicy.rejectionReason(
            currentVersionCode = current?.versionCode,
            currentSignerSha256 = currentMetadata?.getProperty("currentSigner"),
            candidateVersionCode = before.versionCode,
            candidateSigningLineageSha256 = before.signerLineage,
        )
        if (rejection != null) {
            return RevisionImportResult.Rejected(
                when (rejection) {
                    RejectionReason.VERSION_ROLLBACK ->
                        "拒絕降版：來源 ${before.versionCode}，目前 ${current?.versionCode}"

                    RejectionReason.INCOMPATIBLE_SIGNING_LINEAGE ->
                        "來源 App 簽章 lineage 與現有 revision 不相容"
                },
            )
        }
        if (current != null && currentMetadata?.matches(before) == true) {
            return RevisionImportResult.AlreadyCurrent(current)
        }

        val packageRoot = packageRoot(packageName)
        val staging = File(packageRoot, ".staging-${UUID.randomUUID()}")
        if (!staging.mkdirs()) {
            return RevisionImportResult.Failed("無法建立 staging 目錄")
        }

        return try {
            val copied = copyArtifacts(before, staging)
            val after = captureSource(packageName)
            check(before.identity == after.identity) {
                "來源 App 在同步途中被更新，請重試"
            }

            val snapshot = PackageSourceSnapshot(
                packageName = packageName,
                versionCode = before.versionCode,
                signingCertificateLineageSha256 = before.signerLineage,
                baseApk = copied.base,
                requiredSplitNames = before.splits.map(SourceSplit::name).toSet(),
                splitApks = copied.splits,
                supportedAbis = discoverAbis(copied.allFiles),
            )
            val revisionId = revisionId(snapshot, before.lastUpdateTime)
            writeMetadata(staging, revisionId, before, snapshot)

            if (current?.revisionId == revisionId) {
                replaceVerifiedMetadata(
                    activeDirectory = File(packageRoot, revisionId),
                    verifiedMetadata = File(staging, METADATA),
                )
                staging.deleteRecursively()
                return RevisionImportResult.AlreadyCurrent(current)
            }

            markReadOnlyRecursively(staging)

            val destination = File(packageRoot, revisionId)
            if (destination.exists()) {
                staging.deleteRecursively()
            } else {
                check(staging.renameTo(destination)) { "無法原子啟用 revision 目錄" }
            }
            writeActivePointer(packageRoot, revisionId)

            RevisionImportResult.Activated(
                summary = ActiveRevisionSummary(packageName, before.versionCode, revisionId),
                artifactCount = copied.allFiles.size,
                bytesCopied = snapshot.baseApk.sizeBytes +
                    snapshot.splitApks.sumOf { it.artifact.sizeBytes },
            )
        } catch (error: Throwable) {
            staging.setWritable(true, true)
            staging.walkBottomUp().forEach { it.setWritable(true, true) }
            staging.deleteRecursively()
            RevisionImportResult.Failed(error.safeMessage())
        }
    }

    private fun installedApplications(): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

    private fun applicationInfo(packageName: String): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }

    private fun cloneableAppEntry(applicationInfo: ApplicationInfo): InstalledAppEntry? {
        val source = applicationInfo.sourceDir?.let(::File)
        if (!isCloneableAppEligible(
                hostPackageName = appContext.packageName,
                candidatePackageName = applicationInfo.packageName,
                packageExists = true,
                enabled = applicationInfo.enabled,
                sourceReadable = { source?.isFile == true && source.canRead() },
                hasLauncherActivity = {
                    packageManager.getLaunchIntentForPackage(applicationInfo.packageName) != null
                },
            )
        ) {
            return null
        }
        return runCatching {
            val info = packageInfo(applicationInfo.packageName)
            InstalledAppEntry(
                label = packageManager.getApplicationLabel(applicationInfo).toString(),
                packageName = applicationInfo.packageName,
                versionName = info.versionName.orEmpty(),
                versionCode = info.longVersionCodeCompat(),
            )
        }.getOrNull()
    }

    private fun captureSource(packageName: String): SourcePackage {
        val info = packageInfo(packageName)
        val applicationInfo = requireNotNull(info.applicationInfo) { "找不到來源 ApplicationInfo" }
        val base = File(requireNotNull(applicationInfo.sourceDir) { "來源 App 沒有 base APK" })
        require(base.isFile && base.canRead()) { "base APK 無法讀取" }

        val splitPaths = applicationInfo.splitSourceDirs.orEmpty()
        val splitNames = applicationInfo.splitNames.orEmpty()
        require(splitPaths.size == splitNames.size) { "split name/path 數量不一致" }
        val splits = splitNames.indices.map { index ->
            SourceSplit(splitNames[index], File(splitPaths[index])).also {
                require(it.file.isFile && it.file.canRead()) { "split APK 無法讀取：${it.name}" }
            }
        }

        val lineage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = requireNotNull(info.signingInfo) { "來源 App 缺少簽章資訊" }
            require(!signingInfo.hasMultipleSigners()) { "M0 尚不支援 multi-signer 套件" }
            signingInfo.signingCertificateHistory
                .orEmpty()
                .map { signature -> sha256(signature.toByteArray()) }
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty().map { signature -> sha256(signature.toByteArray()) }
        }
        require(lineage.isNotEmpty()) { "來源 App 沒有可驗證的簽章 lineage" }

        return SourcePackage(
            packageName = packageName,
            versionCode = info.longVersionCodeCompat(),
            lastUpdateTime = info.lastUpdateTime,
            signerLineage = lineage,
            base = base,
            splits = splits,
        )
    }

    private fun packageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(
                packageName,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    PackageManager.GET_SIGNATURES
                },
            )
        }

    private fun copyArtifacts(source: SourcePackage, staging: File): CopiedArtifacts {
        val baseFile = File(staging, "base.apk")
        val base = copyAndDigest(source.base, baseFile)
        val splitArtifacts = source.splits.mapIndexed { index, split ->
            val destination = File(staging, "split-${index.toString().padStart(3, '0')}.apk")
            SplitArtifact(split.name, copyAndDigest(split.file, destination))
        }
        return CopiedArtifacts(base, splitArtifacts, listOf(baseFile) + splitArtifacts.map { File(it.artifact.path) })
    }

    private fun copyAndDigest(source: File, destination: File): PackageArtifact {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        BufferedInputStream(FileInputStream(source)).use { input ->
            FileOutputStream(destination).use { rawOutput ->
                BufferedOutputStream(rawOutput).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        size += count
                    }
                    output.flush()
                    rawOutput.fd.sync()
                }
            }
        }
        require(size > 0) { "APK 是空檔案：${source.name}" }
        return PackageArtifact(destination.absolutePath, size, digest.digest().toHex())
    }

    private fun discoverAbis(apks: List<File>): Set<String> = buildSet {
        apks.forEach { apk ->
            ZipFile(apk).use { zip ->
                zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith("lib/") && it.endsWith(".so") }
                    .mapNotNull { it.split('/').getOrNull(1) }
                    .filter(String::isNotBlank)
                    .forEach(::add)
            }
        }
    }

    private fun writeMetadata(
        staging: File,
        revisionId: String,
        source: SourcePackage,
        snapshot: PackageSourceSnapshot,
    ) {
        val properties = Properties().apply {
            setProperty("revisionId", revisionId)
            setProperty("packageName", source.packageName)
            setProperty("versionCode", source.versionCode.toString())
            setProperty("lastUpdateTime", source.lastUpdateTime.toString())
            setProperty("baseSourcePath", source.base.absolutePath)
            setProperty("baseSourceSize", source.base.length().toString())
            setProperty("baseSourceMtime", source.base.lastModified().toString())
            setProperty(
                "splitSourcePaths",
                source.splits.joinToString(",") { "${it.name}=${it.file.absolutePath}" },
            )
            setProperty("signerLineage", source.signerLineage.joinToString(","))
            setProperty("currentSigner", source.signerLineage.last())
            setProperty("baseSha256", snapshot.baseApk.sha256)
            setProperty("baseSize", snapshot.baseApk.sizeBytes.toString())
            setProperty("splitNames", snapshot.requiredSplitNames.sorted().joinToString(","))
            snapshot.splitApks.sortedBy(SplitArtifact::splitName).forEachIndexed { index, split ->
                setProperty("split.$index.name", split.splitName)
                setProperty("split.$index.sha256", split.artifact.sha256)
                setProperty("split.$index.size", split.artifact.sizeBytes.toString())
            }
            source.splits.sortedBy(SourceSplit::name).forEachIndexed { index, split ->
                setProperty("split.$index.sourcePath", split.file.absolutePath)
                setProperty("split.$index.sourceSize", split.file.length().toString())
                setProperty("split.$index.sourceMtime", split.file.lastModified().toString())
            }
            setProperty("supportedAbis", snapshot.supportedAbis.sorted().joinToString(","))
        }
        FileOutputStream(File(staging, METADATA)).use { output ->
            properties.store(output, "AppTwin immutable package revision")
            output.fd.sync()
        }
    }

    private fun writeActivePointer(packageRoot: File, revisionId: String) {
        val pending = File(packageRoot, "$ACTIVE_POINTER.next")
        FileOutputStream(pending).use { output ->
            output.write("$revisionId\n".toByteArray())
            output.fd.sync()
        }
        Files.move(
            pending.toPath(),
            File(packageRoot, ACTIVE_POINTER).toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    /**
     * A legacy revision can be content-identical while lacking the cheap source stat marker.
     * Only a just-completed full copy+digest is allowed to upgrade that metadata in place.
     */
    private fun replaceVerifiedMetadata(activeDirectory: File, verifiedMetadata: File) {
        val currentMetadata = File(activeDirectory, METADATA)
        require(activeDirectory.isDirectory && currentMetadata.isFile) {
            "active revision metadata is missing during verified upgrade"
        }
        check(verifiedMetadata.setReadOnly()) {
            "無法將 verified revision metadata 設為唯讀"
        }
        Files.move(
            verifiedMetadata.toPath(),
            currentMetadata.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun markReadOnlyRecursively(directory: File) {
        directory.walkTopDown()
            .filter(File::isFile)
            .forEach { file -> check(file.setReadOnly()) { "無法將 ${file.name} 設為唯讀" } }
    }

    private fun Properties.matches(source: SourcePackage): Boolean =
        getProperty("versionCode")?.toLongOrNull() == source.versionCode &&
            getProperty("lastUpdateTime")?.toLongOrNull() == source.lastUpdateTime &&
            getProperty("currentSigner") == source.signerLineage.last() &&
            getProperty("baseSourcePath") == source.base.absolutePath &&
            getProperty("baseSourceSize")?.toLongOrNull() == source.base.length() &&
            getProperty("baseSourceMtime")?.toLongOrNull() == source.base.lastModified() &&
            sourceStatsMatch(source.splits)

    private fun Properties.sourceStatsMatch(splits: List<SourceSplit>): Boolean {
        val sorted = splits.sortedBy(SourceSplit::name)
        if (getProperty("splitNames") != sorted.joinToString(",") { it.name }) return false
        return sorted.withIndex().all { (index, split) ->
            getProperty("split.$index.name") == split.name &&
                getProperty("split.$index.sourcePath") == split.file.absolutePath &&
                getProperty("split.$index.sourceSize")?.toLongOrNull() == split.file.length() &&
                getProperty("split.$index.sourceMtime")?.toLongOrNull() == split.file.lastModified()
        }
    }

    private fun revisionId(snapshot: PackageSourceSnapshot, lastUpdateTime: Long): String {
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(snapshot.baseApk.sha256.toByteArray())
            snapshot.splitApks.sortedBy(SplitArtifact::splitName).forEach { split ->
                update(split.splitName.toByteArray())
                update(split.artifact.sha256.toByteArray())
            }
        }.digest().toHex().take(16)
        return "${snapshot.versionCode}-$lastUpdateTime-$digest"
    }

    private fun packageRoot(packageName: String): File = File(revisionsRoot, packageName).also {
        check(it.exists() || it.mkdirs()) { "無法建立 package revision 目錄" }
    }

    private data class SourceSplit(val name: String, val file: File)

    private data class SourceIdentity(
        val versionCode: Long,
        val lastUpdateTime: Long,
        val signerLineage: List<String>,
        val paths: List<String>,
    )

    private data class SourcePackage(
        val packageName: String,
        val versionCode: Long,
        val lastUpdateTime: Long,
        val signerLineage: List<String>,
        val base: File,
        val splits: List<SourceSplit>,
    ) {
        val identity = SourceIdentity(
            versionCode,
            lastUpdateTime,
            signerLineage,
            listOf(base.absolutePath) + splits.map { it.file.absolutePath },
        )
    }

    private data class CopiedArtifacts(
        val base: PackageArtifact,
        val splits: List<SplitArtifact>,
        val allFiles: List<File>,
    )

    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun Throwable.safeMessage(): String = message ?: javaClass.simpleName

    private companion object {
        const val ACTIVE_POINTER = "active"
        const val METADATA = "metadata.properties"
    }
}
