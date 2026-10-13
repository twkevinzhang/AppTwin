package org.apptwin

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.lody.virtual.client.ipc.VPackageManager
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupAppRemovalCoordinator
import org.apptwin.groups.GroupAppRemovalResult
import org.apptwin.groups.GroupAppState
import org.apptwin.groups.GroupHealth
import org.apptwin.operations.OperationKind
import org.apptwin.operations.OperationTarget
import org.apptwin.operations.OperationTracker
import org.apptwin.repair.ExecuteRepairUseCase
import org.apptwin.repair.RepairAction
import org.apptwin.repair.RepairExecutionResult
import org.apptwin.repair.RepairExecutor
import org.apptwin.repair.RepairIssueCode
import org.apptwin.repair.RepairPreviewPolicy
import org.apptwin.repair.RepairTarget
import org.apptwin.repair.RepairTargetKind
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.revision.RevisionImportResult
import org.apptwin.runtime.RuntimeLaunchResult
import org.apptwin.runtime.VirtualRuntimeController
import org.apptwin.usecases.AddCloneAppResult
import org.apptwin.usecases.AddCloneAppUseCase
import org.apptwin.usecases.ClearCloneStorageResult
import org.apptwin.usecases.ClearCloneStorageUseCase
import org.apptwin.usecases.CloneLaunchFailure
import org.apptwin.usecases.CloneLaunchRuntime
import org.apptwin.usecases.ClonePreparationRejection
import org.apptwin.usecases.CloneRuntimeLaunchResult
import org.apptwin.usecases.CloneSourceCurrentVerifier
import org.apptwin.usecases.CloneSourcePreparationResult
import org.apptwin.usecases.CloneSourcePreparer
import org.apptwin.usecases.LaunchCloneAppResult
import org.apptwin.usecases.LaunchCloneAppUseCase

