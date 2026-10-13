package org.apptwin

import android.app.Application

/** Compatibility entry point; feature adapters share a single composition graph. */
internal class AndroidMainOperations(
    graph: AndroidOperationCompositionRoot,
) : MainOperations,
    SpaceOperations by graph.spaces,
    CloneOperations by graph.clones,
    ClonePermissionOperations by graph.permissions,
    SpaceAccountOperations by graph.accounts,
    SpaceArchiveOperations by graph.archiveOperations,
    MainSnapshotOperations by graph.snapshots,
    DiagnosticOperations by graph.diagnostics,
    BackgroundRecoveryOperations by graph.backgroundRecovery {

    constructor(application: Application) : this(
        (application as? AppTwinApplication)?.mainContainer?.operationGraph
            ?: AndroidOperationCompositionRoot(application),
    )

    /** Kept for direct Android integration tests that need a complete one-shot snapshot. */
    suspend fun refreshSnapshot(): MainRefreshSnapshot = refreshSnapshot(loadGroupSnapshot())
}
