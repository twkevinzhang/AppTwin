package org.apptwin.gms.runtime

import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.os.VEnvironment
import com.lody.virtual.os.VirtualExternalStorageLayout
import com.lody.virtual.remote.TrustedPackageProvenance
import android.content.pm.PackageInfo
import android.os.Build
import java.io.File
import java.nio.file.Path

fun interface GmsGroupBindingResolver {
    /** Returns null for an unknown/deleted Group. Android's real user 0 is never a valid binding. */
    fun virtualUserId(groupId: String): Int?
}

sealed interface RuntimeEngineResult {
    data object Success : RuntimeEngineResult
    data class Retryable(val code: String) : RuntimeEngineResult
    data class Rejected(val code: String) : RuntimeEngineResult
}

interface GmsVirtualRuntimeGateway {
    fun isVirtualUserPresent(userId: Int): Boolean
    fun isInstalled(userId: Int): Boolean
    fun installedVersionCode(userId: Int): Long?
    fun hasPrivateState(userId: Int): Boolean
    fun hasBackgroundOwnership(userId: Int): Boolean
    fun installTrusted(
        userId: Int,
        apk: Path,
        provenance: TrustedPackageProvenance,
    ): RuntimeEngineResult
    fun preparePrivateState(userId: Int): RuntimeEngineResult
    fun suspendPreservingData(userId: Int): RuntimeEngineResult
    fun uninstallAndClear(userId: Int): RuntimeEngineResult
}

/** Thin production gateway. It never includes engine errors or filesystem paths in result codes. */
class VirtualCoreGmsRuntimeGateway(
    private val core: VirtualCore = VirtualCore.get(),
) : GmsVirtualRuntimeGateway {
    override fun isVirtualUserPresent(userId: Int): Boolean = runCatching {
        // A valid virtual user can be observed through package state even before GMS is installed.
        com.lody.virtual.os.VUserManager.get().getUserInfo(userId) != null
    }.getOrDefault(false)

    override fun isInstalled(userId: Int): Boolean =
        TRUSTED_PACKAGES.all { core.isAppInstalledAsUser(userId, it) }

    override fun installedVersionCode(userId: Int): Long? = runCatching {
        val gmsVersion = core.getInstalledAppInfo(GMS_PACKAGE, 0)
            ?.getPackageInfo(userId)?.versionCodeCompat()
        val companionVersion = core.getInstalledAppInfo(COMPANION_PACKAGE, 0)
            ?.getPackageInfo(userId)?.versionCodeCompat()
        if (companionVersion == COMPANION_VERSION_CODE) gmsVersion else null
    }.getOrNull()

    override fun hasPrivateState(userId: Int): Boolean {
        val externalRoot = core.context.getExternalFilesDir(null) ?: return true
        return TRUSTED_PACKAGES.any { packageName ->
            privateDirectoriesForObservation(userId, packageName, externalRoot).any(File::exists)
        }
    }

    override fun hasBackgroundOwnership(userId: Int): Boolean =
        core.hasTrustedGmsBackgroundStateForUser(userId)

    override fun installTrusted(
        userId: Int,
        apk: Path,
        provenance: TrustedPackageProvenance,
    ): RuntimeEngineResult = runCatching {
        val result = core.installTrustedPackageForUser(apk.toString(), 0, userId, provenance)
        if (result.isSuccess) RuntimeEngineResult.Success else {
            RuntimeEngineResult.Retryable("ENGINE_INSTALL_RETRYABLE")
        }
    }.getOrElse { RuntimeEngineResult.Retryable("ENGINE_INSTALL_RETRYABLE") }

    override fun preparePrivateState(userId: Int): RuntimeEngineResult = runCatching {
        val prepared = TRUSTED_PACKAGES.flatMap { privateDirectoriesRequired(userId, it) }
            .all { it.isDirectory || it.mkdirs() }
        if (prepared) RuntimeEngineResult.Success else {
            RuntimeEngineResult.Retryable("PRIVATE_STATE_PREPARE_RETRYABLE")
        }
    }.getOrElse { RuntimeEngineResult.Retryable("PRIVATE_STATE_PREPARE_RETRYABLE") }

    override fun suspendPreservingData(userId: Int): RuntimeEngineResult = runCatching {
        if (core.suspendTrustedGmsPackageForUser(userId)) {
            RuntimeEngineResult.Success
        } else {
            RuntimeEngineResult.Retryable("ENGINE_SUSPEND_RETRYABLE")
        }
    }.getOrElse { RuntimeEngineResult.Retryable("ENGINE_SUSPEND_RETRYABLE") }

    override fun uninstallAndClear(userId: Int): RuntimeEngineResult = runCatching {
        // The server operation is intentionally idempotent when the installed flag is already
        // false: it must still repair jobs, notifications, PendingIntents and AccountManager state.
        val removals = TRUSTED_PACKAGES.map { packageName ->
            core.clearTrustedPackageStateForUser(packageName, userId)
        }
        if (removals.any { !it }) {
            RuntimeEngineResult.Retryable("ENGINE_UNINSTALL_RETRYABLE")
        } else if (hasPrivateState(userId) || hasBackgroundOwnership(userId)) {
            RuntimeEngineResult.Retryable("PRIVATE_STATE_DELETE_RETRYABLE")
        } else {
            RuntimeEngineResult.Success
        }
    }.getOrElse { RuntimeEngineResult.Retryable("ENGINE_UNINSTALL_RETRYABLE") }

    /** Uses non-creating canonical paths; unavailable external storage is never treated as clean. */
    private fun privateDirectoriesRequired(userId: Int, packageName: String): List<File> {
        val externalRoot = core.context.getExternalFilesDir(null)
            ?: error("External private storage unavailable")
        return privateDirectoriesForObservation(userId, packageName, externalRoot)
    }

    /** Observes the same layout without calling ensureCreated helpers and creating false state. */
    private fun privateDirectoriesForObservation(
        userId: Int,
        packageName: String,
        externalRoot: File,
    ): List<File> {
        val ce = File(VEnvironment.getUserSystemDirectory(userId), packageName)
        val de = File(VEnvironment.getDeUserSystemDirectory(userId), packageName)
        val private = File(
            VirtualExternalStorageLayout.privateStorageForUser(externalRoot, userId),
            packageName,
        )
        return listOf(ce, de, private)
    }

    private companion object {
        const val GMS_PACKAGE = "com.google.android.gms"
        const val COMPANION_PACKAGE = "com.android.vending"
        const val COMPANION_VERSION_CODE = 84_022_630L
        val TRUSTED_PACKAGES = listOf(GMS_PACKAGE, COMPANION_PACKAGE)
    }
}

private fun PackageInfo.versionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