/** Android implementation of the CloneOperations feature boundary. */
internal class AndroidCloneOperations(
    private val application: Application,
    private val groupStore: FileGroupStore,
    private val importer: AndroidPackageRevisionImporter,
    private val runtimeController: VirtualRuntimeController,
    private val shortcutPublisher: GroupAppShortcutPublisher,
    private val operationTracker: OperationTracker,
    private val appRemoval: GroupAppRemovalCoordinator,
) : CloneOperations {
    private val sourcePreparer = CloneSourcePreparer(::prepareCloneSource)
    private val addClone = AddCloneAppUseCase(
        store = groupStore,
        source = sourcePreparer,
        operations = operationTracker,
    )
    private val clearCloneStorage = ClearCloneStorageUseCase(
        store = groupStore,
        runtime = runtimeController::clearAppStorage,
    )

    override suspend fun addAppToGroup(groupId: String, packageName: String): Group {
        return when (val result = addClone.execute(groupId, packageName)) {
            is AddCloneAppResult.Added -> result.space
            AddCloneAppResult.SpaceNotFound -> error("找不到這個分身空間")
            AddCloneAppResult.SpaceUnavailable -> error("這個分身空間目前無法加入 App")
            AddCloneAppResult.AlreadyPresent -> error("$packageName 已存在於這個分身空間")
            AddCloneAppResult.SourceMissing -> error("手機上的原始 App 已移除")
            is AddCloneAppResult.Rejected -> error("無法同步：${result.reason}")
            is AddCloneAppResult.Failed -> throw result.error
        }
    }

    override suspend fun launchGroupApp(item: GroupAppItem): RuntimeLaunchResult =
        launchClone(
            item,
            pureRuntimeLaunch = { group, app ->
                runtimeController.installAndLaunch(group, app, allowPackageRepair = false)
            },
            runtimeLaunch = { group, app -> runtimeController.installAndLaunch(group, app) },
        )

    override suspend fun uninstallGroupApp(item: GroupAppItem): GroupAppRemovalResult {
        val current = groupStore.find(item.groupId)
        val currentApp = current?.apps?.firstOrNull {
            it.packageName == item.app.packageName &&
                it.addedAtEpochMillis == item.app.addedAtEpochMillis
        }
        if (current == null || currentApp == null) return GroupAppRemovalResult.AlreadyAbsent
        if (current.health != GroupHealth.HEALTHY) {
            val error = IllegalStateException("「${item.groupName}」目前無法解除安裝 App")
            return GroupAppRemovalResult.Failed(requireNotNull(error.message), error)
        }
        return appRemoval.remove(item.groupId, item.app.packageName).also { result ->
            if (result is GroupAppRemovalResult.Succeeded) {
                shortcutPublisher.disable(item.groupId, item.app.packageName)
            }
        }
    }

    override suspend fun clearGroupAppStorage(item: GroupAppItem): ClearCloneStorageResult =
        clearCloneStorage.execute(item.groupId, item.app.packageName)

    override suspend fun createShortcut(item: GroupAppItem): ShortcutCreationResult {
        val currentGroup = groupStore.find(item.groupId)
            ?: return ShortcutCreationResult.Failed("找不到這個分身空間")
        val currentApp = currentGroup.apps.firstOrNull { app ->
            app.packageName == item.app.packageName &&
                app.addedAtEpochMillis == item.app.addedAtEpochMillis
        } ?: return ShortcutCreationResult.Failed("找不到這個分身 App")
        return shortcutPublisher.requestPin(
            item.copy(
                groupName = currentGroup.name,
                groupHealth = currentGroup.health,
                app = currentApp,
            ),
        )
    }

    override suspend fun repairClone(item: GroupAppItem): RepairExecutionResult {
        val sourceAvailable = runCatching { importer.findCloneableApp(item.app.packageName) }
            .getOrNull() != null
        val preview = RepairPreviewPolicy.preview(
            target = RepairTarget(
                kind = RepairTargetKind.CLONE,
                spaceId = item.groupId,
                packageName = item.app.packageName,
            ),
            issues = if (sourceAvailable) {
                setOf(RepairIssueCode.CODE_OUT_OF_SYNC)
            } else {
                setOf(RepairIssueCode.SOURCE_MISSING)
            },
            sourceAvailable = sourceAvailable,
        )
        val option = preview.options.firstOrNull { it.action == RepairAction.RESYNC_CODE }
            ?: return RepairExecutionResult.ActionUnavailable
        if (!option.enabled) return RepairExecutionResult.ActionUnavailable

        val operation = operationTracker.start(
            OperationKind.REPAIR_CLONE,
            org.apptwin.operations.OperationTarget(item.groupId, item.app.packageName),
        )
        val applying = operationTracker.applying(operation)
        val result = ExecuteRepairUseCase(
            RepairExecutor { _, action ->
                if (action != RepairAction.RESYNC_CODE) {
                    RepairExecutionResult.ActionUnavailable
                } else {
                    when (val sync = importer.sync(item.app.packageName)) {
                        is RevisionImportResult.Activated,
                        is RevisionImportResult.AlreadyCurrent,
                        -> RepairExecutionResult.Completed
                        is RevisionImportResult.Rejected ->
                            RepairExecutionResult.Failed("REVISION_REJECTED")
                        is RevisionImportResult.Failed ->
                            RepairExecutionResult.Failed("REVISION_SYNC_FAILED")
                    }
                }
            },
        ).execute(preview, RepairAction.RESYNC_CODE)
        when (result) {
            RepairExecutionResult.Completed -> {
                groupStore.updateAppState(item.groupId, item.app.packageName, GroupAppState.ADDED)
                operationTracker.complete(applying)
                operationTracker.clearFailures(
                    OperationTarget(item.groupId, item.app.packageName),
                    setOf(OperationKind.LAUNCH_CLONE, OperationKind.REPAIR_CLONE),
                )
            }
            is RepairExecutionResult.Failed -> operationTracker.fail(applying, result.code)
            RepairExecutionResult.ActionUnavailable ->
                operationTracker.fail(applying, "REPAIR_UNAVAILABLE")
            RepairExecutionResult.DestructiveConfirmationRequired ->
                operationTracker.fail(applying, "CONFIRMATION_REQUIRED")
        }
        return result
    }

    override suspend fun resolveDeepLink(uri: String): List<Pair<String, String>> {
        val parsed = Uri.parse(uri)
        require(parsed.scheme in setOf("http", "https")) { "只支援 http/https 連結" }
        val intent = Intent(Intent.ACTION_VIEW, parsed).addCategory(Intent.CATEGORY_BROWSABLE)
        return groupStore.loadSnapshot().groups.flatMap { group ->
            val userId = group.environmentBinding?.internalId ?: return@flatMap emptyList()
            val resolvedPackages = runCatching {
                VPackageManager.get().queryIntentActivities(
                    intent,
                    intent.resolveType(application),
                    0,
                    userId,
                )
            }.getOrDefault(emptyList()).mapNotNull { it.activityInfo?.packageName }.toSet()
            group.apps
                .filter { it.packageName in resolvedPackages }
                .map { group.id to it.packageName }
        }
    }

    override suspend fun launchDeepLink(
        item: GroupAppItem,
        uri: String,
    ): RuntimeLaunchResult {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        return launchClone(
            item,
            pureRuntimeLaunch = { group, app ->
                runtimeController.installAndLaunchIntent(
                    group, app, intent, allowPackageRepair = false,
                )
            },
            runtimeLaunch = { group, app ->
                runtimeController.installAndLaunchIntent(group, app, intent)
            },
        )
    }

    private fun launchClone(
        item: GroupAppItem,
        pureRuntimeLaunch: (Group, GroupApp) -> RuntimeLaunchResult,
        runtimeLaunch: (Group, GroupApp) -> RuntimeLaunchResult,
    ): RuntimeLaunchResult {
        var runtimeResult: RuntimeLaunchResult? = null
        val launch = LaunchCloneAppUseCase(
            store = groupStore,
            source = sourcePreparer,
            runtime = CloneLaunchRuntime { group, app ->
                runtimeLaunch(group, app).also { runtimeResult = it }.let {
                    when (it) {
                        is RuntimeLaunchResult.Started -> CloneRuntimeLaunchResult.Started
                        RuntimeLaunchResult.PackageRepairRequired ->
                            CloneRuntimeLaunchResult.Failed(CloneLaunchFailure.UNKNOWN)
                        is RuntimeLaunchResult.Failed ->
                            CloneRuntimeLaunchResult.Failed(classifyLaunchFailure(it.reason))
                    }
                }
            },
            operations = operationTracker,
            pureSource = CloneSourceCurrentVerifier(importer::isSourceCurrent),
            pureRuntime = CloneLaunchRuntime { group, app ->
                when (val result = pureRuntimeLaunch(group, app)) {
                    is RuntimeLaunchResult.Started -> {
                        runtimeResult = result
                        CloneRuntimeLaunchResult.Started
                    }
                    RuntimeLaunchResult.PackageRepairRequired ->
                        CloneRuntimeLaunchResult.RepairRequired
                    is RuntimeLaunchResult.Failed -> {
                        runtimeResult = result
                        CloneRuntimeLaunchResult.Failed(classifyLaunchFailure(result.reason))
                    }
                }
            },
        ).execute(item.groupId, item.app.packageName)
        return when (launch) {
            LaunchCloneAppResult.Started ->
                runtimeResult ?: RuntimeLaunchResult.Failed("啟動結果遺失")
            LaunchCloneAppResult.SpaceNotFound -> RuntimeLaunchResult.Failed("找不到分身空間")
            LaunchCloneAppResult.CloneNotFound -> RuntimeLaunchResult.Failed("找不到分身 App")
            LaunchCloneAppResult.SpaceUnavailable ->
                RuntimeLaunchResult.Failed("分身空間目前無法使用")
            LaunchCloneAppResult.SourceMissing ->
                RuntimeLaunchResult.Failed("手機上的原始 App 已移除")
            is LaunchCloneAppResult.Rejected ->
                RuntimeLaunchResult.Failed("無法同步：${launch.reason}")
            is LaunchCloneAppResult.Failed ->
                RuntimeLaunchResult.Failed(launch.reason.name, launch.error)
        }
    }

    private fun prepareCloneSource(packageName: String): CloneSourcePreparationResult {
        val sourceInstalled = runCatching { importer.findCloneableApp(packageName) }
            .getOrNull() != null
        if (!sourceInstalled) return CloneSourcePreparationResult.SourceMissing
        return when (val sync = importer.sync(packageName)) {
            is RevisionImportResult.Activated -> CloneSourcePreparationResult.Ready
            is RevisionImportResult.AlreadyCurrent -> CloneSourcePreparationResult.AlreadyCurrent
            is RevisionImportResult.Rejected ->
                CloneSourcePreparationResult.Rejected(classifyPreparationRejection(sync.reason))
            is RevisionImportResult.Failed ->
                CloneSourcePreparationResult.Rejected(classifyPreparationRejection(sync.reason))
        }
    }

    private fun classifyPreparationRejection(reason: String): ClonePreparationRejection = when {
        reason.contains("rollback", ignoreCase = true) ||
            reason.contains("version", ignoreCase = true) ->
            ClonePreparationRejection.REVISION_ROLLBACK
        reason.contains("sign", ignoreCase = true) ->
            ClonePreparationRejection.SIGNATURE_REPLACEMENT
        reason.contains("APK", ignoreCase = true) ||
            reason.contains("artifact", ignoreCase = true) ||
            reason.contains("split", ignoreCase = true) ->
            ClonePreparationRejection.ARTIFACT_INVALID
        else -> ClonePreparationRejection.UNKNOWN
    }

    private fun classifyLaunchFailure(reason: String): CloneLaunchFailure = when {
        reason.contains("identity", ignoreCase = true) ||
            reason.contains("digest", ignoreCase = true) ->
            CloneLaunchFailure.PACKAGE_IDENTITY_MISMATCH
        reason.contains("install", ignoreCase = true) ->
            CloneLaunchFailure.PACKAGE_INSTALL_FAILED
        reason.contains("activity", ignoreCase = true) ||
            reason.contains("launch", ignoreCase = true) ||
            reason.contains("啟動") ->
            CloneLaunchFailure.ACTIVITY_START_FAILED
        else -> CloneLaunchFailure.UNKNOWN
    }
}
