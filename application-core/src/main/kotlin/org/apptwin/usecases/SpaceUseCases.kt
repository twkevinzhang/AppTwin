package org.apptwin.usecases

import org.apptwin.groups.Group
import org.apptwin.groups.GroupApp
import org.apptwin.groups.GroupAppRemovalCoordinator
import org.apptwin.groups.GroupAppRemovalResult
import org.apptwin.groups.GroupAppState
import org.apptwin.groups.GroupHealth
import org.apptwin.groups.GroupLifecycleCoordinator
import org.apptwin.groups.GroupStore
import org.apptwin.operations.OperationKind
import org.apptwin.operations.OperationTarget
import org.apptwin.operations.OperationTracker

sealed interface CreateSpaceResult {
    data class Created(val space: Group) : CreateSpaceResult
    data class Rejected(val reason: CreateSpaceRejection) : CreateSpaceResult
    data class Failed(val error: Throwable) : CreateSpaceResult
}

enum class CreateSpaceRejection { BLANK_NAME }

class CreateSpaceUseCase(
    private val lifecycle: GroupLifecycleCoordinator,
) {
    fun execute(name: String): CreateSpaceResult {
        val normalized = name.trim()
        if (normalized.isEmpty()) return CreateSpaceResult.Rejected(CreateSpaceRejection.BLANK_NAME)
        return runCatching { lifecycle.createGroup(normalized) }
            .fold(
                onSuccess = { CreateSpaceResult.Created(it) },
                onFailure = { CreateSpaceResult.Failed(it) },
            )
    }
}

sealed interface CloneSourcePreparationResult {
    data object Ready : CloneSourcePreparationResult
    data object SourceMissing : CloneSourcePreparationResult
    data class Rejected(val reason: ClonePreparationRejection) : CloneSourcePreparationResult
}

enum class ClonePreparationRejection {
    REVISION_ROLLBACK,
    SIGNATURE_REPLACEMENT,
    ARTIFACT_INVALID,
    UNKNOWN,
}

fun interface CloneSourcePreparer {
    fun prepare(packageName: String): CloneSourcePreparationResult
}

sealed interface AddCloneAppResult {
    data class Added(val space: Group) : AddCloneAppResult
    data object SpaceNotFound : AddCloneAppResult
    data object SpaceUnavailable : AddCloneAppResult
    data object AlreadyPresent : AddCloneAppResult
    data object SourceMissing : AddCloneAppResult
    data class Rejected(val reason: ClonePreparationRejection) : AddCloneAppResult
    data class Failed(val error: Throwable) : AddCloneAppResult
}

class AddCloneAppUseCase(
    private val store: GroupStore,
    private val source: CloneSourcePreparer,
    private val operations: OperationTracker,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun execute(spaceId: String, packageName: String): AddCloneAppResult {
        val space = runCatching { store.find(spaceId) }
            .getOrElse { return AddCloneAppResult.Failed(it) }
            ?: return AddCloneAppResult.SpaceNotFound
        if (space.health != GroupHealth.HEALTHY) return AddCloneAppResult.SpaceUnavailable
        if (space.contains(packageName)) return AddCloneAppResult.AlreadyPresent
        val operation = operations.start(
            OperationKind.ADD_CLONE,
            OperationTarget(spaceId, packageName),
        )
        val applying = operations.applying(operation)
        return runCatching {
            when (val preparation = source.prepare(packageName)) {
                CloneSourcePreparationResult.Ready -> {
                    val updated = requireNotNull(store.addApp(spaceId, packageName, clock())) {
                        "Space disappeared while adding clone"
                    }
                    operations.complete(applying)
                    AddCloneAppResult.Added(updated)
                }
                CloneSourcePreparationResult.SourceMissing -> {
                    operations.complete(applying)
                    AddCloneAppResult.SourceMissing
                }
                is CloneSourcePreparationResult.Rejected -> {
                    operations.complete(applying)
                    AddCloneAppResult.Rejected(preparation.reason)
                }
            }
        }.getOrElse { error ->
            operations.fail(applying, "ADD_CLONE_FAILED")
            AddCloneAppResult.Failed(error)
        }
    }
}

sealed interface CloneRuntimeLaunchResult {
    data object Started : CloneRuntimeLaunchResult
    data class Failed(val reason: CloneLaunchFailure) : CloneRuntimeLaunchResult
}

