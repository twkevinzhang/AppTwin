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
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import org.apptwin.groups.EnvironmentBinding
import org.apptwin.groups.Group
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupAppRemovalRuntime
import org.apptwin.groups.GroupEnvironmentRuntime
import org.apptwin.groups.GroupHealth
import org.apptwin.groups.RuntimeGroupAppRemovalResult
import org.apptwin.revision.ActiveRuntimeRevision
import org.apptwin.revision.ActiveRuntimeRevisionProvider
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.revision.PackageArtifactIdentity

sealed interface RuntimeLaunchResult {
    data class Started(
        val packageName: String,
        val processPrefix: String,
        val dataDirectory: String,
    ) : RuntimeLaunchResult

    data class Failed(val reason: String, val error: Throwable? = null) : RuntimeLaunchResult
}

/** The only adapter allowed to translate a Group environment into the engine's numeric user API. */
class VirtualRuntimeController internal constructor(
    context: Context,
    private val revisionProvider: ActiveRuntimeRevisionProvider,
    diagnosticsSink: RuntimeDiagnosticsSink,
) : GroupEnvironmentRuntime, GroupAppRemovalRuntime {
    constructor(context: Context) : this(
        context,
        AndroidPackageRevisionImporter(context),
        FileRuntimeDiagnosticsSink(context),
    )

    private val appContext = context.applicationContext
    private val launchRecorder = RuntimeLaunchRecorder(diagnosticsSink) { error ->
        Log.w(TAG, "Unable to persist runtime diagnostics", error)
    }

    override fun createEnvironment(groupId: String, groupName: String): EnvironmentBinding {
        val core = VirtualCore.get()
        core.waitForEngine()
        val user = requireNotNull(
            VUserManager.get().createUser(
                environmentName(groupId, groupName),
                0,
            ),
        ) { "無法建立群組環境" }
        Log.i(TAG, "group-environment-created groupId=$groupId environmentId=${user.id}")
        return EnvironmentBinding(user.id)
    }

    override fun findEnvironment(groupId: String): EnvironmentBinding? {
        VirtualCore.get().waitForEngine()
        val prefix = environmentPrefix(groupId)
        val matches = VUserManager.get().users.filter { user ->
            user.name == prefix || user.name.startsWith("$prefix|")
        }
        check(matches.size <= 1) { "群組存在多個隔離環境" }
        return matches.singleOrNull()?.id?.let(::EnvironmentBinding)
    }

    override fun environmentExists(binding: EnvironmentBinding): Boolean {
        VirtualCore.get().waitForEngine()
        return VUserManager.get().getUserInfo(binding.internalId) != null
    }

    fun syncEnvironmentLabel(group: Group) {
        val binding = group.environmentBinding ?: return
        val manager = VUserManager.get()
        val user = manager.getUserInfo(binding.internalId) ?: return
        val expected = environmentName(group.id, group.name)
        if (user.name != expected) manager.setUserName(binding.internalId, expected)
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
        val wasInstalled = core.isAppInstalledAsUser(binding.internalId, packageName)
        if (wasInstalled) {
            check(core.uninstallPackageAsUser(packageName, binding.internalId)) {
                "無法從群組移除 $packageName"
            }
        }
        check(!core.isAppInstalledAsUser(binding.internalId, packageName)) {
            "$packageName 仍存在於群組環境"
        }
        deleteGuestPrivateData(binding.internalId, packageName)
        Log.i(
            TAG,
            "group-app-removed package=$packageName environmentId=${binding.internalId}",
        )
        return if (wasInstalled) {
            RuntimeGroupAppRemovalResult.Removed
        } else {
            RuntimeGroupAppRemovalResult.AlreadyAbsent
        }
    }

    private fun deleteGuestPrivateData(environmentId: Int, packageName: String) {
        listOf(
            VEnvironment.getDataUserPackageDirectory(environmentId, packageName),
            VEnvironment.getDeDataUserPackageDirectory(environmentId, packageName),
            VEnvironment.getVirtualPrivateStorageDir(environmentId, packageName),
        ).forEach { directory ->
            check(!directory.exists() || directory.deleteRecursively()) {
                "無法刪除群組 App 私有資料：${directory.absolutePath}"
            }
        }
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
        val revision = requireNotNull(revisionProvider.activeRuntimeRevision(packageName)) {
            "沒有可啟動的 active revision"
        }
        prepareVirtualExternalStorage(environmentId)
        RuntimePackageSynchronizer(VirtualCorePackageGateway(core))
            .synchronize(revision, environmentId)
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
        Log.i(
            TAG,
            "group-app-start groupId=${group.id} environmentId=$environmentId " +
                "package=$packageName data=${dataDirectory.absolutePath}",
        )
        launchRecorder.record(
            RuntimeLaunchResult.Started(
                packageName = packageName,
                processPrefix = "${appContext.packageName}:p",
                dataDirectory = dataDirectory.absolutePath,
            ),
            RuntimeDiagnostics(
                groupId = group.id,
                environmentBindingId = binding.internalId,
                packageName = app.packageName,
                runtimeDataDirectory = dataDirectory.absolutePath,
            ),
        )
    }.getOrElse { error ->
        Log.e(TAG, "GroupApp launch failed for ${app.packageName}/${group.id}", error)
        RuntimeLaunchResult.Failed(error.message ?: error.javaClass.simpleName, error)
    }

    fun installAndLaunchIntent(
        group: Group,
        app: GroupApp,
        intent: Intent,
    ): RuntimeLaunchResult = runCatching {
        require(group.contains(app.packageName)) { "GroupApp does not belong to this Group" }
        require(intent.action == Intent.ACTION_VIEW) { "Only view intents may be routed" }
        require(intent.data?.scheme in setOf("http", "https")) { "Unsupported deep-link scheme" }
        val environmentId = requireHealthyEnvironment(group).internalId
        val packageName = app.packageName
        val core = VirtualCore.get()
        core.waitForEngine()
        val revision = requireNotNull(revisionProvider.activeRuntimeRevision(packageName)) {
            "沒有可啟動的 active revision"
        }
        prepareVirtualExternalStorage(environmentId)
        RuntimePackageSynchronizer(VirtualCorePackageGateway(core))
            .synchronize(revision, environmentId)
        markGuestCodeReadOnly(core, packageName)
        val routedIntent = Intent(intent)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resultCode = VActivityManager.get().startActivity(routedIntent, environmentId)
        check(resultCode >= 0) { "分身 App 無法開啟此連結：$resultCode" }
        val dataDirectory = VEnvironment.getDataUserPackageDirectory(environmentId, packageName)
        RuntimeLaunchResult.Started(packageName, "p$environmentId", dataDirectory.absolutePath)
    }.getOrElse { error ->
        RuntimeLaunchResult.Failed(error.message ?: error.javaClass.simpleName, error)
    }

    private fun requireHealthyEnvironment(group: Group): EnvironmentBinding {
        require(group.health == GroupHealth.HEALTHY) { "群組環境目前無法使用" }
        val binding = requireNotNull(group.environmentBinding) { "群組環境尚未建立" }
        check(environmentExists(binding)) { "群組環境已損毀" }
        return binding
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
        fun environmentPrefix(groupId: String): String = "AppTwin:group:$groupId"
        fun environmentName(groupId: String, groupName: String): String =
            "${environmentPrefix(groupId)}|$groupName"
    }

    private class VirtualCorePackageGateway(
        private val core: VirtualCore,
    ) : RuntimePackageGateway {
        override fun isInstalled(packageName: String): Boolean = core.isAppInstalled(packageName)

        override fun installedArtifactIdentity(packageName: String): PackageArtifactIdentity? {
            val installed = core.getInstalledAppInfo(packageName, 0) ?: return null
            val installedUsers = core.getPackageInstalledUsers(packageName)
            val packageInfo: PackageInfo = installedUsers.asSequence()
                .mapNotNull { userId ->
                    VPackageManager.get().getPackageInfo(packageName, 0, userId)
                }
                .firstOrNull()
                ?: return null
            return RuntimePackageArtifactReader.read(
                baseApk = File(installed.apkPath),
                splitNames = packageInfo.splitNames?.copyOf() ?: emptyArray(),
                splitCodePaths = installed.splitCodePaths?.copyOf() ?: emptyArray(),
                sha256 = ::sha256,
            )
        }

        override fun installOrUpdate(
            revision: ActiveRuntimeRevision,
            update: Boolean,
        ): RuntimePackageInstallResult {
            val flags = InstallStrategy.SKIP_DEX_OPT or if (update) {
                InstallStrategy.UPDATE_IF_EXIST
            } else {
                0
            }
            val result = core.installPackage(revision.directory.absolutePath, flags)
            return RuntimePackageInstallResult(result.isSuccess, result.error)
        }

        override fun isInstalledForUser(userId: Int, packageName: String): Boolean =
            core.isAppInstalledAsUser(userId, packageName)

        override fun installForUser(userId: Int, packageName: String): Boolean =
            core.installPackageAsUser(userId, packageName)

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(FileInputStream(file)).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

private fun PackageInfo.versionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
