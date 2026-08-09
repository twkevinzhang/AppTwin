package org.apptwin.runtime

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
import org.apptwin.groups.EnvironmentBinding
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupAppRemovalRuntime
import org.apptwin.groups.GroupEnvironmentRuntime
import org.apptwin.groups.GroupHealth
import org.apptwin.groups.RuntimeGroupAppRemovalResult
import org.apptwin.revision.AndroidPackageRevisionImporter

sealed interface RuntimeLaunchResult {
    data class Started(
        val packageName: String,
        val processPrefix: String,
        val dataDirectory: String,
    ) : RuntimeLaunchResult

    data class Failed(val reason: String, val error: Throwable? = null) : RuntimeLaunchResult
}

/** The only adapter allowed to translate a Group environment into the engine's numeric user API. */
class VirtualRuntimeController(context: Context) : GroupEnvironmentRuntime, GroupAppRemovalRuntime {
    private val appContext = context.applicationContext
    private val importer = AndroidPackageRevisionImporter(appContext)

    override fun createEnvironment(groupId: String, groupName: String): EnvironmentBinding {
        val core = VirtualCore.get()
        core.waitForEngine()
        val user = requireNotNull(
            VUserManager.get().createUser(
                environmentName(groupId),
                0,
            ),
        ) { "無法建立群組環境" }
        Log.i(TAG, "group-environment-created groupId=$groupId environmentId=${user.id}")
        return EnvironmentBinding(user.id)
    }

    override fun findEnvironment(groupId: String): EnvironmentBinding? {
        VirtualCore.get().waitForEngine()
        val matches = VUserManager.get().users.filter { it.name == environmentName(groupId) }
        check(matches.size <= 1) { "群組存在多個隔離環境" }
        return matches.singleOrNull()?.id?.let(::EnvironmentBinding)
    }

    override fun environmentExists(binding: EnvironmentBinding): Boolean {
        VirtualCore.get().waitForEngine()
        return VUserManager.get().getUserInfo(binding.internalId) != null
    }

    override fun deleteEnvironment(binding: EnvironmentBinding) {
        val core = VirtualCore.get()
        core.waitForEngine()
        require(binding.internalId != 0) { "預設引擎環境不可刪除" }
        if (!environmentExists(binding)) return
        check(VUserManager.get().removeUser(binding.internalId)) { "無法刪除群組環境" }
        repeat(40) {
            if (!environmentExists(binding)) {
                Log.i(TAG, "group-environment-deleted environmentId=${binding.internalId}")
                return
            }
            Thread.sleep(50)
        }
        error("群組環境刪除逾時")
    }

    override fun removeApp(
        binding: EnvironmentBinding,
        packageName: String,
    ): RuntimeGroupAppRemovalResult {
        require(binding.internalId > 0) { "預設引擎環境不可移除 GroupApp" }
        val core = VirtualCore.get()
        core.waitForEngine()
        check(environmentExists(binding)) { "群組環境已損毀" }
        if (!core.isAppInstalledAsUser(binding.internalId, packageName)) {
            return RuntimeGroupAppRemovalResult.AlreadyAbsent
        }
        check(core.uninstallPackageAsUser(packageName, binding.internalId)) {
            "無法從群組移除 $packageName"
        }
        check(!core.isAppInstalledAsUser(binding.internalId, packageName)) {
            "$packageName 仍存在於群組環境"
        }
        Log.i(
            TAG,
            "group-app-removed package=$packageName environmentId=${binding.internalId}",
        )
        return RuntimeGroupAppRemovalResult.Removed
    }

