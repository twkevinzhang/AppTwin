package org.apptwin.groups

import java.util.UUID

interface GroupEnvironmentRuntime {
    fun createEnvironment(groupId: String, groupName: String): EnvironmentBinding
    fun findEnvironment(groupId: String): EnvironmentBinding?
    fun environmentExists(binding: EnvironmentBinding): Boolean
    fun copyPrivateAppData(
        source: EnvironmentBinding,
        destination: EnvironmentBinding,
        apps: List<GroupApp>,
    )
    fun deleteEnvironment(binding: EnvironmentBinding)
}

enum class GroupOperationType { CREATE, MIGRATE, DELETE }

enum class GroupOperationPhase { STARTED, ENVIRONMENT_CREATED }

data class GroupOperation(
    val groupId: String,
    val type: GroupOperationType,
    val phase: GroupOperationPhase,
    val groupName: String? = null,
    val environmentBinding: EnvironmentBinding? = null,
    val sourceBinding: EnvironmentBinding? = null,
    val startedAtEpochMillis: Long,
)

interface GroupOperationJournal {
    fun listAll(): List<GroupOperation>
    fun write(operation: GroupOperation)
    fun remove(groupId: String)
}

/** Owns every transition that creates, binds, validates, or destroys a Group environment. */
class GroupLifecycleCoordinator(
    private val store: GroupStore,
    private val runtime: GroupEnvironmentRuntime,
    private val journal: GroupOperationJournal,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun createGroup(name: String): Group {
        val groupId = idFactory()
        val started = GroupOperation(
            groupId = groupId,
            type = GroupOperationType.CREATE,
            phase = GroupOperationPhase.STARTED,
            groupName = name.trim(),
            startedAtEpochMillis = clock(),
        )
        journal.write(started)
        var binding: EnvironmentBinding? = null
        return try {
            binding = runtime.createEnvironment(groupId, name.trim())
            journal.write(
                started.copy(
                    phase = GroupOperationPhase.ENVIRONMENT_CREATED,
                    environmentBinding = binding,
                ),
            )
            store.create(groupId, name, binding, started.startedAtEpochMillis).also {
                journal.remove(groupId)
            }
        } catch (error: Throwable) {
            binding?.let { created ->
                runCatching { runtime.deleteEnvironment(created) }
                    .onSuccess { journal.remove(groupId) }
            }
            throw error
        }
    }

    fun deleteGroup(groupId: String): Boolean {
        val group = store.find(groupId) ?: return false
        val binding = requireNotNull(group.environmentBinding) { "群組環境尚未建立" }
        store.updateHealth(groupId, GroupHealth.DELETING)
        journal.write(
            GroupOperation(
                groupId = groupId,
                type = GroupOperationType.DELETE,
                phase = GroupOperationPhase.STARTED,
                environmentBinding = binding,
                startedAtEpochMillis = clock(),
            ),
        )
        runtime.deleteEnvironment(binding)
        check(!runtime.environmentExists(binding)) { "群組環境仍存在" }
        check(store.delete(groupId)) { "群組資料不存在" }
        journal.remove(groupId)
        return true
    }

    fun reconcile() {
        recoverOperations()
        val groups = store.listAll().sortedWith(compareBy(Group::createdAtEpochMillis, Group::id))
        val activeOwners = linkedMapOf<EnvironmentBinding, Group>()
        groups.asSequence()
            .filter { it.health != GroupHealth.PROVISIONING }
            .forEach { group ->
                group.environmentBinding?.let { activeOwners.putIfAbsent(it, group) }
            }
        groups.forEach { group ->
            if (group.googleServicesState == GoogleServicesState.PREPARING) {
                store.updateGoogleServicesState(group.id, GoogleServicesState.FAILED)
            }
            when (group.health) {
                GroupHealth.PROVISIONING -> reconcileProvisioning(group, activeOwners)
                GroupHealth.HEALTHY -> validateHealthy(group, activeOwners)
                GroupHealth.DELETING -> resumeDelete(group)
                GroupHealth.DAMAGED -> Unit
            }
        }
    }

    private fun recoverOperations() {
        journal.listAll().forEach { operation ->
            val group = store.find(operation.groupId)
            when (operation.type) {
                GroupOperationType.CREATE -> when {
                    group != null -> journal.remove(operation.groupId)
                    operation.environmentBinding != null -> {
                        runtime.deleteEnvironment(operation.environmentBinding)
                        journal.remove(operation.groupId)
                    }
                    else -> {
                        runtime.findEnvironment(operation.groupId)?.let(runtime::deleteEnvironment)
                        journal.remove(operation.groupId)
                    }
                }
                GroupOperationType.MIGRATE -> when {
                    group == null -> {
                        operation.environmentBinding?.let(runtime::deleteEnvironment)
                        journal.remove(operation.groupId)
                    }
                    group.health != GroupHealth.PROVISIONING -> journal.remove(operation.groupId)
                    operation.environmentBinding != null -> completeMigration(group, operation)
                    else -> runtime.findEnvironment(operation.groupId)?.let { recovered ->
                        completeMigration(
                            group,
                            operation.copy(
                                phase = GroupOperationPhase.ENVIRONMENT_CREATED,
                                environmentBinding = recovered,
                            ),
                        )
                    }
                }
                GroupOperationType.DELETE -> when {
                    group == null -> journal.remove(operation.groupId)
                    else -> resumeDelete(group)
                }
            }
        }
    }

    private fun reconcileProvisioning(
        group: Group,
        activeOwners: MutableMap<EnvironmentBinding, Group>,
    ) {
        val legacy = group.environmentBinding
        if (legacy != null && legacy.internalId > 0 && runtime.environmentExists(legacy)) {
            val owner = activeOwners[legacy]
            if (owner == null) {
                store.completeProvisioning(group.id, legacy)?.let { activeOwners[legacy] = it }
                return
            }
        }
        migrateToDedicatedEnvironment(group, legacy)
            ?.let { migrated -> migrated.environmentBinding?.let { activeOwners[it] = migrated } }
    }

    private fun validateHealthy(
        group: Group,
        activeOwners: MutableMap<EnvironmentBinding, Group>,
    ) {
        val binding = group.environmentBinding ?: run {
            store.updateHealth(group.id, GroupHealth.DAMAGED)
            return
        }
        val owner = activeOwners[binding]
        if (!runtime.environmentExists(binding) || (owner != null && owner.id != group.id)) {
            store.updateHealth(group.id, GroupHealth.DAMAGED)
        }
    }

    private fun migrateToDedicatedEnvironment(
        group: Group,
        source: EnvironmentBinding?,
    ): Group? {
        val started = GroupOperation(
            groupId = group.id,
            type = GroupOperationType.MIGRATE,
            phase = GroupOperationPhase.STARTED,
            groupName = group.name,
            sourceBinding = source,
            startedAtEpochMillis = clock(),
        )
        journal.write(started)
        val destination = runtime.createEnvironment(group.id, group.name)
        val operation = started.copy(
            phase = GroupOperationPhase.ENVIRONMENT_CREATED,
            environmentBinding = destination,
        )
        journal.write(operation)
        return completeMigration(group, operation)
    }

    private fun completeMigration(group: Group, operation: GroupOperation): Group? {
        val destination = requireNotNull(operation.environmentBinding)
        operation.sourceBinding
            ?.takeIf(runtime::environmentExists)
            ?.let { source -> runtime.copyPrivateAppData(source, destination, group.apps) }
        store.updateGoogleServicesState(group.id, GoogleServicesState.NOT_PREPARED)
        return store.completeProvisioning(group.id, destination).also {
            journal.remove(group.id)
        }
    }

    private fun resumeDelete(group: Group) {
        val binding = group.environmentBinding ?: run {
            store.updateHealth(group.id, GroupHealth.DAMAGED)
            journal.remove(group.id)
            return
        }
        if (runtime.environmentExists(binding)) runtime.deleteEnvironment(binding)
        check(!runtime.environmentExists(binding)) { "群組環境仍存在" }
        store.delete(group.id)
        journal.remove(group.id)
    }
}
