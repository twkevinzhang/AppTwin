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
import org.maskaccounts.instances.VirtualInstance
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

/** Bridges MaskAccounts immutable revisions and instance identities to the GPL virtual engine. */
class VirtualRuntimeController(context: Context) {
    private val appContext = context.applicationContext
    private val importer = AndroidPackageRevisionImporter(appContext)

    fun installAndLaunch(
        instance: VirtualInstance,
        activityName: String? = null,
    ): RuntimeLaunchResult = runCatching {
        val packageName = instance.packageName
        val core = VirtualCore.get()
        core.waitForEngine()
        val virtualUserId = virtualUserIdFor(instance)
        ensureRequiredPackages(packageName, core, virtualUserId)
        if (packageName == CloneRuntimeSupport.MAPS_PACKAGE) {
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
        persistRuntimeMapping(instance, virtualUserId, dataDirectory)
        Log.i(
            TAG,
            "clone-start package=$packageName instance=${instance.id} user=$virtualUserId " +
                "data=${dataDirectory.absolutePath}",
        )
        RuntimeLaunchResult.Started(
            packageName = packageName,
            virtualUserId = virtualUserId,
            processPrefix = "${appContext.packageName}:p",
            dataDirectory = dataDirectory.absolutePath,
        )
    }.getOrElse { error ->
        Log.e(TAG, "Clone launch failed for ${instance.packageName}/${instance.id}", error)
        RuntimeLaunchResult.Failed(error.message ?: error.javaClass.simpleName, error)
    }

    private fun persistRuntimeMapping(
        instance: VirtualInstance,
        virtualUserId: Int,
        runtimeDataDirectory: File,
    ) {
        val instanceData = File(
            appContext.filesDir,
            "instances/${instance.packageName}/${instance.id}/data",
        )
        check(instanceData.isDirectory) { "找不到 instance data root" }
        val mapping = Properties().apply {
            setProperty("packageName", instance.packageName)
            setProperty("instanceId", instance.id)
            setProperty("virtualUserId", virtualUserId.toString())
            setProperty("runtimeDataDirectory", runtimeDataDirectory.absolutePath)
        }
        FileOutputStream(File(instanceData, "runtime.properties")).use { output ->
            mapping.store(output, "MaskAccounts virtual runtime mapping")
            output.fd.sync()
        }
    }

    private fun virtualUserIdFor(instance: VirtualInstance): Int {
        if (!CloneRuntimeSupport.requiresDedicatedVirtualUser(instance.packageName)) return 0
        val mapping = runtimeMappingFile(instance)
        val existingUserId = mapping.takeIf(File::isFile)
            ?.let(::readProperties)
            ?.getProperty("virtualUserId")
            ?.toIntOrNull()
        if (existingUserId != null && VUserManager.get().getUserInfo(existingUserId) != null) {
            return existingUserId
        }
        val user = requireNotNull(
            VUserManager.get().createUser(
                "MaskAccounts ${instance.packageName.takeLast(24)} ${instance.id.take(8)}",
                0,
            ),
        ) { "無法建立 Maps virtual user" }
        persistVirtualUserId(instance, user.id)
        Log.i(TAG, "maps-m1-virtual-user-created instance=${instance.id} user=${user.id}")
        return user.id
    }

    private fun persistVirtualUserId(instance: VirtualInstance, virtualUserId: Int) {
        val mapping = runtimeMappingFile(instance)
        val properties = mapping.takeIf(File::isFile)?.let(::readProperties) ?: Properties()
        properties.setProperty("packageName", instance.packageName)
        properties.setProperty("instanceId", instance.id)
        properties.setProperty("virtualUserId", virtualUserId.toString())
        FileOutputStream(mapping).use { output ->
            properties.store(output, "MaskAccounts virtual runtime mapping")
            output.fd.sync()
        }
    }

    private fun runtimeMappingFile(instance: VirtualInstance): File = File(
        appContext.filesDir,
        "instances/${instance.packageName}/${instance.id}/data/runtime.properties",
    )

    /**
     * Google clients resolve Play services and the Play Store through the virtual PackageManager.
     * Import their main-system revisions before the guest application's own first launch; their
     * code stays shared, while the guest app's data remains under its virtual user directory.
     */
    private fun ensureRequiredPackages(packageName: String, core: VirtualCore, virtualUserId: Int) {
        CloneRuntimeSupport.requiredPackages(packageName).forEach { dependency ->
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
    }
}

private fun PackageInfo.versionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