enum class CloneLaunchFailure {
    PACKAGE_INSTALL_FAILED,
    PACKAGE_IDENTITY_MISMATCH,
    ACTIVITY_START_FAILED,
    UNKNOWN,
}

fun interface CloneLaunchRuntime {
    fun launch(space: Group, app: GroupApp): CloneRuntimeLaunchResult
}

sealed interface LaunchCloneAppResult {
    data object Started : LaunchCloneAppResult
    data object SpaceNotFound : LaunchCloneAppResult
    data object CloneNotFound : LaunchCloneAppResult
    data object SpaceUnavailable : LaunchCloneAppResult
    data object SourceMissing : LaunchCloneAppResult
    data class Rejected(val reason: ClonePreparationRejection) : LaunchCloneAppResult
    data class Failed(val reason: CloneLaunchFailure, val error: Throwable? = null) :
        LaunchCloneAppResult
}

class LaunchCloneAppUseCase(
    private val store: GroupStore,
    private val source: CloneSourcePreparer,
    private val runtime: CloneLaunchRuntime,
    private val operations: OperationTracker,
) {
    fun execute(spaceId: String, packageName: String): LaunchCloneAppResult {
        val space = runCatching { store.find(spaceId) }
            .getOrElse { return LaunchCloneAppResult.Failed(CloneLaunchFailure.UNKNOWN, it) }
            ?: return LaunchCloneAppResult.SpaceNotFound
        if (space.health != GroupHealth.HEALTHY) return LaunchCloneAppResult.SpaceUnavailable
        val app = space.apps.firstOrNull { it.packageName == packageName }
            ?: return LaunchCloneAppResult.CloneNotFound
        val operation = operations.start(
            OperationKind.LAUNCH_CLONE,
            OperationTarget(spaceId, packageName),
        )
        val applying = operations.applying(operation)
        return runCatching {
            when (val preparation = source.prepare(packageName)) {
                CloneSourcePreparationResult.SourceMissing -> {
                    store.updateAppState(spaceId, packageName, GroupAppState.SOURCE_MISSING)
                    operations.complete(applying)
                    LaunchCloneAppResult.SourceMissing
                }
                is CloneSourcePreparationResult.Rejected -> {
                    store.updateAppState(spaceId, packageName, GroupAppState.FAILED)
                    operations.complete(applying)
                    LaunchCloneAppResult.Rejected(preparation.reason)
                }
                CloneSourcePreparationResult.Ready -> {
                    store.updateAppState(spaceId, packageName, GroupAppState.INSTALLING)
                    when (val launch = runtime.launch(space, app)) {
                        CloneRuntimeLaunchResult.Started -> {
                            store.updateAppState(spaceId, packageName, GroupAppState.ENABLED)
                            operations.complete(applying)
                            LaunchCloneAppResult.Started
                        }
                        is CloneRuntimeLaunchResult.Failed -> {
                            store.updateAppState(spaceId, packageName, GroupAppState.FAILED)
                            operations.fail(applying, "LAUNCH_FAILED")
                            LaunchCloneAppResult.Failed(launch.reason)
                        }
                    }
                }
            }
        }.getOrElse { error ->
            runCatching { store.updateAppState(spaceId, packageName, GroupAppState.FAILED) }
            operations.fail(applying, "LAUNCH_FAILED")
            LaunchCloneAppResult.Failed(CloneLaunchFailure.UNKNOWN, error)
        }
    }
}

class RemoveCloneAppUseCase(
    private val removal: GroupAppRemovalCoordinator,
) {
    fun execute(spaceId: String, packageName: String): GroupAppRemovalResult =
        removal.remove(spaceId, packageName)
}

sealed interface DeleteSpaceResult {
    data object Deleted : DeleteSpaceResult
    data object AlreadyAbsent : DeleteSpaceResult
    data class Failed(val error: Throwable) : DeleteSpaceResult
}

class DeleteSpaceUseCase(
    private val lifecycle: GroupLifecycleCoordinator,
) {
    fun execute(spaceId: String): DeleteSpaceResult = runCatching {
        if (lifecycle.deleteGroup(spaceId)) DeleteSpaceResult.Deleted
        else DeleteSpaceResult.AlreadyAbsent
    }.getOrElse(DeleteSpaceResult::Failed)
}
