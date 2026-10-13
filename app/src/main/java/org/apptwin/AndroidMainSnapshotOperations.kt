package org.apptwin

import android.Manifest
import android.os.Build
import android.os.Environment
import com.lody.virtual.client.ipc.VAccountManager
import com.lody.virtual.os.VEnvironment
import org.apptwin.accounts.SpaceAccountSync
import org.apptwin.archive.AndroidSpaceArchiveOperations
import org.apptwin.gms.AndroidGmsOperations
import org.apptwin.gms.GmsGroupProductState
import org.apptwin.gms.capabilities.GmsCapability
import org.apptwin.gms.capabilities.GmsCapabilityAssessment
import org.apptwin.gms.capabilities.GmsCapabilityStatus
import org.apptwin.gms.model.GmsGroupId
import org.apptwin.gms.model.GmsProfile
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.groups.GroupHealth
import org.apptwin.operations.FileOperationRecordStore
import org.apptwin.permissions.ClonePermissionPolicy
import org.apptwin.permissions.ClonePermissionVirtualScope
import org.apptwin.revision.ActiveRevisionSummary
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.runtime.VirtualRuntimeController

/** Android implementation of the MainSnapshotOperations feature boundary. */
internal class AndroidMainSnapshotOperations(
    private val groupStore: FileGroupStore,
    private val operationStore: FileOperationRecordStore,
    private val importer: AndroidPackageRevisionImporter,
    private val runtimeController: VirtualRuntimeController,
    private val shortcutPublisher: GroupAppShortcutPublisher,
    private val gms: AndroidGmsOperations,
    private val archives: AndroidSpaceArchiveOperations,
    private val permissionOperations: AndroidClonePermissionOperations,
    private val accountOperations: AndroidSpaceAccountOperations,
) : MainSnapshotOperations {
    override suspend fun loadGroupSnapshot(): MainGroupSnapshot {
        val groupSnapshot = groupStore.loadSnapshot()
        return MainGroupSnapshot(
            groups = groupSnapshot.groups,
            operations = operationStore.listPending(),
            dataWarnings = groupSnapshot.issues.map { issue ->
                "Group ${issue.groupId}/${issue.metadataName} 無法讀取"
            },
        )
    }

    override suspend fun refreshSnapshot(groups: MainGroupSnapshot): MainRefreshSnapshot {
        shortcutPublisher.reconcile(groups.groups)
        val storage = readStorageStatus()
        val entries = runCatching(importer::listCloneableApps).getOrDefault(emptyList())
        groups.groups.forEach { group ->
            runCatching { runtimeController.syncEnvironmentLabel(group) }
        }
        val activeRevisions = linkedMapOf<String, ActiveRevisionSummary?>()
        val warnings = groups.dataWarnings.toMutableList()
        val gmsCompatibility = groups.groups.associate { group ->
            group.id to runCatching { gms.snapshot(group.id) }.getOrElse { error ->
                warnings += "Group ${group.id}/data/gms 無法讀取：${error.userMessage()}"
                GmsGroupProductState(
                    profile = GmsProfile.disabled(GmsGroupId(group.id)),
                    capabilities = GmsCapability.entries.map { capability ->
                        GmsCapabilityAssessment(
                            capability,
                            if (capability in setOf(
                                    GmsCapability.PLAY_BILLING,
                                    GmsCapability.PLAY_INTEGRITY,
                                )
                            ) {
                                GmsCapabilityStatus.UNSUPPORTED
                            } else {
                                GmsCapabilityStatus.UNTESTED
                            },
                        )
                    },
                    hasDataWarning = true,
                )
            }
        }
        gms.warnings().forEach { warning ->
            val message = "Group ${warning.groupId}/data/gms/${warning.metadataName} 無法讀取"
            if (message !in warnings) warnings += message
        }
        entries.forEach { entry ->
            activeRevisions[entry.packageName] = runCatching { importer.active(entry.packageName) }
                .getOrElse { error ->
                    warnings += "${entry.packageName} active revision 無法讀取：${error.userMessage()}"
                    null
                }
        }
        val permissions = buildMap {
            groups.groups.forEach { group ->
                val userId = group.environmentBinding?.internalId ?: return@forEach
                group.apps.forEach { app ->
                    val state = ClonePermissionState(
                        cameraGranted = permissionOperations.virtualPermissionGranted(
                            Manifest.permission.CAMERA,
                            app.packageName,
                            userId,
                        ),
                        microphoneGranted = permissionOperations.virtualPermissionGranted(
                            Manifest.permission.RECORD_AUDIO,
                            app.packageName,
                            userId,
                        ),
                    )
                    put("${group.id}:${app.packageName}", state)
                }
            }
        }
        val clonePermissions = ClonePermissionPolicy.aggregate(
            permissionOperations.readClonePermissionRequirements(groups.groups, entries, warnings),
        )
        return MainRefreshSnapshot(
            storage = storage,
            entries = entries,
            groups = groups.groups,
            activeRevisions = activeRevisions,
            dataWarnings = warnings,
            operations = groups.operations,
            permissions = permissions,
            clonePermissions = clonePermissions,
            gmsCompatibility = gmsCompatibility,
        )
    }

    override suspend fun loadSpaceDetails(groupId: String): SpaceDetails? {
        val group = groupStore.find(groupId) ?: return null
        if (group.health == GroupHealth.HEALTHY) accountOperations.prepareAccountApps(groupId)
        val userId = group.environmentBinding?.internalId ?: return null
        val roots = listOf(VEnvironment.getUserSystemDirectory(userId), VEnvironment.getDeUserSystemDirectory(userId))
        fun bytes(file: java.io.File): Long = if (!file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath())) 0 else if (file.isFile) file.length() else file.listFiles().orEmpty().sumOf(::bytes)
        var data = 0L; var cache = 0L; var google = 0L
        roots.forEach { root -> root.listFiles().orEmpty().forEach { directory ->
            val size = bytes(directory)
            if (directory.name in setOf("com.google.android.gms", "com.android.vending", "com.google.android.gsf")) google += size
            else { val cached = bytes(java.io.File(directory,"cache")) + bytes(java.io.File(directory,"code_cache")); cache += cached; data += size - cached }
        } }
        val permissions = ClonePermissionPolicy.aggregate(permissionOperations.readClonePermissionRequirements(listOf(group), emptyList(), mutableListOf())).map { summary ->
            SpacePermissionEntry(summary.permission, summary.label, summary.granted, summary.permission in AndroidClonePermissionOperations.SUPPORTED_RUNTIME_PERMISSIONS && summary.virtualScope != ClonePermissionVirtualScope.PLATFORM_EXEMPT,
                summary.affectedClones.map { SpacePermissionApp(it.packageName, it.appLabel, permissionOperations.virtualPermissionGranted(summary.permission, it.packageName, userId)) },
                platformExempt = summary.virtualScope == ClonePermissionVirtualScope.PLATFORM_EXEMPT)
        }
        val manager = VAccountManager.get()
        return SpaceDetails(groupId, SpaceStorageBreakdown(data,cache,(VEnvironment.getVirtualStorageDir("",userId)?.let(::bytes) ?: 0),google), permissions,
            manager.getAccounts(userId,null).map { account ->
                val authorities = SpaceAccountSync.adapters(userId, account.type).map { it.authority }
                SpaceAccountEntry(account.name,account.type,authorities,authorities.isNotEmpty(),if(authorities.isEmpty()) "此帳號沒有可用的同步服務" else "可手動同步")
            },
            manager.getAuthenticatorTypes(userId).map { SpaceAccountType(it.type, when (it.type) { "com.google" -> "Google 帳號"; "com.google.work" -> "企業管理帳號"; else -> it.type }) }, archives.backups(groupId), archives.globalBackupLocation())
    }

    private fun readStorageStatus(): StorageStatus {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
        if (!granted) return StorageStatus(false, 0, 0)
        return StorageStatus(
            granted = true,
            downloadCount = publicDirectoryEntryCount(Environment.DIRECTORY_DOWNLOADS),
            photoCount = publicDirectoryEntryCount(Environment.DIRECTORY_DCIM) +
                publicDirectoryEntryCount(Environment.DIRECTORY_PICTURES),
        )
    }

    private fun publicDirectoryEntryCount(directoryType: String): Int = runCatching {
        @Suppress("DEPRECATION")
        Environment.getExternalStoragePublicDirectory(directoryType).listFiles()?.size ?: 0
    }.getOrDefault(0)
}

private fun Throwable.userMessage(): String = message ?: javaClass.simpleName
