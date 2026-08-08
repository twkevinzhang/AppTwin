package org.apptwin.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Environment
import android.util.Log
import com.lody.virtual.client.core.InstallStrategy
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.client.ipc.VPackageManager
import com.lody.virtual.os.VEnvironment
import com.lody.virtual.os.VirtualExternalStorageLayout
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
import org.apptwin.revision.RevisionImportResult

sealed interface RuntimeLaunchResult {
    data class Started(
        val packageName: String,
        val processPrefix: String,
        val dataDirectory: String,
    ) : RuntimeLaunchResult

    data class Failed(val reason: String, val error: Throwable? = null) : RuntimeLaunchResult
}

sealed interface GroupPreparationResult {
    data object Ready : GroupPreparationResult
    data class Failed(val reason: String, val error: Throwable? = null) : GroupPreparationResult
}

data class VirtualPackageSummary(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
)

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

    override fun copyPrivateAppData(
        source: EnvironmentBinding,
        destination: EnvironmentBinding,
        apps: List<GroupApp>,
    ) {
        require(source != destination) { "來源與目標群組環境不可相同" }
        apps.forEach { app ->
            val sourceDirectory = VEnvironment.getDataUserPackageDirectory(
                source.internalId,
                app.packageName,
            )
            if (!sourceDirectory.isDirectory) return@forEach
            VActivityManager.get().killAppByPkg(app.packageName, source.internalId)
            val destinationDirectory = VEnvironment.getDataUserPackageDirectory(
                destination.internalId,
                app.packageName,
            )
            if (destinationDirectory.exists()) {
                check(destinationDirectory.deleteRecursively()) {
                    "無法清除新群組的空白 App 資料：${app.packageName}"
                }
            }
            check(
                destinationDirectory.parentFile?.isDirectory == true ||
                    destinationDirectory.parentFile?.mkdirs() == true,
            ) { "無法建立群組 App 資料目錄：${app.packageName}" }
            check(sourceDirectory.copyRecursively(destinationDirectory, overwrite = true)) {
                "無法複製群組 App 資料：${app.packageName}"
            }
            Log.i(
                TAG,
                "group-private-data-copied package=${app.packageName} " +
                    "sourceEnvironment=${source.internalId} " +
                    "destinationEnvironment=${destination.internalId}",
            )
        }
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

    fun prepareGroup(group: Group): GroupPreparationResult = runCatching {
        val core = VirtualCore.get()
        core.waitForEngine()
        val binding = requireHealthyEnvironment(group)
        ensurePackages(GROUP_GOOGLE_PACKAGES, core, binding.internalId)
        GroupPreparationResult.Ready
    }.getOrElse { error ->
        Log.e(TAG, "Group environment preparation failed for ${group.id}", error)
        GroupPreparationResult.Failed(error.message ?: error.javaClass.simpleName, error)
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
        ensurePackages(GROUP_GOOGLE_PACKAGES, core, environmentId)
        ensurePackages(GroupAppRuntimeSupport.requiredPackages(packageName), core, environmentId)
        if (packageName == GroupAppRuntimeSupport.MAPS_PACKAGE) {
            runCatching { GoogleRuntimeBootstrap.prewarmCheckin(environmentId) }
                .onFailure { error ->
                    Log.w(TAG, "google-checkin-prewarm-skipped environmentId=$environmentId", error)
                }
        }
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

    /** Opens the Play Store that belongs to this Group's virtual user. */
    fun launchPlayStore(group: Group): RuntimeLaunchResult = runCatching {
        val binding = requireHealthyEnvironment(group)
        val environmentId = binding.internalId
        val core = VirtualCore.get()
        core.waitForEngine()
        ensurePackages(GROUP_GOOGLE_PACKAGES, core, environmentId)
        runCatching { GoogleRuntimeBootstrap.prewarmCheckin(environmentId) }
            .onFailure { error ->
                // A newly created GMS process can miss the runtime's first service-bind
                // deadline while Chimera modules initialize. Play and its account flow
                // retry Checkin themselves, so this best-effort prewarm must not make the
                // Group's store unusable.
                Log.w(TAG, "google-checkin-prewarm-skipped environmentId=$environmentId", error)
            }
        prepareVirtualExternalStorage(environmentId)

        val contract = GroupPlayStoreLaunchContract.launcher
        check(core.isAppInstalledAsUser(environmentId, contract.packageName)) {
            "Play 商店尚未加入群組環境"
        }
        val launchIntent = requireNotNull(
            core.getLaunchIntent(contract.packageName, environmentId),
        ) { "找不到 Play 商店啟動入口" }
            .addFlags(contract.flags)
        val resultCode = VActivityManager.get().startActivity(launchIntent, environmentId)
        check(resultCode >= 0) { "Play 商店啟動失敗：$resultCode" }

        val dataDirectory = VEnvironment.getDataUserPackageDirectory(
            environmentId,
            contract.packageName,
        )
        Log.i(
            TAG,
            "group-play-store-start groupId=${group.id} environmentId=$environmentId " +
                "package=${contract.packageName} data=${dataDirectory.absolutePath}",
        )
        RuntimeLaunchResult.Started(
            packageName = contract.packageName,
            processPrefix = "${appContext.packageName}:p",
            dataDirectory = dataDirectory.absolutePath,
        )
    }.getOrElse { error ->
        Log.e(TAG, "Group Play Store launch failed for ${group.id}", error)
        RuntimeLaunchResult.Failed(error.message ?: error.javaClass.simpleName, error)
    }

    /** Returns the user-visible packages currently installed in this Group's virtual user. */
    fun installedPackages(group: Group): Result<List<VirtualPackageSummary>> = runCatching {
        val binding = requireHealthyEnvironment(group)
        val core = VirtualCore.get()
        core.waitForEngine()
        VPackageManager.get().getInstalledPackages(0, binding.internalId)
            .asSequence()
            .filter { info ->
                GroupVirtualPackageInventory.shouldExpose(
                    packageName = info.packageName,
                    hostPackageName = appContext.packageName,
                )
            }
            .filter { info -> core.getLaunchIntent(info.packageName, binding.internalId) != null }
            .map { info ->
                VirtualPackageSummary(
                    packageName = info.packageName,
                    label = runCatching {
                        info.applicationInfo?.loadLabel(appContext.packageManager)?.toString()
                    }.getOrNull()
                        ?.takeIf(String::isNotBlank)
                        ?: info.packageName,
                    versionName = info.versionName.orEmpty(),
                    versionCode = info.versionCodeCompat(),
                )
            }
            .sortedBy(VirtualPackageSummary::packageName)
            .toList()
    }

    /** Launches code already installed by this Group's Play Store without importing host code. */
    fun launchInstalledPackage(group: Group, packageName: String): RuntimeLaunchResult =
        runCatching {
            require(group.contains(packageName)) { "GroupApp does not belong to this Group" }
            require(
                GroupVirtualPackageInventory.shouldExpose(packageName, appContext.packageName),
            ) { "群組服務套件不可從一般 App 入口啟動" }
            val binding = requireHealthyEnvironment(group)
            val environmentId = binding.internalId
            val core = VirtualCore.get()
            core.waitForEngine()
            check(core.isAppInstalledAsUser(environmentId, packageName)) {
                "$packageName 已不在這個群組中"
            }
            val launchIntent = requireNotNull(core.getLaunchIntent(packageName, environmentId)) {
                "找不到群組 App 啟動入口"
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resultCode = VActivityManager.get().startActivity(launchIntent, environmentId)
            check(resultCode >= 0) { "群組 App 啟動失敗：$resultCode" }
            val dataDirectory = VEnvironment.getDataUserPackageDirectory(environmentId, packageName)
            val app = requireNotNull(group.apps.firstOrNull { it.packageName == packageName })
            persistRuntimeDiagnostics(group, app, binding, dataDirectory)
            RuntimeLaunchResult.Started(
                packageName = packageName,
                processPrefix = "${appContext.packageName}:p",
                dataDirectory = dataDirectory.absolutePath,
            )
        }.getOrElse { error ->
            Log.e(TAG, "Installed GroupApp launch failed for $packageName/${group.id}", error)
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

    private fun ensurePackages(
        packages: List<String>,
        core: VirtualCore,
        environmentId: Int,
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
                core.isAppInstalledAsUser(environmentId, dependency) ||
                    core.installPackageAsUser(environmentId, dependency),
            ) { "Google 相依套件未加入群組環境：$dependency" }
            Log.i(
                TAG,
                "google-runtime-dependency-ready package=$dependency environmentId=$environmentId",
            )
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
        val externalRoot = Environment.getExternalStorageDirectory() ?: return
        val directories = listOf(
            VirtualExternalStorageLayout.sharedStorageForUser(
                externalRoot,
                appContext.packageName,
                environmentId,
            ),
            VirtualExternalStorageLayout.privateStorageForUser(
                externalRoot,
                appContext.packageName,
                environmentId,
            ),
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
        val GROUP_GOOGLE_PACKAGES = GroupAppRuntimeSupport.googlePackages
    }
}

private fun PackageInfo.versionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
