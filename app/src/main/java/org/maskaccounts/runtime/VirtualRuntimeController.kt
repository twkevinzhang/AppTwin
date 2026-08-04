package org.maskaccounts.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import android.util.Log
import com.lody.virtual.client.core.InstallStrategy
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.client.ipc.VPackageManager
import com.lody.virtual.os.VEnvironment
import com.lody.virtual.os.VUserManager
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import org.maskaccounts.groups.AppGroup
import org.maskaccounts.groups.FileGroupStore
import org.maskaccounts.groups.GroupApp
import org.maskaccounts.revision.AndroidPackageRevisionImporter
import org.maskaccounts.revision.RevisionImportResult

sealed interface RuntimeLaunchResult {
    data class Started(
        val packageName: String,
        val virtualUserId: Int,
        val processPrefix: String,
        val dataDirectory: String,
    ) : RuntimeLaunchResult

    data class Failed(val reason: String, val error: Throwable? = null) : RuntimeLaunchResult
}

sealed interface GroupPreparationResult {
    data class Ready(val virtualUserId: Int) : GroupPreparationResult
    data class Failed(val reason: String, val error: Throwable? = null) : GroupPreparationResult
}

/** Bridges shared package revisions and isolated Group identities to the GPL virtual engine. */
class VirtualRuntimeController(context: Context) {
    private val appContext = context.applicationContext
    private val importer = AndroidPackageRevisionImporter(appContext)

    fun prepareGroup(group: AppGroup): GroupPreparationResult = runCatching {
        val core = VirtualCore.get()
        core.waitForEngine()
        val resolution = virtualUserFor(group)
        val virtualUserId = resolution.userId
        ensurePackages(GROUP_GOOGLE_PACKAGES, core, virtualUserId)
        migrateLegacyGroupApps(group, resolution)
        GroupPreparationResult.Ready(virtualUserId)
    }.getOrElse { error ->
        Log.e(TAG, "Group runtime preparation failed for ${group.id}", error)
        GroupPreparationResult.Failed(error.message ?: error.javaClass.simpleName, error)
    }

