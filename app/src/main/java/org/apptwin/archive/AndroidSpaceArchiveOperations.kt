package org.apptwin.archive

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Parcel
import com.lody.virtual.client.ipc.VPackageManager
import com.lody.virtual.client.hook.proxies.keystore.CustodianKeyspaceState
import com.lody.virtual.os.VEnvironment
import com.lody.virtual.remote.VDeviceInfo
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import org.apptwin.SpaceArchiveExportResult
import org.apptwin.SpaceArchiveImportResult
import org.apptwin.custodian.contract.CustodianContract
import org.apptwin.gms.AndroidGmsOperations
import org.apptwin.gms.model.GmsDesiredState
import org.apptwin.gms.usecases.GmsLifecycleResult
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.groups.GroupAppState
import org.apptwin.groups.GroupHealth
import org.apptwin.groups.GroupLifecycleCoordinator
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.runtime.AndroidCustodianKeyspaceRegistrar
import org.apptwin.runtime.VirtualRuntimeController

/** Coordinates a verified same-device Space archive transaction around the virtual runtime. */
internal class AndroidSpaceArchiveOperations(
    private val application: Application,
    private val groups: FileGroupStore,
    private val lifecycle: GroupLifecycleCoordinator,
    private val runtime: VirtualRuntimeController,
    private val revisions: AndroidPackageRevisionImporter,
    private val gms: AndroidGmsOperations,
    private val custodian: AndroidCustodianKeyspaceRegistrar =
        AndroidCustodianKeyspaceRegistrar(application),
) {
    fun exportSpace(
        groupId: String,
        destination: Uri,
        compression: SpaceArchiveCompression,
    ): SpaceArchiveExportResult {
        val group = requireNotNull(groups.find(groupId)) { "找不到這個空間" }
        require(group.health == GroupHealth.HEALTHY) { "空間目前無法封存" }
        require(group.apps.any { it.packageName == CustodianContract.LINE_PACKAGE }) {
            "目前只有包含 LINE 的 Custodian Space 可封存"
        }
        val binding = requireNotNull(group.environmentBinding) { "空間環境不存在" }
        val appManifests = group.apps.map { app ->
            val active = requireNotNull(revisions.active(app.packageName)) {
                "${app.packageName} 沒有可封存的 active revision"
            }
            SpaceArchiveApp(
                packageName = app.packageName,
                addedAt = app.addedAtEpochMillis,
                state = SpaceArchiveAppState.valueOf(app.state.name),
                revisionId = active.revisionId,
            )
        }
        val markerExisted = CustodianKeyspaceState.readRetainedForUser(binding.internalId) != null
        val keyspaceId = custodian.retainForArchive(group, CustodianContract.LINE_PACKAGE)
        val archiveId = UUID.randomUUID().toString()
        val generatedRoot = File(application.cacheDir, "space-archive-export/$archiveId")
        try {
            runtime.quiesceEnvironment(binding)
            writeRuntimeMetadata(generatedRoot, group)
            val manifest = SpaceArchiveManifest(
                archiveId = archiveId,
                sourceSpaceId = group.id,
                name = group.name,
                createdAt = System.currentTimeMillis(),
                apps = appManifests,
                gmsEnabled = gms.snapshot(group.id).profile.desiredState == GmsDesiredState.ENABLED,
                custodianKeyspaceId = keyspaceId,
            )
            val sources = archiveSources(binding.internalId, generatedRoot)
            val digest = MessageDigest.getInstance("SHA-256")
            var bytesWritten = 0L
            val descriptor = requireNotNull(
                application.contentResolver.openFileDescriptor(destination, "rwt"),
            ) { "無法開啟選定的存檔位置" }
            descriptor.use { parcel ->
                FileOutputStream(parcel.fileDescriptor).use { fileOutput ->
                    val counting = CountingOutputStream(DigestOutputStream(fileOutput, digest))
                    SpaceArchiveWriter.write(counting, manifest, sources, compression)
                    counting.flush()
                    fileOutput.fd.sync()
                    bytesWritten = counting.count
                }
            }
            val archiveSha256 = digest.digest().toHex()
            custodian.sealArchive(
                group.id,
                archiveId,
                CustodianContract.LINE_PACKAGE,
                keyspaceId,
                archiveSha256,
            )
            return SpaceArchiveExportResult(group.name, bytesWritten)
        } catch (failure: Throwable) {
            runCatching { application.contentResolver.delete(destination, null, null) }
            if (!markerExisted) runCatching { custodian.cancelArchiveRetention(group) }
            throw failure
        } finally {
            generatedRoot.deleteRecursively()
        }
    }

    fun importSpace(source: Uri): SpaceArchiveImportResult {
        val operationId = UUID.randomUUID().toString()
        val operationRoot = File(application.cacheDir, "space-archive-import/$operationId")
        val archiveFile = File(operationRoot, "source.apptwin-space")
        val stagingRoot = File(operationRoot, "staging")
        check(operationRoot.mkdirs()) { "無法建立匯入 staging" }
        var created: Group? = null
        try {
            val archiveSha256 = copyArchiveAndDigest(source, archiveFile)
            val archive = SpaceArchiveReader().readAndExtract(archiveFile, stagingRoot)
            val manifest = archive.manifest
            require(manifest.apps.any { it.packageName == CustodianContract.LINE_PACKAGE }) {
                "存檔不包含受 Custodian 保護的 LINE"
            }
            manifest.apps.forEach { app ->
                val active = requireNotNull(revisions.active(app.packageName)) {
                    "裝置缺少 ${app.packageName} 的 active revision"
                }
                require(active.revisionId == app.revisionId) {
                    "${app.packageName} revision 與存檔不一致"
                }
            }
            val currentOwner = requireNotNull(
                custodian.resolveArchivedOwner(
                    manifest.archiveId,
                    CustodianContract.LINE_PACKAGE,
                    manifest.custodianKeyspaceId,
                    archiveSha256,
                ),
            ) { "Custodian 無法驗證此存檔或存檔內容已變更" }
            require(groups.find(currentOwner) == null) {
                "此存檔的 LINE 身分仍由現有空間使用；請先移除原空間"
            }

            val destination = lifecycle.createGroup(manifest.name)
            created = destination
            custodian.claimArchive(
                manifest.archiveId,
                currentOwner,
                destination,
                CustodianContract.LINE_PACKAGE,
                manifest.custodianKeyspaceId,
                archiveSha256,
            )
            manifest.apps.forEach { archived ->
                groups.addApp(destination.id, archived.packageName, archived.addedAt)
                groups.updateAppState(
                    destination.id,
                    archived.packageName,
                    GroupAppState.valueOf(archived.state.name),
                )
            }
            var hydrated = requireNotNull(groups.find(destination.id))
            if (manifest.gmsEnabled) {
                gms.grantNetworkConsent(hydrated.id)
                checkGmsEnabled(gms.enable(hydrated.id))
            }
            hydrated = requireNotNull(groups.find(destination.id))
            hydrated.apps.forEach { app -> runtime.preparePackageForRestore(hydrated, app) }
            val binding = requireNotNull(hydrated.environmentBinding)
            runtime.quiesceEnvironment(binding)
            restoreArchiveTrees(archive, binding.internalId)
            restoreRuntimeMetadata(archive, hydrated)
            CustodianKeyspaceState.writeForUser(
                binding.internalId,
                hydrated.id,
                manifest.custodianKeyspaceId,
            )
            CustodianKeyspaceState.writeRetainedForUser(
                binding.internalId,
                CustodianContract.LINE_PACKAGE,
                manifest.custodianKeyspaceId,
            )
            return SpaceArchiveImportResult(requireNotNull(groups.find(hydrated.id)))
        } catch (failure: Throwable) {
            created?.let { group ->
                runCatching { lifecycle.deleteGroup(group.id) }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }
            throw failure
        } finally {
            operationRoot.deleteRecursively()
        }
    }

    private fun archiveSources(userId: Int, generatedRoot: File): List<SpaceArchiveSource> {
        val credential = VEnvironment.getUserSystemDirectory(userId).also(::ensureDirectory)
        val device = VEnvironment.getDeUserSystemDirectory(userId).also(::ensureDirectory)
        val shared = VEnvironment.getVirtualStorageDir(CustodianContract.LINE_PACKAGE, userId)
            ?.also(::ensureDirectory)
        val private = VEnvironment.getVirtualPrivateStorageDir(userId)?.also(::ensureDirectory)
        return buildList {
            add(SpaceArchiveSource("credential", credential))
            add(SpaceArchiveSource("device", device))
            shared?.let { add(SpaceArchiveSource("external-shared", it)) }
            private?.let { add(SpaceArchiveSource("external-private", it)) }
            add(SpaceArchiveSource("runtime", generatedRoot))
        }
    }

    private fun writeRuntimeMetadata(root: File, group: Group) {
        ensureDirectory(root)
        val binding = requireNotNull(group.environmentBinding)
        val parcel = Parcel.obtain()
        try {
            runtime.readDeviceInfo(binding).writeToParcel(parcel, 0)
            FileOutputStream(File(root, DEVICE_INFO_FILE)).use { output ->
                output.write(parcel.marshall())
                output.fd.sync()
            }
        } finally {
            parcel.recycle()
        }
        val permissions = Properties().apply {
            group.apps.forEachIndexed { index, app ->
                setProperty("$index.packageName", app.packageName)
                setProperty(
                    "$index.camera",
                    permissionGranted(Manifest.permission.CAMERA, app.packageName, binding.internalId)
                        .toString(),
                )
                setProperty(
                    "$index.microphone",
                    permissionGranted(
                        Manifest.permission.RECORD_AUDIO,
                        app.packageName,
                        binding.internalId,
                    ).toString(),
                )
            }
            setProperty("count", group.apps.size.toString())
        }
        FileOutputStream(File(root, PERMISSIONS_FILE)).use { output ->
            permissions.store(output, "AppTwin Space archive runtime permissions")
            output.fd.sync()
        }
    }

    private fun restoreRuntimeMetadata(archive: SpaceArchiveReadResult, group: Group) {
        val binding = requireNotNull(group.environmentBinding)
        val runtimeRoot = File(archive.stagingRoot, "payload/runtime")
        val deviceBytes = File(runtimeRoot, DEVICE_INFO_FILE).readBytes()
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(deviceBytes, 0, deviceBytes.size)
            parcel.setDataPosition(0)
            runtime.restoreDeviceInfo(binding, VDeviceInfo.CREATOR.createFromParcel(parcel))
        } finally {
            parcel.recycle()
        }
        val permissions = Properties().apply {
            FileInputStream(File(runtimeRoot, PERMISSIONS_FILE)).use(::load)
        }
        val count = permissions.getProperty("count")?.toIntOrNull()
            ?: error("存檔權限資料損毀")
        repeat(count) { index ->
            val packageName = requireNotNull(permissions.getProperty("$index.packageName"))
            require(group.contains(packageName)) { "存檔權限套件不屬於新空間" }
            setPermission(
                Manifest.permission.CAMERA,
                packageName,
                binding.internalId,
                permissions.getProperty("$index.camera").toBooleanStrict(),
            )
            setPermission(
                Manifest.permission.RECORD_AUDIO,
                packageName,
                binding.internalId,
                permissions.getProperty("$index.microphone").toBooleanStrict(),
            )
        }
    }

    private fun restoreArchiveTrees(archive: SpaceArchiveReadResult, userId: Int) {
        replaceDirectory(
            File(archive.stagingRoot, "payload/credential"),
            VEnvironment.getUserSystemDirectory(userId),
        )
        replaceDirectory(
            File(archive.stagingRoot, "payload/device"),
            VEnvironment.getDeUserSystemDirectory(userId),
        )
        VEnvironment.getVirtualStorageDir(CustodianContract.LINE_PACKAGE, userId)?.let { target ->
            replaceDirectory(File(archive.stagingRoot, "payload/external-shared"), target)
        }
        VEnvironment.getVirtualPrivateStorageDir(userId)?.let { target ->
            replaceDirectory(File(archive.stagingRoot, "payload/external-private"), target)
        }
    }

    private fun replaceDirectory(source: File, destination: File) {
        if (destination.exists()) check(destination.deleteRecursively()) {
            "無法清除還原目標：${destination.name}"
        }
        ensureDirectory(destination)
        if (!source.isDirectory) return
        source.walkTopDown().forEach { input ->
            val relative = input.relativeTo(source).path
            if (relative.isEmpty()) return@forEach
            val output = File(destination, relative)
            if (input.isDirectory) {
                ensureDirectory(output)
            } else {
                ensureDirectory(requireNotNull(output.parentFile))
                BufferedInputStream(FileInputStream(input)).use { sourceStream ->
                    FileOutputStream(output).use { rawOutput ->
                        val targetStream = BufferedOutputStream(rawOutput)
                        sourceStream.copyTo(targetStream, COPY_BUFFER_SIZE)
                        targetStream.flush()
                        rawOutput.fd.sync()
                    }
                }
                check(output.setReadable(true, true) && output.setWritable(true, true)) {
                    "無法設定還原檔案權限"
                }
            }
        }
    }

    private fun copyArchiveAndDigest(source: Uri, destination: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = requireNotNull(application.contentResolver.openInputStream(source)) {
            "無法讀取選定的存檔"
        }
        var total = 0L
        input.use { rawInput ->
            BufferedInputStream(rawInput).use { buffered ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val count = buffered.read(buffer)
                        if (count < 0) break
                        total = Math.addExact(total, count.toLong())
                        require(total <= MAX_ARCHIVE_BYTES) { "存檔超過 8 GB 上限" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
        }
        require(total > 0) { "存檔是空檔案" }
        return digest.digest().toHex()
    }

    private fun permissionGranted(permission: String, packageName: String, userId: Int): Boolean =
        VPackageManager.get().checkPermission(permission, packageName, userId) ==
            PackageManager.PERMISSION_GRANTED

    private fun setPermission(
        permission: String,
        packageName: String,
        userId: Int,
        granted: Boolean,
    ) {
        check(
            VPackageManager.get().setRuntimePermissionGranted(
                permission,
                packageName,
                userId,
                granted,
            ),
        ) { "無法還原 $packageName 權限" }
    }

    private fun checkGmsEnabled(result: GmsLifecycleResult) {
        check(
            result is GmsLifecycleResult.Completed ||
                result is GmsLifecycleResult.AlreadySatisfied,
        ) { "無法重建存檔的 Google 服務環境：${result.javaClass.simpleName}" }
    }

    private fun ensureDirectory(directory: File) {
        check(directory.isDirectory || directory.mkdirs()) { "無法建立 ${directory.name} 目錄" }
        directory.setReadable(true, true)
        directory.setWritable(true, true)
        directory.setExecutable(true, true)
    }

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var count: Long = 0
            private set

        override fun write(value: Int) {
            out.write(value)
            count = Math.addExact(count, 1)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out.write(buffer, offset, length)
            count = Math.addExact(count, length.toLong())
        }
    }

    private companion object {
        const val DEVICE_INFO_FILE = "device-info.bin"
        const val PERMISSIONS_FILE = "permissions.properties"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val MAX_ARCHIVE_BYTES = 8L * 1024L * 1024L * 1024L
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
