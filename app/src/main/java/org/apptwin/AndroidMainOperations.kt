package org.apptwin

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.security.SecureRandom
import org.apptwin.compatibility.CompatibilityAssessmentPolicy
import org.apptwin.compatibility.CompatibilityLimitation
import org.apptwin.compatibility.DeviceValidation
import org.apptwin.compatibility.PackageCompatibilityFacts
import org.apptwin.diagnostics.DiagnosticCloneSnapshot
import org.apptwin.diagnostics.DiagnosticOperationSnapshot
import org.apptwin.diagnostics.DiagnosticSnapshot
import org.apptwin.diagnostics.DiagnosticSpaceSnapshot
import org.apptwin.diagnostics.DiagnosticsExporter
import org.apptwin.groups.FileGroupAppRemovalJournal
import org.apptwin.groups.FileGroupOperationJournal
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.Group
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupAppRemovalCoordinator
import org.apptwin.groups.GroupAppRemovalResult
import org.apptwin.groups.GroupAppState
import org.apptwin.groups.GroupHealth
import org.apptwin.groups.GroupLifecycleCoordinator
import org.apptwin.groups.GroupReconciliationResult
import org.apptwin.operations.FileOperationRecordStore
import org.apptwin.operations.OperationKind
import org.apptwin.operations.OperationReconciler
import org.apptwin.operations.OperationRecoveryDecision
import org.apptwin.operations.OperationRecoveryHandler
import org.apptwin.operations.OperationTracker
import org.apptwin.operations.OperationTarget
import org.apptwin.revision.ActiveRevisionSummary
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.revision.RevisionImportResult
import org.apptwin.repair.ExecuteRepairUseCase
import org.apptwin.repair.RepairAction
import org.apptwin.repair.RepairExecutionResult
import org.apptwin.repair.RepairExecutor
import org.apptwin.repair.RepairIssueCode
import org.apptwin.repair.RepairPreviewPolicy
import org.apptwin.repair.RepairTarget
import org.apptwin.repair.RepairTargetKind
import org.apptwin.runtime.RuntimeLaunchResult
import org.apptwin.runtime.VirtualRuntimeController
import org.apptwin.runtime.GroupAppRuntimeSupport
import org.apptwin.runtime.RuntimeCompatibility
import org.apptwin.spaces.SpaceStatePolicy
import org.apptwin.usecases.AddCloneAppResult
import org.apptwin.usecases.AddCloneAppUseCase
import org.apptwin.usecases.CloneLaunchRuntime
import org.apptwin.usecases.CloneLaunchFailure
import org.apptwin.usecases.ClonePreparationRejection
import org.apptwin.usecases.CloneRuntimeLaunchResult
import org.apptwin.usecases.CloneSourcePreparationResult
import org.apptwin.usecases.CloneSourcePreparer
import org.apptwin.usecases.LaunchCloneAppResult
import org.apptwin.usecases.LaunchCloneAppUseCase
import com.lody.virtual.client.ipc.VPackageManager

/** Android adapters composed behind the ViewModel's blocking-operation port. */
internal class AndroidMainOperations(private val application: Application) : MainOperations {
    private val importer = AndroidPackageRevisionImporter(application)
    private val groupStore = FileGroupStore(application)
    private val runtimeController = VirtualRuntimeController(application)
    private val shortcutPublisher = GroupAppShortcutPublisher(application)
    private val operationStore = FileOperationRecordStore(application)
    private val operationTracker = OperationTracker(operationStore)
    private val sourcePreparer = CloneSourcePreparer(::prepareCloneSource)
    private val addClone = AddCloneAppUseCase(
        store = groupStore,
        source = sourcePreparer,
        operations = operationTracker,
    )
    private val operationReconciler = OperationReconciler(
        store = operationStore,
        handlers = mapOf(
            OperationKind.ADD_CLONE to OperationRecoveryHandler {
                // Membership is the durable commit. If it is absent, the interrupted add safely
                // rolled back and the user may retry; if present, there is no work left to replay.
                OperationRecoveryDecision.COMPLETED
            },
            OperationKind.LAUNCH_CLONE to OperationRecoveryHandler { record ->
                val packageName = record.target.packageName
                    ?: return@OperationRecoveryHandler OperationRecoveryDecision.COMPLETED
                val app = groupStore.find(record.target.spaceId)?.apps
                    ?.firstOrNull { it.packageName == packageName }
                if (app?.state == GroupAppState.INSTALLING) {
                    groupStore.updateAppState(
                        record.target.spaceId,
                        packageName,
                        GroupAppState.FAILED,
                    )
                }
                OperationRecoveryDecision.COMPLETED
            },
            OperationKind.REPAIR_CLONE to OperationRecoveryHandler {
                OperationRecoveryDecision.COMPLETED
            },
        ),
    )
    private val lifecycle = GroupLifecycleCoordinator(
        store = groupStore,
        runtime = runtimeController,
        journal = FileGroupOperationJournal(application),
    )
    private val appRemoval = GroupAppRemovalCoordinator(
        store = groupStore,
        runtime = runtimeController,
        journal = FileGroupAppRemovalJournal(application),
    )