    fun installAndLaunch(
        group: AppGroup,
        app: GroupApp,
        activityName: String? = null,
    ): RuntimeLaunchResult = runCatching {
        require(group.contains(app.packageName)) { "GroupApp does not belong to this group" }
        val packageName = app.packageName
        val core = VirtualCore.get()
        core.waitForEngine()
        val resolution = virtualUserFor(group)
        val virtualUserId = resolution.userId
        ensurePackages(GROUP_GOOGLE_PACKAGES, core, virtualUserId)
        migrateLegacyGroupApps(group, resolution)
        ensurePackages(GroupAppRuntimeSupport.requiredPackages(packageName), core, virtualUserId)
        if (packageName == GroupAppRuntimeSupport.MAPS_PACKAGE) {
            runCatching {
                GoogleRuntimeBootstrap.prewarmCheckin(virtualUserId)
            }.onFailure { error ->
                // Checkin accelerates first-time Google setup, but it is not a Maps launch
                // prerequisite. A stale transient GMS service must not strand an otherwise
                // healthy clone after device registration has already completed.
                Log.w(TAG, "google-checkin-prewarm-skipped user=$virtualUserId", error)
            }
        }
        val revision = requireNotNull(importer.activeRevisionDirectory(packageName)) {
            "沒有可啟動的 active revision"
        }
        prepareVirtualExternalStorage()

        if (!core.isAppInstalled(packageName)) {
            val result = core.installPackage(
                revision.absolutePath,
                InstallStrategy.TERMINATE_IF_EXIST or InstallStrategy.SKIP_DEX_OPT,
            )
            check(result.isSuccess) { result.error ?: "virtual package install failed" }
        }
        check(
            core.isAppInstalledAsUser(virtualUserId, packageName) ||
                core.installPackageAsUser(virtualUserId, packageName),
        ) {
            "無法將 $packageName 提供給 virtual user $virtualUserId"
        }
        markGuestCodeReadOnly(core, packageName)

        check(core.isAppInstalledAsUser(virtualUserId, packageName)) {
            "virtual package is not installed for user $virtualUserId"
        }
        val virtualPackage = requireNotNull(
            VPackageManager.get().getPackageInfo(packageName, 0, virtualUserId),
        ) { "virtual package info is missing" }
        Log.i(
            TAG,
            "virtual-package package=${virtualPackage.packageName} " +
                "versionCode=${virtualPackage.versionCodeCompat()} " +
                "versionName=${virtualPackage.versionName} " +
                "splits=${virtualPackage.splitNames?.contentToString()} " +
                "sourceDir=${virtualPackage.applicationInfo?.sourceDir}",
        )
        val launchIntent = if (activityName == null) {
            requireNotNull(core.getLaunchIntent(packageName, virtualUserId)) {
                "找不到 virtual launcher activity"
            }
        } else {
            val component = ComponentName(packageName, activityName)
            requireNotNull(
                VPackageManager.get().getActivityInfo(component, 0, virtualUserId),
            ) { "找不到 virtual activity：$activityName" }
            Intent(Intent.ACTION_MAIN)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resultCode = VActivityManager.get().startActivity(launchIntent, virtualUserId)
        check(resultCode >= 0) { "virtual activity start failed: $resultCode" }

        val dataDirectory = VEnvironment.getDataUserPackageDirectory(virtualUserId, packageName)
        persistRuntimeMapping(group, app, virtualUserId, dataDirectory)
        Log.i(
            TAG,
            "group-app-start package=$packageName group=${group.id} user=$virtualUserId " +
                "data=${dataDirectory.absolutePath}",
        )
        RuntimeLaunchResult.Started(
            packageName = packageName,
            virtualUserId = virtualUserId,
            processPrefix = "${appContext.packageName}:p",
            dataDirectory = dataDirectory.absolutePath,
        )
    }.getOrElse { error ->
        Log.e(TAG, "GroupApp launch failed for ${app.packageName}/${group.id}", error)
        RuntimeLaunchResult.Failed(error.message ?: error.javaClass.simpleName, error)
    }

    fun deleteGroupRuntime(group: AppGroup) {
        val core = VirtualCore.get()
        core.waitForEngine()
        val virtualUserId = readMappedVirtualUserId(group) ?: return
        if (virtualUserId == 0) {
            // Legacy M0 records used user 0. Remove only this group's apps; user 0 may still back
            // another migrated group, so deleting it would destroy unrelated data.
            group.apps.forEach { app ->
                if (core.isAppInstalledAsUser(virtualUserId, app.packageName)) {
                    check(core.uninstallPackageAsUser(app.packageName, virtualUserId)) {
                        "無法清除舊版 GroupApp 資料：${app.packageName}"
                    }
                }
            }
        } else {
            check(VUserManager.get().removeUser(virtualUserId)) {
                "無法刪除 Group virtual user $virtualUserId"
            }
        }
        Log.i(TAG, "group-runtime-delete-started group=${group.id} user=$virtualUserId")
    }

    private fun persistRuntimeMapping(
        group: AppGroup,
        app: GroupApp,
        virtualUserId: Int,
        runtimeDataDirectory: File,
    ) {
        val groupData = groupDataDirectory(group)
        check(groupData.isDirectory) { "找不到 group data root" }
        val mappingFile = File(groupData, FileGroupStore.RUNTIME_METADATA)
        val mapping = mappingFile.takeIf(File::isFile)?.let(::readProperties) ?: Properties()
        mapping.apply {
            setProperty("groupId", group.id)
            setProperty("virtualUserId", virtualUserId.toString())
            setProperty("runtimeDataDirectory.${app.packageName}", runtimeDataDirectory.absolutePath)
        }
        FileOutputStream(mappingFile).use { output ->
            mapping.store(output, "MaskAccounts Group runtime mapping")
            output.fd.sync()
        }
    }

    private fun virtualUserFor(group: AppGroup): VirtualUserResolution {
        val properties = runtimeMappingFile(group)
            .takeIf(File::isFile)
            ?.let(::readProperties)
            ?: Properties()
        val existingUserId = properties.getProperty("virtualUserId")?.toIntOrNull()
        val isLegacySharedUser = requiresDedicatedGroupMigration(
            existingUserId = existingUserId,
            hasLegacyInstanceId = properties.getProperty("instanceId") != null,
            legacyDataMigrated = properties.getProperty("legacyDataMigrated") == "true",
        )
        if (
            existingUserId != null &&
            !isLegacySharedUser &&
            VUserManager.get().getUserInfo(existingUserId) != null
        ) {
            return VirtualUserResolution(
                existingUserId,
                properties.getProperty("legacyVirtualUserId")?.toIntOrNull(),
            )
        }
        val user = requireNotNull(
            VUserManager.get().createUser(
                "MaskAccounts ${group.name.take(24)} ${group.id.take(8)}",
                0,
            ),
        ) { "無法建立 Group virtual user" }
        val legacySourceUserId = existingUserId.takeIf { isLegacySharedUser }
        persistVirtualUserId(group, user.id, legacySourceUserId)
        Log.i(TAG, "group-virtual-user-created group=${group.id} user=${user.id}")
        return VirtualUserResolution(user.id, legacySourceUserId)
    }

    private fun persistVirtualUserId(
        group: AppGroup,
        virtualUserId: Int,
        legacySourceUserId: Int?,
    ) {
        val mapping = runtimeMappingFile(group)
        val properties = mapping.takeIf(File::isFile)?.let(::readProperties) ?: Properties()
        properties.setProperty("groupId", group.id)
        properties.setProperty("virtualUserId", virtualUserId.toString())
        legacySourceUserId?.let { sourceUserId ->
            properties.setProperty("legacyVirtualUserId", sourceUserId.toString())
            properties.setProperty("legacyDataMigrated", "false")
        }
        FileOutputStream(mapping).use { output ->
            properties.store(output, "MaskAccounts Group runtime mapping")
            output.fd.sync()
        }
    }

    /**
     * M0 placed ordinary apps in virtual user 0. On first Group preparation, copy only each
     * GroupApp's private data into its new user. GMS data deliberately starts clean so separate
     * migrated Groups cannot inherit the same Google account environment.
     */
    private fun migrateLegacyGroupApps(
        group: AppGroup,
        resolution: VirtualUserResolution,
    ) {
        val sourceUserId = resolution.legacySourceUserId ?: return
        val mapping = runtimeMappingFile(group)
        val properties = readProperties(mapping)
        if (properties.getProperty("legacyDataMigrated") == "true") {
            var changed = properties.remove("runtimeDataDirectory") != null
            group.apps.forEach { app ->
                val key = "runtimeDataDirectory.${app.packageName}"
                if (properties.getProperty(key) == null) {
                    properties.setProperty(
                        key,
                        VEnvironment.getDataUserPackageDirectory(
                            resolution.userId,
                            app.packageName,
                        ).absolutePath,
                    )
                    changed = true
                }
            }
            if (changed) writeRuntimeProperties(mapping, properties)
            return
        }
        group.apps.forEach { app ->
            val source = VEnvironment.getDataUserPackageDirectory(sourceUserId, app.packageName)
            if (!source.isDirectory) return@forEach
            VActivityManager.get().killAppByPkg(app.packageName, sourceUserId)
            val destination = VEnvironment.getDataUserPackageDirectory(
                resolution.userId,
                app.packageName,
            )
            if (destination.exists()) {
                check(destination.deleteRecursively()) {
                    "無法清除新 GroupApp 的空白資料目錄：${app.packageName}"
                }
            }
            check(destination.parentFile?.isDirectory == true || destination.parentFile?.mkdirs() == true) {
                "無法建立 GroupApp 資料目錄：${app.packageName}"
            }
            check(source.copyRecursively(destination, overwrite = true)) {
                "無法搬移舊 GroupApp 資料：${app.packageName}"
            }
            properties.setProperty(
                "runtimeDataDirectory.${app.packageName}",
                destination.absolutePath,
            )
            Log.i(
                TAG,
                "legacy-group-app-data-copied group=${group.id} package=${app.packageName} " +
                    "fromUser=$sourceUserId toUser=${resolution.userId}",
            )
        }
        properties.remove("runtimeDataDirectory")
        properties.setProperty("legacyDataMigrated", "true")
        writeRuntimeProperties(mapping, properties)
    }

    private fun writeRuntimeProperties(mapping: File, properties: Properties) {
        FileOutputStream(mapping).use { output ->
            properties.store(output, "MaskAccounts Group runtime mapping")
            output.fd.sync()
        }
    }

    private fun readMappedVirtualUserId(group: AppGroup): Int? = runtimeMappingFile(group)
        .takeIf(File::isFile)
        ?.let(::readProperties)
        ?.getProperty("virtualUserId")
        ?.toIntOrNull()

    private fun runtimeMappingFile(group: AppGroup): File =
        File(groupDataDirectory(group), FileGroupStore.RUNTIME_METADATA)

    private fun groupDataDirectory(group: AppGroup): File =
        File(appContext.filesDir, "groups/${group.id}/${FileGroupStore.DATA_DIRECTORY}")

    /**
     * Google clients resolve Play services and the Play Store through the virtual PackageManager.
     * Import their main-system revisions before the guest application's own first launch; their
     * code stays shared, while the guest app's data remains under its virtual user directory.
     */
    private fun ensurePackages(
        packages: List<String>,
        core: VirtualCore,
        virtualUserId: Int,
    ) {
        packages.distinct().forEach { dependency ->
            when (val sync = importer.sync(dependency)) {
                is RevisionImportResult.Activated,
                is RevisionImportResult.AlreadyCurrent,
                -> Unit
                is RevisionImportResult.Rejected -> error("無法同步 $dependency：${sync.reason}")
                is RevisionImportResult.Failed -> error("無法同步 $dependency：${sync.reason}")
            }
            if (!core.isAppInstalled(dependency)) {
                val revision = requireNotNull(importer.activeRevisionDirectory(dependency))
                val result = core.installPackage(
                    revision.absolutePath,
                    InstallStrategy.TERMINATE_IF_EXIST or InstallStrategy.SKIP_DEX_OPT,
                )
                check(result.isSuccess) {
                    "無法安裝 Google 相依套件 $dependency：${result.error ?: "unknown error"}"
                }
            }
            check(
                core.isAppInstalledAsUser(virtualUserId, dependency) ||
                    core.installPackageAsUser(virtualUserId, dependency),
            ) {
                "Google 相依套件未提供給 virtual user $virtualUserId：$dependency"
            }
            Log.i(
                TAG,
                "google-runtime-dependency-ready package=$dependency user=$virtualUserId",
            )
        }
    }

    private fun readProperties(file: File): Properties = Properties().apply {
        file.inputStream().use(::load)
    }

    private fun markGuestCodeReadOnly(core: VirtualCore, packageName: String) {
        val installed = requireNotNull(core.getInstalledAppInfo(packageName, 0)) {
            "virtual package metadata is missing"
        }
        (listOf(installed.apkPath) + installed.splitCodePaths.orEmpty()).forEach { path ->
            val apk = File(path)
            check(apk.isFile && apk.setReadOnly()) { "無法將 guest code 設為唯讀：$path" }
        }
    }

    private fun prepareVirtualExternalStorage() {
        val appExternalRoot = appContext.getExternalFilesDir(null)?.parentFile ?: return
        val virtualRoot = File(appExternalRoot, "virtual/0")
        check(virtualRoot.isDirectory || virtualRoot.mkdirs()) {
            "無法建立 virtual external storage"
        }
    }

    private companion object {
        const val TAG = "MaskAccountsRuntime"
        val GROUP_GOOGLE_PACKAGES = listOf(
            GroupAppRuntimeSupport.GOOGLE_SERVICES_FRAMEWORK_PACKAGE,
            GroupAppRuntimeSupport.GOOGLE_PLAY_SERVICES_PACKAGE,
            GroupAppRuntimeSupport.GOOGLE_PLAY_STORE_PACKAGE,
        )
    }

    private data class VirtualUserResolution(
        val userId: Int,
        val legacySourceUserId: Int? = null,
    )
}

private fun PackageInfo.versionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

internal fun requiresDedicatedGroupMigration(
    existingUserId: Int?,
    hasLegacyInstanceId: Boolean,
    legacyDataMigrated: Boolean,
): Boolean = existingUserId == 0 && hasLegacyInstanceId && !legacyDataMigrated