    fun installAndLaunch(
        group: Group,
        app: GroupApp,
        activityName: String? = null,
    ): RuntimeLaunchResult = runCatching {
        require(group.contains(app.packageName)) { "GroupApp does not belong to this Group" }
        val binding = requireHealthyEnvironment(group)
        val environmentId = binding.internalId
        val packageName = app.packageName
        val core = VirtualCore.get()
        core.waitForEngine()
        val revision = requireNotNull(importer.activeRevisionDirectory(packageName)) {
            "沒有可啟動的 active revision"
        }
        prepareVirtualExternalStorage(environmentId)
        if (!core.isAppInstalled(packageName)) {
            val result = core.installPackage(
                revision.absolutePath,
                InstallStrategy.TERMINATE_IF_EXIST or InstallStrategy.SKIP_DEX_OPT,
            )
            check(result.isSuccess) { result.error ?: "virtual package install failed" }
        }
        check(
            core.isAppInstalledAsUser(environmentId, packageName) ||
                core.installPackageAsUser(environmentId, packageName),
        ) { "無法將 $packageName 加入群組環境" }
        markGuestCodeReadOnly(core, packageName)
        val virtualPackage = requireNotNull(
            VPackageManager.get().getPackageInfo(packageName, 0, environmentId),
        ) { "群組 App 套件資訊不存在" }
        Log.i(
            TAG,
            "group-package package=${virtualPackage.packageName} " +
                "versionCode=${virtualPackage.versionCodeCompat()} " +
                "versionName=${virtualPackage.versionName} " +
                "splits=${virtualPackage.splitNames?.contentToString()}",
        )
        val launchIntent = if (activityName == null) {
            requireNotNull(core.getLaunchIntent(packageName, environmentId)) {
                "找不到群組 App 啟動入口"
            }
        } else {
            val component = ComponentName(packageName, activityName)
            requireNotNull(VPackageManager.get().getActivityInfo(component, 0, environmentId)) {
                "找不到指定的群組 App activity"
            }
            Intent(Intent.ACTION_MAIN)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resultCode = VActivityManager.get().startActivity(launchIntent, environmentId)
        check(resultCode >= 0) { "群組 App 啟動失敗：$resultCode" }
        val dataDirectory = VEnvironment.getDataUserPackageDirectory(environmentId, packageName)
        persistRuntimeDiagnostics(group, app, binding, dataDirectory)
        Log.i(
            TAG,
            "group-app-start groupId=${group.id} environmentId=$environmentId " +
                "package=$packageName data=${dataDirectory.absolutePath}",
        )
        RuntimeLaunchResult.Started(
            packageName = packageName,
            processPrefix = "${appContext.packageName}:p",
            dataDirectory = dataDirectory.absolutePath,
        )
    }.getOrElse { error ->
        Log.e(TAG, "GroupApp launch failed for ${app.packageName}/${group.id}", error)
        RuntimeLaunchResult.Failed(error.message ?: error.javaClass.simpleName, error)
    }

    private fun requireHealthyEnvironment(group: Group): EnvironmentBinding {
        require(group.health == GroupHealth.HEALTHY) { "群組環境目前無法使用" }
        val binding = requireNotNull(group.environmentBinding) { "群組環境尚未建立" }
        check(environmentExists(binding)) { "群組環境已損毀" }
        return binding
    }

    private fun persistRuntimeDiagnostics(
        group: Group,
        app: GroupApp,
        binding: EnvironmentBinding,
        runtimeDataDirectory: File,
    ) {
        val groupData = File(
            appContext.filesDir,
            "groups/${group.id}/${FileGroupStore.DATA_DIRECTORY}",
        )
        check(groupData.isDirectory) { "找不到群組資料目錄" }
        val mappingFile = File(groupData, FileGroupStore.RUNTIME_METADATA)
        val properties = Properties().apply {
            setProperty("groupId", group.id)
            setProperty("environmentBindingId", binding.internalId.toString())
            setProperty(
                "runtimeDataDirectory.${app.packageName}",
                runtimeDataDirectory.absolutePath,
            )
        }
        FileOutputStream(mappingFile).use { output ->
            properties.store(output, "AppTwin Group runtime diagnostics")
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

    private fun prepareVirtualExternalStorage(environmentId: Int) {
        val directories = listOf(
            requireNotNull(
                VEnvironment.getVirtualStorageDir(appContext.packageName, environmentId),
            ) { "無法取得 virtual shared external storage" },
            requireNotNull(VEnvironment.getVirtualPrivateStorageDir(environmentId)) {
                "無法取得 virtual private external storage"
            },
        )
        directories.forEach { directory ->
            check(directory.isDirectory || directory.mkdirs()) {
                "無法建立 virtual external storage：${directory.path}"
            }
        }
    }

    private companion object {
        const val TAG = "AppTwinRuntime"
        fun environmentName(groupId: String): String = "AppTwin:group:$groupId"
    }
}

private fun PackageInfo.versionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
