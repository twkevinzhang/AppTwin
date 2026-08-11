package org.apptwin.runtime

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lody.virtual.client.core.InstallStrategy
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.client.ipc.VPackageManager
import com.lody.virtual.client.hook.proxies.keystore.KeystoreAliasPolicy
import com.lody.virtual.os.VEnvironment
import com.lody.virtual.os.VUserManager
import com.lody.virtual.remote.PreparedActivityLaunch
import com.lody.virtual.server.pm.VUserManagerService
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.security.KeyStore
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.apptwin.MainActivity
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activityLauncher = HostActivityLaunchAdapter(
        prepareActivity = { intent, expectedPackage, userId ->
            VActivityManager.get().prepareActivityLaunch(intent, expectedPackage, userId)
        },
        resumedHost = mainActivityLaunchHosts::current,
        startActivity = { activity, intent -> activity.startActivity(intent) },
        moveTaskToFront = { activity, taskId ->
            val activityManager =
                activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.moveTaskToFront(taskId, 0)
        },
        dispatchToMain = { runnable -> mainHandler.post(runnable) },
        isMainThread = { Looper.myLooper() == Looper.getMainLooper() },
        awaitAcknowledgement = { launchId, timeoutMs ->
            VActivityManager.get().awaitPreparedActivityLaunch(launchId, timeoutMs)
        },
        hasExpectedGuestActivity = ::hasExpectedGuestActivity,
        cancelAcknowledgement = { launchId ->
            VActivityManager.get().cancelPreparedActivityLaunch(launchId)
        },
    )

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
        deleteGuestKeystoreEntries(binding.internalId, packageName)
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
            VUserManagerService.removeDirectoryRecursiveOrThrow(directory)
        }
    }

    private fun deleteGuestKeystoreEntries(environmentId: Int, packageName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val ownedAliases = KeystoreAliasPolicy.ownedAliases(
            packageName,
            environmentId,
            Collections.list(keyStore.aliases()),
        )
        ownedAliases.forEach(keyStore::deleteEntry)
        if (ownedAliases.isNotEmpty()) {
            Log.i(
                TAG,
                "group-keystore-cleared package=$packageName " +
                    "environmentId=$environmentId count=${ownedAliases.size}",
            )
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
        val launch = activityLauncher.launch(launchIntent, packageName, environmentId)
        check(launch.isSuccess) { "群組 App 啟動失敗：${launch.failureReason}" }
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
        val launch = activityLauncher.launch(routedIntent, packageName, environmentId)
        check(launch.isSuccess) { "分身 App 無法開啟此連結：${launch.failureReason}" }
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

    private fun hasExpectedGuestActivity(
        expectedPackage: String,
        environmentId: Int,
        prepared: PreparedActivityLaunch,
    ): Boolean {
        val activityManager =
            appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val physicalTask = activityManager.getRunningTasks(1).firstOrNull() ?: return false
        @Suppress("DEPRECATION")
        val taskId = physicalTask.id
        val virtualTask = VActivityManager.get().getTaskInfo(taskId) ?: return false
        return matchesExpectedGuestActivity(
            expectedPackage = expectedPackage,
            expectedEnvironmentId = environmentId,
            taskEnvironmentId = virtualTask.userId,
            topActivityPackage = virtualTask.topActivity?.packageName,
            preparedTaskId = prepared.taskId,
            preparedLaunchId = requireNotNull(prepared.launchId),
            taskId = taskId,
            taskPreparedLaunchId = virtualTask.preparedLaunchId,
        )
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

internal class ResumedHostRegistry<Host : Any> {
    @Volatile
    private var resumedHost: Host? = null

    fun onResumed(host: Host) {
        resumedHost = host
    }

    fun onPaused(host: Host) {
        if (resumedHost === host) resumedHost = null
    }

    fun current(): Host? = resumedHost
}

internal val mainActivityLaunchHosts = ResumedHostRegistry<MainActivity>()

/**
 * Executes only Android task operations on the visible host's main thread. Virtual task policy
 * and acknowledgement stay in the engine call made by the serialized IO dispatcher.
 */
internal class HostActivityLaunchAdapter<Host : Any>(
    private val prepareActivity: (Intent, String, Int) -> PreparedActivityLaunch,
    private val resumedHost: () -> Host?,
    private val startActivity: (Host, Intent) -> Unit,
    private val moveTaskToFront: (Host, Int) -> Unit,
    private val dispatchToMain: (Runnable) -> Boolean,
    private val isMainThread: () -> Boolean,
    private val awaitAcknowledgement: (String, Long) -> Boolean,
    private val hasExpectedGuestActivity:
        (String, Int, PreparedActivityLaunch) -> Boolean = { _, _, _ -> false },
    private val cancelAcknowledgement: (String) -> Unit,
    private val acknowledgementTimeoutMs: Long = 5_000L,
    private val activityConfirmationTimeoutMs: Long = 1_000L,
    private val activityConfirmationPollMs: Long = 50L,
    private val monotonicTimeMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val pause: (Long) -> Unit = Thread::sleep,
    private val mainDispatchTimeoutMs: Long = 5_000L,
) {
    fun launch(intent: Intent, expectedPackage: String, userId: Int): PreparedActivityLaunch {
        if (isMainThread()) {
            return PreparedActivityLaunch.failure("Guest launch must run off the main thread")
        }

        val host = callOnMain(resumedHost).getOrElse { error ->
            return PreparedActivityLaunch.failure(
                error.message ?: "Unable to obtain the resumed AppTwin activity",
            )
        } ?: return PreparedActivityLaunch.failure(
            "AppTwin must be resumed to launch a guest activity",
        )

        val prepared = prepareActivity(Intent(intent), expectedPackage, userId)
        if (!prepared.isSuccess) return prepared

        val launchId = requireNotNull(prepared.launchId)
        var acknowledged = false
        val result = try {
            val startError = callOnMain {
                check(resumedHost() === host) {
                    "AppTwin activity is no longer resumed"
                }
                if (prepared.isReused) {
                    moveTaskToFront(host, prepared.taskId)
                } else {
                    startActivity(host, Intent(requireNotNull(prepared.intent)))
                }
            }.exceptionOrNull()
            if (startError != null) {
                PreparedActivityLaunch.failure(
                    startError.message ?: "Unable to start guest activity",
                )
            } else {
                acknowledged = awaitAcknowledgement(launchId, acknowledgementTimeoutMs)
                val guestActivityStarted = acknowledged || awaitExpectedGuestActivity(
                    expectedPackage,
                    userId,
                    prepared,
                )
                if (guestActivityStarted) {
                    prepared
                } else {
                    PreparedActivityLaunch.failure(
                        "Expected Group activity did not resume",
                    )
                }
            }
        } catch (error: Throwable) {
            PreparedActivityLaunch.failure(
                error.message ?: "Unable to start guest activity",
            )
        }
        if (!acknowledged) {
            try {
                cancelAcknowledgement(launchId)
            } catch (_: Throwable) {
                // Cleanup must not replace the launch failure.
            }
        }
        return result
    }

    private fun awaitExpectedGuestActivity(
        expectedPackage: String,
        userId: Int,
        prepared: PreparedActivityLaunch,
    ): Boolean {
        // A splash activity can destroy the prepared token before its successor reaches onResume.
        // Confirm only the exact foreground task produced by this prepared launch.
        require(activityConfirmationTimeoutMs >= 0L)
        require(activityConfirmationPollMs > 0L)
        val deadline = monotonicTimeMs() + activityConfirmationTimeoutMs
        while (true) {
            val confirmed = try {
                hasExpectedGuestActivity(expectedPackage, userId, prepared)
            } catch (_: Exception) {
                return false
            }
            if (confirmed) return true
            val remainingMs = deadline - monotonicTimeMs()
            if (remainingMs <= 0L) return false
            try {
                pause(minOf(activityConfirmationPollMs, remainingMs))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    private fun <T> callOnMain(block: () -> T): Result<T> {
        if (isMainThread()) return runCatching(block)

        val pending = AtomicBoolean(true)
        val completed = CountDownLatch(1)
        var outcome: Result<T>? = null
        val accepted = runCatching {
            dispatchToMain(
                Runnable {
                    if (!pending.compareAndSet(true, false)) return@Runnable
                    try {
                        outcome = runCatching(block)
                    } finally {
                        completed.countDown()
                    }
                },
            )
        }.getOrElse { error ->
            pending.set(false)
            return Result.failure(error)
        }
        if (!accepted) {
            pending.set(false)
            return Result.failure(IllegalStateException("Unable to dispatch to the main thread"))
        }

        try {
            if (!completed.await(mainDispatchTimeoutMs, TimeUnit.MILLISECONDS)) {
                pending.compareAndSet(true, false)
                return Result.failure(IllegalStateException("Timed out waiting for the main thread"))
            }
        } catch (error: InterruptedException) {
            pending.compareAndSet(true, false)
            Thread.currentThread().interrupt()
            return Result.failure(error)
        }
        return checkNotNull(outcome)
    }
}

internal fun matchesExpectedGuestActivity(
    expectedPackage: String,
    expectedEnvironmentId: Int,
    taskEnvironmentId: Int,
    topActivityPackage: String?,
    preparedTaskId: Int,
    preparedLaunchId: String,
    taskId: Int,
    taskPreparedLaunchId: String?,
): Boolean {
    val isPreparedTask = if (preparedTaskId >= 0) {
        taskId == preparedTaskId
    } else {
        taskPreparedLaunchId == preparedLaunchId
    }
    return isPreparedTask &&
        taskEnvironmentId == expectedEnvironmentId &&
        topActivityPackage == expectedPackage
}

private fun PackageInfo.versionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