    override suspend fun refreshSnapshot(): MainRefreshSnapshot {
        val storage = readStorageStatus()
        val entries = runCatching(importer::listCloneableApps).getOrDefault(emptyList())
        val groupSnapshot = groupStore.loadSnapshot()
        groupSnapshot.groups.forEach { group ->
            runCatching { runtimeController.syncEnvironmentLabel(group) }
        }
        val activeRevisions = linkedMapOf<String, ActiveRevisionSummary?>()
        val warnings = groupSnapshot.issues.map { issue ->
            "Group ${issue.groupId}/${issue.metadataName} 無法讀取"
        }.toMutableList()
        entries.forEach { entry ->
            activeRevisions[entry.packageName] = runCatching { importer.active(entry.packageName) }
                .getOrElse { error ->
                    warnings += "${entry.packageName} active revision 無法讀取：${error.userMessage()}"
                    null
                }
        }
        val permissions = buildMap {
            groupSnapshot.groups.forEach { group ->
                val userId = group.environmentBinding?.internalId ?: return@forEach
                group.apps.forEach { app ->
                    val state = ClonePermissionState(
                        cameraGranted = virtualPermissionGranted(
                            Manifest.permission.CAMERA,
                            app.packageName,
                            userId,
                        ),
                        microphoneGranted = virtualPermissionGranted(
                            Manifest.permission.RECORD_AUDIO,
                            app.packageName,
                            userId,
                        ),
                    )
                    put("${group.id}:${app.packageName}", state)
                }
            }
        }
        return MainRefreshSnapshot(
            storage = storage,
            entries = entries,
            groups = groupSnapshot.groups,
            activeRevisions = activeRevisions,
            dataWarnings = warnings,
            operations = operationStore.listPending(),
            permissions = permissions,
        )
    }

    override suspend fun findGroup(groupId: String): Group? = groupStore.find(groupId)

    override suspend fun createGroup(name: String): Group = lifecycle.createGroup(name)

    override suspend fun renameGroup(groupId: String, name: String): Group? =
        groupStore.rename(groupId, name)?.also(runtimeController::syncEnvironmentLabel)

    override suspend fun deleteGroup(groupId: String): Group? {
        val group = groupStore.find(groupId) ?: return null
        check(lifecycle.deleteGroup(groupId)) { "群組資料不存在" }
        return group
    }

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
        launchClone(item) { group, app -> runtimeController.installAndLaunch(group, app) }

