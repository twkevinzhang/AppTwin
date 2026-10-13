package org.apptwin

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.client.ipc.VPackageManager
import com.lody.virtual.helper.compat.PlatformPermissionCompatibility
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupHealth
import org.apptwin.permissions.ClonePermissionCategory
import org.apptwin.permissions.ClonePermissionRequirement
import org.apptwin.permissions.ClonePermissionTarget
import org.apptwin.permissions.ClonePermissionVirtualScope
import org.apptwin.runtime.VirtualRuntimeController

/** Android implementation of the ClonePermissionOperations feature boundary. */
internal class AndroidClonePermissionOperations(
    private val application: Application,
    private val groupStore: FileGroupStore,
    private val runtimeController: VirtualRuntimeController,
) : ClonePermissionOperations {
    override suspend fun setClonePermission(
        item: GroupAppItem,
        permission: String,
        granted: Boolean,
    ): Boolean {
        if (permission !in SUPPORTED_RUNTIME_PERMISSIONS) return false
        val group = groupStore.find(item.groupId) ?: return false
        if (group.health != GroupHealth.HEALTHY) return false
        val member = group.apps.firstOrNull { it.packageName == item.app.packageName } ?: return false
        val userId = group.environmentBinding?.internalId ?: return false
        if (PlatformPermissionCompatibility.isUnavailableOnPlatform(application.packageManager, permission)) {
            // Platform-exempt grants are idempotent and cannot revoke or restart the clone.
            return granted && VPackageManager.get().setRuntimePermissionGranted(
                permission, item.app.packageName, userId, true,
            )
        }
        runtimeController.preparePackageForPermissions(group, member)
        val changed = VPackageManager.get().setRuntimePermissionGranted(
            permission,
            item.app.packageName,
            userId,
            granted,
        )
        // Native media path grants are installed at guest process setup. Restart on either
        // media transition so provider and direct-path access reflect the same decision.
        if (changed && (!granted || permission in MEDIA_PROCESS_PERMISSIONS)) {
            VActivityManager.get().killAppByPkg(item.app.packageName, userId)
        }
        return changed
    }

    internal fun virtualPermissionGranted(permission: String, packageName: String, userId: Int): Boolean =
        runCatching {
            VPackageManager.get().checkPermission(permission, packageName, userId) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    internal fun readClonePermissionRequirements(
        groups: List<Group>,
        entries: List<org.apptwin.revision.InstalledAppEntry>,
        warnings: MutableList<String>,
    ): List<ClonePermissionRequirement> {
        val labels = entries.associate { it.packageName to it.label }
        val hostPermissions = hostRequestedPermissions()
        return buildList {
            groups.forEach groupLoop@ { group ->
                val userId = group.environmentBinding?.internalId ?: return@groupLoop
                group.apps.forEach appLoop@ { app ->
                    val packageInfo = runCatching {
                        VPackageManager.get().getPackageInfo(
                            app.packageName,
                            PackageManager.GET_PERMISSIONS,
                            userId,
                        )
                    }.getOrNull() ?: sourcePermissionPackageInfo(app.packageName)
                    if (packageInfo == null) {
                        addPermissionWarning(warnings, group, app, "虛擬 package 不存在")
                        return@appLoop
                    }
                    val target = ClonePermissionTarget(
                        groupId = group.id,
                        groupName = group.name,
                        packageName = app.packageName,
                        appLabel = labels[app.packageName] ?: app.packageName,
                    )
                    packageInfo.requestedPermissions.orEmpty().distinct().forEach { permission ->
                        add(permissionRequirement(permission, target, hostPermissions))
                    }
                }
            }
        }
    }

    private fun permissionRequirement(
        permission: String,
        target: ClonePermissionTarget,
        hostPermissions: Set<String>,
    ): ClonePermissionRequirement {
        val platformExempt = PlatformPermissionCompatibility.isUnavailableOnPlatform(application.packageManager, permission)
        val hostDeclared = permission in hostPermissions
        val hostGranted = application.checkSelfPermission(permission) ==
            PackageManager.PERMISSION_GRANTED
        val permissionInfo = runCatching {
            application.packageManager.getPermissionInfo(permission, 0)
        }.getOrNull()
        val label = permissionInfo?.loadLabel(application.packageManager)?.toString()
            ?.takeIf(String::isNotBlank)
            ?: permission.substringAfterLast('.')
        val special = permission in SPECIAL_PERMISSIONS
        val baseProtection = permissionInfo?.protectionLevel
            ?.and(PermissionInfo.PROTECTION_MASK_BASE)
        val category = when {
            platformExempt -> ClonePermissionCategory.AUTOMATIC
            !hostDeclared && !hostGranted -> ClonePermissionCategory.UNSUPPORTED
            special -> ClonePermissionCategory.SPECIAL
            baseProtection == PermissionInfo.PROTECTION_DANGEROUS ->
                ClonePermissionCategory.RUNTIME
            baseProtection == PermissionInfo.PROTECTION_NORMAL ->
                ClonePermissionCategory.AUTOMATIC
            hostGranted -> ClonePermissionCategory.AUTOMATIC
            else -> ClonePermissionCategory.UNSUPPORTED
        }
        val effectiveGranted = when (category) {
            ClonePermissionCategory.SPECIAL -> specialPermissionGranted(permission)
            ClonePermissionCategory.AUTOMATIC -> true
            ClonePermissionCategory.RUNTIME -> hostGranted
            ClonePermissionCategory.UNSUPPORTED -> false
        }
        val virtualScope = when {
            platformExempt -> ClonePermissionVirtualScope.PLATFORM_EXEMPT
            category == ClonePermissionCategory.UNSUPPORTED ->
                ClonePermissionVirtualScope.NOT_SUPPORTED
            permission in SUPPORTED_RUNTIME_PERMISSIONS ->
                ClonePermissionVirtualScope.SPACE_SCOPED
            else -> ClonePermissionVirtualScope.HOST_SHARED
        }
        return ClonePermissionRequirement(
            permission = permission,
            target = target,
            label = label,
            category = category,
            virtualScope = virtualScope,
            hostGranted = effectiveGranted,
            canRequestRuntime = category == ClonePermissionCategory.RUNTIME && hostDeclared &&
                !(permission == Manifest.permission.ACCESS_BACKGROUND_LOCATION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R),
        )
    }

    private fun hostRequestedPermissions(): Set<String> = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.packageManager.getPackageInfo(
                application.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            application.packageManager.getPackageInfo(
                application.packageName,
                PackageManager.GET_PERMISSIONS,
            )
        }
        info.requestedPermissions.orEmpty().toSet()
    }.getOrDefault(emptySet())

    private fun sourcePermissionPackageInfo(packageName: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            application.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_PERMISSIONS,
            )
        }
    }.getOrNull()

    @SuppressLint("InlinedApi")
    private fun specialPermissionGranted(permission: String): Boolean = when (permission) {
        Manifest.permission.MANAGE_EXTERNAL_STORAGE ->
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        Manifest.permission.SYSTEM_ALERT_WINDOW -> Settings.canDrawOverlays(application)
        Manifest.permission.WRITE_SETTINGS -> Settings.System.canWrite(application)
        Manifest.permission.REQUEST_INSTALL_PACKAGES ->
            application.packageManager.canRequestPackageInstalls()
        Manifest.permission.SCHEDULE_EXACT_ALARM,
        Manifest.permission.USE_EXACT_ALARM,
        -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            application.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        Manifest.permission.PACKAGE_USAGE_STATS -> {
            val appOps = application.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    application.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    application.packageName,
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        }
        else -> false
    }

    private fun addPermissionWarning(
        warnings: MutableList<String>,
        group: Group,
        app: GroupApp,
        reason: String,
    ) {
        val warning = "Group ${group.id}/${app.packageName} 權限無法讀取：$reason"
        if (warning !in warnings) warnings += warning
    }

    @SuppressLint("InlinedApi")
    companion object {
        val MEDIA_PROCESS_PERMISSIONS = setOf(
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        )
        val SUPPORTED_RUNTIME_PERMISSIONS = setOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE,
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
        )
        val SPECIAL_PERMISSIONS = setOf(
            Manifest.permission.MANAGE_EXTERNAL_STORAGE,
            Manifest.permission.SYSTEM_ALERT_WINDOW,
            Manifest.permission.WRITE_SETTINGS,
            Manifest.permission.REQUEST_INSTALL_PACKAGES,
            Manifest.permission.SCHEDULE_EXACT_ALARM,
            Manifest.permission.USE_EXACT_ALARM,
            Manifest.permission.PACKAGE_USAGE_STATS,
        )
    }
}
