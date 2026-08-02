package org.maskaccounts.runtime

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.util.Log
import com.lody.virtual.client.core.InstallStrategy
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.client.ipc.VPackageManager
import com.lody.virtual.os.VEnvironment
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import org.maskaccounts.instances.VirtualInstance
import org.maskaccounts.revision.AndroidPackageRevisionImporter

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

    fun installAndLaunch(instance: VirtualInstance): RuntimeLaunchResult = runCatching {
        val packageName = instance.packageName
        val revision = requireNotNull(importer.activeRevisionDirectory(packageName)) {
            "沒有可啟動的 active revision"
        }
        val core = VirtualCore.get()
        core.waitForEngine()
        prepareVirtualExternalStorage()

        if (!core.isAppInstalled(packageName)) {
            val result = core.installPackage(
                revision.absolutePath,
                InstallStrategy.TERMINATE_IF_EXIST or InstallStrategy.SKIP_DEX_OPT,
            )
            check(result.isSuccess) { result.error ?: "virtual package install failed" }
        }
        markGuestCodeReadOnly(core, packageName)

        // M0 proves one independent LINE clone first. Additional instance/user mapping follows
        // after the launcher path is stable on the API 31 acceptance device.
        val virtualUserId = 0
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
        val launchIntent = requireNotNull(core.getLaunchIntent(packageName, virtualUserId)) {
            "找不到 virtual launcher activity"
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