    private fun launchClone(
        item: GroupAppItem,
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
                        is RuntimeLaunchResult.Failed ->
                            CloneRuntimeLaunchResult.Failed(classifyLaunchFailure(it.reason))
                    }
                }
            },
            operations = operationTracker,
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
        return appRemoval.remove(item.groupId, item.app.packageName)
    }

    override suspend fun createShortcut(item: GroupAppItem): ShortcutCreationResult =
        shortcutPublisher.requestPin(item)

    override suspend fun exportDiagnostics(): String {
        val groupSnapshot = groupStore.loadSnapshot()
        val operations = operationStore.listPending()
        val entries = runCatching(importer::listCloneableApps).getOrDefault(emptyList())
            .associateBy { it.packageName }
        val spaces = groupSnapshot.groups.map { group ->
            val spaceState = SpaceStatePolicy.assess(group, operations)
            val cloneStates = spaceState.clones.associateBy { it.packageName }
            DiagnosticSpaceSnapshot(
                spaceId = group.id,
                lifecycle = spaceState.lifecycle,
                clones = group.apps.map { app ->
                    val source = entries[app.packageName]
                    val limitations = setOf(
                        CompatibilityLimitation.PUSH_DELIVERY,
                        CompatibilityLimitation.CAMERA,
                        CompatibilityLimitation.MICROPHONE,
                        CompatibilityLimitation.BACKGROUND_EXECUTION,
                    )
                    val validations = source?.let { installed ->
                        GroupAppRuntimeSupport.validation(
                            app.packageName,
                            installed.versionCode,
                            Build.VERSION.SDK_INT,
                        )
                    }?.let { validation ->
                        setOf(
                            DeviceValidation(
                                androidApi = validation.androidApi,
                                versionCode = validation.versionCode,
                            ),
                        )
                    }.orEmpty()
                    val assessment = CompatibilityAssessmentPolicy.assess(
                        PackageCompatibilityFacts(
                            packageName = app.packageName,
                            versionCode = source?.versionCode ?: 0,
                            sourceInstalled = source != null,
                            hasLauncher = source?.let { true },
                            abiSupported = null,
                            baseArtifactReadable = null,
                            splitSetComplete = null,
                            signatureTrusted = null,
                            requiredFeaturesSatisfied = null,
                            knownLimitations = limitations,
                            validations = validations,
                        ),
                    )
                    DiagnosticCloneSnapshot(
                        packageName = app.packageName,
                        lifecycle = cloneStates.getValue(app.packageName).lifecycle,
                        compatibility = assessment.level,
                    )
                },
            )
        }
        val report = DiagnosticsExporter.export(
            DiagnosticSnapshot(
                appVersion = BuildConfig.VERSION_NAME,
                androidApi = Build.VERSION.SDK_INT,
                spaces = spaces,
                operations = operations.map { operation ->
                    DiagnosticOperationSnapshot(
                        kind = operation.kind,
                        phase = operation.phase,
                        failureCode = operation.failureCode,
                    )
                },
            ),
            exportSalt = ByteArray(32).also(SecureRandom()::nextBytes),
        )
        return report.text
    }

    override suspend fun repairClone(item: GroupAppItem): RepairExecutionResult {
        val sourceAvailable = runCatching { importer.listCloneableApps() }
            .getOrDefault(emptyList())
            .any { it.packageName == item.app.packageName }
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

    override suspend fun setClonePermission(
        item: GroupAppItem,
        permission: String,
        granted: Boolean,
    ): Boolean {
        if (permission !in SUPPORTED_RUNTIME_PERMISSIONS) return false
        val group = groupStore.find(item.groupId) ?: return false
        val userId = group.environmentBinding?.internalId ?: return false
        return VPackageManager.get().setRuntimePermissionGranted(
            permission,
            item.app.packageName,
            userId,
            granted,
        )
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
    ): RuntimeLaunchResult = launchClone(item) { group, app ->
        runtimeController.installAndLaunchIntent(
            group,
            app,
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE),
        )
    }

    private fun virtualPermissionGranted(permission: String, packageName: String, userId: Int): Boolean =
        runCatching {
            VPackageManager.get().checkPermission(permission, packageName, userId) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    override suspend fun reconcileGroups(): GroupReconciliationResult = lifecycle.reconcile()

    override suspend fun reconcileAppRemovals() {
        appRemoval.reconcile()
    }

    override suspend fun reconcileApplicationOperations() {
        operationReconciler.reconcile()
    }

    private fun prepareCloneSource(packageName: String): CloneSourcePreparationResult {
        val sourceInstalled = runCatching { importer.listCloneableApps() }
            .getOrDefault(emptyList())
            .any { it.packageName == packageName }
        if (!sourceInstalled) return CloneSourcePreparationResult.SourceMissing
        return when (val sync = importer.sync(packageName)) {
            is RevisionImportResult.Activated,
            is RevisionImportResult.AlreadyCurrent,
            -> CloneSourcePreparationResult.Ready
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

    private fun readStorageStatus(): StorageStatus {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
        if (!granted) return StorageStatus(false, 0, 0)
        return StorageStatus(
            granted = true,
            downloadCount = publicDirectoryEntryCount(Environment.DIRECTORY_DOWNLOADS),
            photoCount = publicDirectoryEntryCount(Environment.DIRECTORY_DCIM) +
                publicDirectoryEntryCount(Environment.DIRECTORY_PICTURES),
        )
    }

    private fun publicDirectoryEntryCount(directoryType: String): Int = runCatching {
        @Suppress("DEPRECATION")
        Environment.getExternalStoragePublicDirectory(directoryType).listFiles()?.size ?: 0
    }.getOrDefault(0)

    private companion object {
        val SUPPORTED_RUNTIME_PERMISSIONS = setOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
    }
}

private fun Throwable.userMessage(): String = message ?: javaClass.simpleName
