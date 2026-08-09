package org.apptwin.groups

import java.util.UUID

interface GroupEnvironmentRuntime {
    fun createEnvironment(groupId: String, groupName: String): EnvironmentBinding
    fun findEnvironment(groupId: String): EnvironmentBinding?
    fun environmentExists(binding: EnvironmentBinding): Boolean
    fun deleteEnvironment(binding: EnvironmentBinding)
}

enum class GroupOperationType { CREATE, DELETE }

enum class GroupOperationPhase { STARTED, ENVIRONMENT_CREATED }

data class GroupOperation(
    val groupId: String,
    val type: GroupOperationType,
    val phase: GroupOperationPhase,
    val groupName: String? = null,
    val environmentBinding: EnvironmentBinding? = null,
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
        groups.forEach { group ->
                group.environmentBinding?.let { activeOwners.putIfAbsent(it, group) }
            }
        groups.forEach { group ->
            when (group.health) {
                GroupHealth.PROVISIONING -> store.updateHealth(group.id, GroupHealth.DAMAGED)
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
                GroupOperationType.DELETE -> when {
                    group == null -> journal.remove(operation.groupId)
                    else -> resumeDelete(group)
                }
            }
        }
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
