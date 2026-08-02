package org.maskaccounts.revision

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
import org.maskaccounts.packagesource.PackageArtifact
import org.maskaccounts.packagesource.PackageSourceSnapshot
import org.maskaccounts.packagesource.SplitArtifact

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
 * revision. Instance data is deliberately outside this directory and is never touched here.
 */
class AndroidPackageRevisionImporter(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val revisionsRoot = File(appContext.filesDir, "package-revisions")

    fun listCloneableApps(): List<InstalledAppEntry> = installedApplications()
        .asSequence()
        .filterNot { it.packageName == appContext.packageName }
        .filter { it.enabled && it.sourceDir != null }
        .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
        .mapNotNull { applicationInfo ->
            runCatching {
                val info = packageInfo(applicationInfo.packageName)
                InstalledAppEntry(
                    label = packageManager.getApplicationLabel(applicationInfo).toString(),
                    packageName = applicationInfo.packageName,
                    versionName = info.versionName.orEmpty(),
                    versionCode = info.longVersionCodeCompat(),
                )
            }.getOrNull()
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, InstalledAppEntry::label))
        .toList()

    fun active(packageName: String): ActiveRevisionSummary? {
        val packageRoot = File(revisionsRoot, packageName)
        if (!packageRoot.isDirectory) return null
        val pointer = File(packageRoot, ACTIVE_POINTER)
        if (!pointer.isFile) return null
        val revisionId = pointer.readText().trim()
        if (revisionId.isEmpty()) return null
        val metadata = readProperties(File(packageRoot, "$revisionId/$METADATA")) ?: return null
        return ActiveRevisionSummary(
            packageName = metadata.getProperty("packageName") ?: return null,
            versionCode = metadata.getProperty("versionCode")?.toLongOrNull() ?: return null,
            revisionId = revisionId,
        )
    }

    fun sync(packageName: String): RevisionImportResult {
        val before = runCatching { captureSource(packageName) }
            .getOrElse { return RevisionImportResult.Failed(it.safeMessage()) }
        val current = active(packageName)
        if (current != null && before.versionCode < current.versionCode) {
            return RevisionImportResult.Rejected(
                "拒絕降版：來源 ${before.versionCode}，目前 ${current.versionCode}",
            )
        }
        val currentMetadata = activeMetadata(packageName)
        if (current != null && currentMetadata?.matches(before) == true) {
            return RevisionImportResult.AlreadyCurrent(current)
        }
        val previousSigner = currentMetadata?.getProperty("currentSigner")
        if (previousSigner != null && previousSigner !in before.signerLineage) {
            return RevisionImportResult.Rejected("來源 App 簽章 lineage 與現有 revision 不相容")
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

            if (current?.revisionId == revisionId) {
                staging.deleteRecursively()
                return RevisionImportResult.AlreadyCurrent(current)
            }

            writeMetadata(staging, revisionId, before, snapshot)
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
            setProperty("supportedAbis", snapshot.supportedAbis.sorted().joinToString(","))
        }
        FileOutputStream(File(staging, METADATA)).use { output ->
            properties.store(output, "MaskAccounts immutable package revision")
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

    private fun markReadOnlyRecursively(directory: File) {
        directory.walkTopDown()
            .filter(File::isFile)
            .forEach { file -> check(file.setReadOnly()) { "無法將 ${file.name} 設為唯讀" } }
    }

    private fun activeMetadata(packageName: String): Properties? {
        val active = active(packageName) ?: return null
        return readProperties(File(packageRoot(packageName), "${active.revisionId}/$METADATA"))
    }

    private fun readProperties(file: File): Properties? {
        if (!file.isFile) return null
        return runCatching {
            Properties().apply { FileInputStream(file).use(::load) }
        }.getOrNull()
    }

    private fun Properties.matches(source: SourcePackage): Boolean =
        getProperty("versionCode")?.toLongOrNull() == source.versionCode &&
            getProperty("lastUpdateTime")?.toLongOrNull() == source.lastUpdateTime &&
            getProperty("currentSigner") == source.signerLineage.last() &&
            getProperty("splitNames") == source.splits.map(SourceSplit::name).sorted().joinToString(",")

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
