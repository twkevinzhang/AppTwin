package org.apptwin

import android.app.Application
import org.apptwin.archive.AndroidSpaceArchiveOperations
import org.apptwin.groups.FileGroupAppRemovalJournal
import org.apptwin.groups.FileGroupOperationJournal
import org.apptwin.groups.FileGroupStore
import org.apptwin.groups.GroupAppRemovalCoordinator
import org.apptwin.groups.GroupAppState
import org.apptwin.groups.GroupLifecycleCoordinator
import org.apptwin.gms.AndroidGmsOperations
import org.apptwin.gms.runtime.SharedPreferencesGmsOperationReceiptStore
import org.apptwin.gms.usecases.GmsLifecycleResult
import org.apptwin.gms.production
import org.apptwin.operations.FileOperationRecordStore
import org.apptwin.operations.OperationKind
import org.apptwin.operations.OperationReconciler
import org.apptwin.operations.OperationRecoveryDecision
import org.apptwin.operations.OperationRecoveryHandler
import org.apptwin.operations.OperationTracker
import org.apptwin.revision.AndroidPackageRevisionImporter
import org.apptwin.runtime.VirtualRuntimeController
import org.apptwin.usecases.ClearSpaceStorageUseCase

/** Application-owned graph: shared stores, journals, runtime, receipts and provisioning identity. */
internal class AndroidOperationCompositionRoot(private val application: Application) {
    private val importer = AndroidPackageRevisionImporter(application)
    private val groupStore = FileGroupStore(application)
    private val runtimeController = VirtualRuntimeController(application)
    private val shortcutPublisher = GroupAppShortcutPublisher(application)
    private val operationStore = FileOperationRecordStore(application)
    private val operationTracker = OperationTracker(operationStore)
    private val clearSpaceStorage = ClearSpaceStorageUseCase(
        store = groupStore,
        runtime = runtimeController::clearAppStorage,
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
    private val provisioningBindings = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val lifecycle = GroupLifecycleCoordinator(
        store = groupStore,
        runtime = runtimeController,
        journal = FileGroupOperationJournal(application),
        initialize = { group ->
            provisioningBindings[group.id] = requireNotNull(group.environmentBinding).internalId
            try {
                gms.grantNetworkConsent(group.id)
                val result = gms.initializeForSpace(group.id)
                check(result is GmsLifecycleResult.Completed || result is GmsLifecycleResult.AlreadySatisfied) { "Google 服務初始化失敗：$result" }
            } finally { provisioningBindings.remove(group.id) }
        },
    )
    private val appRemoval = GroupAppRemovalCoordinator(
        store = groupStore,
        runtime = runtimeController,
        journal = FileGroupAppRemovalJournal(application),
    )
    private val gmsOperationReceipts = SharedPreferencesGmsOperationReceiptStore(application)
    private val gms = AndroidGmsOperations.production(
        application,
        groupStore,
        gmsOperationReceipts,
        provisionalBinding = provisioningBindings::get,
    )
    private val archives = AndroidSpaceArchiveOperations(
        application = application,
        groups = groupStore,
        lifecycle = lifecycle,
        runtime = runtimeController,
        revisions = importer,
    )
    private val deletedGroupCleanup = DeletedGroupCleanup(
        receipts = gmsOperationReceipts,
        disableShortcuts = shortcutPublisher::disable,
    )

    val spaces = AndroidSpaceOperations(groupStore, runtimeController, lifecycle, deletedGroupCleanup, clearSpaceStorage)
    val clones = AndroidCloneOperations(application, groupStore, importer, runtimeController, shortcutPublisher, operationTracker, appRemoval)
    val permissions = AndroidClonePermissionOperations(application, groupStore, runtimeController)
    val accounts = AndroidSpaceAccountOperations(application, groupStore, runtimeController)
    val archiveOperations = AndroidSpaceArchiveOperationsPort(archives)
    val snapshots = AndroidMainSnapshotOperations(groupStore, operationStore, importer, runtimeController, shortcutPublisher, gms, archives, permissions, accounts)
    val diagnostics = AndroidDiagnosticOperations(groupStore, operationStore, importer)
    val backgroundRecovery = AndroidBackgroundRecoveryOperations(application, groupStore, lifecycle, archives, appRemoval, operationReconciler, gms)
    /** Reuse this graph when multiple entry points need the compatibility aggregate. */
    fun mainOperations(): MainOperations = AndroidMainOperations(this)
}
