package org.apptwin.archive

import android.app.Application
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Parcel
import com.lody.virtual.client.ipc.VAccountManager
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
import org.apptwin.CreateSpaceFromBackupResult
import org.apptwin.custodian.contract.CustodianContract
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.groups.GroupApp
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
    private val custodian: AndroidCustodianKeyspaceRegistrar =
        AndroidCustodianKeyspaceRegistrar(application),
) {
    private val backupRegistry = SpaceBackupRegistry(application)
    private val keyspaceClaims = BackupKeyspaceClaimJournal(File(application.filesDir, "backup-keyspace-claims"))

    /** Run after group lifecycle recovery, before exposing spaces or permitting another backup claim. */
    fun reconcileBackupKeyspaceClaims() {
        keyspaceClaims.records().forEach(::recoverKeyspaceClaim)
    }

    private fun recoverKeyspaceClaim(record: BackupKeyspaceClaim) {
        val owner = custodian.resolveArchivedOwner(record.archiveId, CustodianContract.LINE_PACKAGE,
            record.keyspaceId, record.archiveSha256)
        val destination = groups.find(record.destinationOwner)
        if (destination != null) {
            check(owner == record.destinationOwner && destination.contains(CustodianContract.LINE_PACKAGE)) {
                "已提交空間的備份金鑰交易狀態不一致"
            }
        } else if (owner == record.destinationOwner) {
            check(groups.find(record.originalOwner) == null) { "備份來源身分已被其他空間使用" }
            custodian.reassignArchive(record.archiveId, record.destinationOwner, record.originalOwner,
                CustodianContract.LINE_PACKAGE, record.keyspaceId, record.archiveSha256)
        } else {
            check(owner == record.originalOwner) { "無法確認備份金鑰交易的目前擁有者" }
        }
        keyspaceClaims.remove(record)
    }

    fun globalBackupLocation(): String? = backupRegistry.globalLocation()
    fun setGlobalBackupLocation(tree: Uri) = backupRegistry.setGlobalLocation(tree)
    fun backups(groupId: String): List<SpaceBackupEntry> = backupRegistry.list(groupId)
    fun deleteBackup(id: String) = backupRegistry.delete(id)
    fun exportToGlobalLocation(groupId: String, compression: SpaceArchiveCompression): SpaceArchiveExportResult {
        val group = requireNotNull(groups.find(groupId)) { "找不到這個空間" }
        val destination = backupRegistry.createDestination(group.name)
        return try {
            exportSpace(groupId, destination, compression)
        } catch (failure: Throwable) {
            runCatching { android.provider.DocumentsContract.deleteDocument(application.contentResolver, destination) }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    fun exportSpace(
        groupId: String,
        destination: Uri,
        compression: SpaceArchiveCompression,
    ): SpaceArchiveExportResult {
        val group = requireNotNull(groups.find(groupId)) { "找不到這個空間" }
        require(group.health == GroupHealth.HEALTHY) { "空間目前無法封存" }
        val containsLine = group.apps.any { it.packageName == CustodianContract.LINE_PACKAGE }
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
        val keyspaceId = if (containsLine) custodian.retainForArchive(group, CustodianContract.LINE_PACKAGE) else ""
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
            if (containsLine) custodian.sealArchive(
                group.id,
                archiveId,
                CustodianContract.LINE_PACKAGE,
                keyspaceId,
                archiveSha256,
            )
            backupRegistry.record(group.id, group.name, destination, bytesWritten)
            return SpaceArchiveExportResult(group.name, bytesWritten)
        } catch (failure: Throwable) {
            runCatching { application.contentResolver.delete(destination, null, null) }
            if (containsLine && !markerExisted) runCatching { custodian.cancelArchiveRetention(group) }
            throw failure
        } finally {
            generatedRoot.deleteRecursively()
        }
    }

    fun createSpaceFromBackup(source: Uri): CreateSpaceFromBackupResult {
        reconcileBackupKeyspaceClaims()
        val operationId = UUID.randomUUID().toString()
        val operationRoot = File(application.cacheDir, "space-from-backup/$operationId")
        val archiveFile = File(operationRoot, "source.apptwin-space")
        val stagingRoot = File(operationRoot, "staging")
        check(operationRoot.mkdirs()) { "無法準備從備份建立空間" }
        var created: Group? = null
        var pendingKeyspaceClaim: BackupKeyspaceClaim? = null
        try {
            val archiveSha256 = copyArchiveAndDigest(source, archiveFile)
            val archive = SpaceArchiveReader().readAndExtract(archiveFile, stagingRoot)
            val manifest = archive.manifest
            val containsLine = manifest.apps.any { it.packageName == CustodianContract.LINE_PACKAGE }
            manifest.apps.forEach { app ->
                val active = requireNotNull(revisions.active(app.packageName)) {
                    "裝置缺少 ${app.packageName} 的 active revision"
                }
                require(active.revisionId == app.revisionId) {
                    "${app.packageName} revision 與存檔不一致"
                }
            }
            val currentOwner = if (containsLine) requireNotNull(
                custodian.resolveArchivedOwner(
                    manifest.archiveId,
                    CustodianContract.LINE_PACKAGE,
                    manifest.custodianKeyspaceId,
                    archiveSha256,
                ),
            ) { "Custodian 無法驗證此存檔或存檔內容已變更" } else null
            require(currentOwner == null || groups.find(currentOwner) == null) {
                "此存檔的 LINE 身分仍由現有空間使用；請先移除原空間"
            }

            val archivedApps = manifest.apps.map { archived ->
                GroupApp(archived.packageName, archived.addedAt, GroupAppState.valueOf(archived.state.name))
            }
            val destination = lifecycle.createGroup(manifest.name, initialApps = archivedApps,
                finalizeCreation = { provisional -> hydrateAccounts(archive, provisional) }) { provisional ->
                val hydrated = provisional.copy(health = GroupHealth.HEALTHY, apps = archivedApps)
                if (containsLine) {
                    val claim = BackupKeyspaceClaim(operationId, manifest.archiveId, requireNotNull(currentOwner),
                        hydrated.id, manifest.custodianKeyspaceId, archiveSha256)
                    // Persist before the service owner change; marker-write failures are recoverable too.
                    keyspaceClaims.write(claim)
                    pendingKeyspaceClaim = claim
                    custodian.claimArchive(manifest.archiveId, claim.originalOwner, hydrated,
                        CustodianContract.LINE_PACKAGE, claim.keyspaceId, archiveSha256)
                }
                hydrated.apps.forEach { app -> runtime.preparePackageFromBackup(hydrated, app) }
                val binding = requireNotNull(hydrated.environmentBinding)
                runtime.quiesceEnvironment(binding)
                hydrateArchiveTrees(archive, binding.internalId)
                hydrateRuntimeMetadata(archive, hydrated)
                // Activation markers are authority, never trust their archived owner identity.
                listOf(CustodianKeyspaceState.fileForUser(binding.internalId),
                    CustodianKeyspaceState.retainedFileForUser(binding.internalId)).forEach { marker ->
                    check(!marker.exists() || marker.delete()) { "無法重建備份的金鑰隔離狀態" }
                }
                if (containsLine) {
                    CustodianKeyspaceState.writeForUser(binding.internalId, hydrated.id, manifest.custodianKeyspaceId)
                    CustodianKeyspaceState.writeRetainedForUser(binding.internalId,
                        CustodianContract.LINE_PACKAGE, manifest.custodianKeyspaceId)
                }
            }
            created = destination
            // Journal removal is post-commit cleanup; never delete a committed destination for its failure.
            pendingKeyspaceClaim?.let { claim -> runCatching { keyspaceClaims.remove(claim) } }
            return CreateSpaceFromBackupResult(requireNotNull(groups.find(destination.id)))
        } catch (failure: Throwable) {
            created?.let { group ->
                runCatching { lifecycle.deleteGroup(group.id) }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }
            pendingKeyspaceClaim?.let { claim ->
                runCatching { recoverKeyspaceClaim(claim) }.exceptionOrNull()?.let(failure::addSuppressed)
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
        val accountBytes = VAccountManager.get().exportAccountsForBackup(binding.internalId)
        require(accountBytes.size <= MAX_ACCOUNT_METADATA_BYTES) { "備份帳號資料超過上限" }
        FileOutputStream(File(root, ACCOUNTS_FILE)).use { output ->
            output.write(accountBytes)
            output.fd.sync()
        }
        val permissions = Properties().apply {
            group.apps.forEachIndexed { index, app ->
                setProperty("$index.packageName", app.packageName)
                val permissions = controlledPermissions(app.packageName, binding.internalId)
                setProperty("$index.permissions", permissions.joinToString(","))
                permissions.forEach { permission ->
                    setProperty("$index.$permission", permissionGranted(permission, app.packageName,
                        binding.internalId).toString())
                }
            }
            setProperty("count", group.apps.size.toString())
        }
        FileOutputStream(File(root, PERMISSIONS_FILE)).use { output ->
            permissions.store(output, "AppTwin Space archive runtime permissions")
            output.fd.sync()
        }
    }

    private fun hydrateAccounts(archive: SpaceArchiveReadResult, group: Group) {
        val userId = requireNotNull(group.environmentBinding).internalId
        val file = File(archive.stagingRoot, "payload/runtime/$ACCOUNTS_FILE")
        require(file.isFile && file.length() in 1..MAX_ACCOUNT_METADATA_BYTES.toLong()) {
            "備份帳號資料損毀或超過上限"
        }
        val manager = VAccountManager.get()
        manager.importAccountsFromBackup(userId, file.readBytes())
        val types = manager.getAuthenticatorTypes(userId).map { it.type }.toSet()
        require(manager.getAccounts(userId, null).all { it.type in types }) {
            "備份帳號缺少對應的帳號服務"
        }
    }

    private fun hydrateRuntimeMetadata(archive: SpaceArchiveReadResult, group: Group) {
        val binding = requireNotNull(group.environmentBinding)
        val runtimeRoot = File(archive.stagingRoot, "payload/runtime")
        val deviceFile = File(runtimeRoot, DEVICE_INFO_FILE)
        require(deviceFile.isFile && deviceFile.length() in 1..MAX_RUNTIME_METADATA_BYTES) {
            "備份裝置資料損毀或超過上限"
        }
        val deviceBytes = deviceFile.readBytes()
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(deviceBytes, 0, deviceBytes.size)
            parcel.setDataPosition(0)
            runtime.hydrateDeviceInfo(binding, VDeviceInfo.CREATOR.createFromParcel(parcel))
        } finally {
            parcel.recycle()
        }
        val permissionsFile = File(runtimeRoot, PERMISSIONS_FILE)
        require(permissionsFile.isFile && permissionsFile.length() in 1..MAX_RUNTIME_METADATA_BYTES) {
            "備份權限資料損毀或超過上限"
        }
        val permissions = Properties().apply {
            FileInputStream(permissionsFile).use(::load)
        }
        val count = permissions.getProperty("count")?.toIntOrNull()
            ?: error("存檔權限資料損毀")
        require(count == group.apps.size) { "備份權限套件數量不一致" }
        val seen = mutableSetOf<String>()
        repeat(count) { index ->
            val packageName = requireNotNull(permissions.getProperty("$index.packageName"))
            require(group.contains(packageName) && seen.add(packageName)) { "備份權限套件不屬於新空間或重複" }
            val archivedPermissions = requireNotNull(permissions.getProperty("$index.permissions"))
                .split(',').filter(String::isNotEmpty)
            require(archivedPermissions.distinct().size == archivedPermissions.size &&
                archivedPermissions.toSet() == controlledPermissions(packageName, binding.internalId).toSet()) {
                "備份權限集合不一致"
            }
            archivedPermissions.forEach { permission ->
                setPermission(permission, packageName, binding.internalId,
                    requireNotNull(permissions.getProperty("$index.$permission")) {
                        "備份缺少權限決策"
                    }.toBooleanStrict())
            }
        }
    }

    private fun hydrateArchiveTrees(archive: SpaceArchiveReadResult, userId: Int) {
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
            "無法清除載入備份目標：${destination.name}"
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
                    "無法設定載入備份檔案權限"
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

    private fun controlledPermissions(packageName: String, userId: Int): List<String> =
        VPackageManager.get().getPackageInfo(packageName, PackageManager.GET_PERMISSIONS, userId)
            ?.requestedPermissions.orEmpty().filter { permission ->
                com.lody.virtual.client.hook.GuestPermissionPolicy.isControlled(permission) &&
                    runCatching {
                        val info = application.packageManager.getPermissionInfo(permission, 0)
                        (info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS
                    }.getOrDefault(false)
            }.sorted()

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
        ) { "無法載入備份 $packageName 權限" }
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
        const val MAX_RUNTIME_METADATA_BYTES = 1024L * 1024L
        const val MAX_ACCOUNT_METADATA_BYTES = 256 * 1024
        const val ACCOUNTS_FILE = "scoped-accounts.bin"
        const val DEVICE_INFO_FILE = "device-info.bin"
        const val PERMISSIONS_FILE = "permissions.properties"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val MAX_ARCHIVE_BYTES = 8L * 1024L * 1024L * 1024L
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
