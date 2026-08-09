package org.apptwin.groups

import java.util.UUID

const val CURRENT_GROUP_SCHEMA_VERSION = 4

enum class GroupHealth {
    PROVISIONING,
    HEALTHY,
    DAMAGED,
    DELETING,
}

enum class GroupAppState {
    ADDED,
    INSTALLING,
    ENABLED,
    DISABLED,
    SOURCE_MISSING,
    FAILED,
}

@JvmInline
value class EnvironmentBinding(val internalId: Int) {
    init {
        require(internalId >= 0) { "Environment binding must not be negative" }
    }
}

data class GroupApp(
    val packageName: String,
    val addedAtEpochMillis: Long,
    val state: GroupAppState = GroupAppState.ADDED,
) {
    init {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid Android package name" }
        require(addedAtEpochMillis >= 0) { "Added time must not be negative" }
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

data class Group(
    val id: String,
    val name: String,
    val createdAtEpochMillis: Long,
    val environmentBinding: EnvironmentBinding?,
    val health: GroupHealth,
    val apps: List<GroupApp> = emptyList(),
    val schemaVersion: Int = CURRENT_GROUP_SCHEMA_VERSION,
) {
    init {
        require(ID.matches(id)) { "Invalid group id" }
        require(name.isNotBlank()) { "Group name must not be blank" }
        require(createdAtEpochMillis >= 0) { "Creation time must not be negative" }
        require(schemaVersion == CURRENT_GROUP_SCHEMA_VERSION) { "Unsupported group schema" }
        require(apps.map(GroupApp::packageName).distinct().size == apps.size) {
            "A package can only be added once per group"
        }
        if (health in setOf(GroupHealth.HEALTHY, GroupHealth.DELETING)) {
            require(environmentBinding != null) { "An active Group must own an environment" }
        }
    }

    fun contains(packageName: String): Boolean = apps.any { it.packageName == packageName }

    private companion object {
        val ID = Regex("[a-f0-9-]{36}")
    }
}

interface GroupStore {
    fun create(
        id: String,
        name: String,
        environmentBinding: EnvironmentBinding,
        createdAtEpochMillis: Long,
    ): Group
    fun listAll(): List<Group>
    fun find(groupId: String): Group?
    fun rename(groupId: String, name: String): Group?
    fun addApp(
        groupId: String,
        packageName: String,
        addedAtEpochMillis: Long,
    ): Group?
    fun removeApp(groupId: String, packageName: String): Group?
    fun updateAppState(groupId: String, packageName: String, state: GroupAppState): Group?
    fun updateHealth(groupId: String, health: GroupHealth): Group?
    fun delete(groupId: String): Boolean
}

class InMemoryGroupStore : GroupStore {
    private val groups = linkedMapOf<String, Group>()

    @Synchronized
    override fun create(
        id: String,
        name: String,
        environmentBinding: EnvironmentBinding,
        createdAtEpochMillis: Long,
    ): Group {
        val group = Group(
            id = UUID.fromString(id).toString(),
            name = name.trim(),
            createdAtEpochMillis = createdAtEpochMillis,
            environmentBinding = environmentBinding,
            health = GroupHealth.HEALTHY,
        )
        check(group.id !in groups) { "Duplicate group id" }
        check(groups.values.none { it.environmentBinding == environmentBinding }) {
            "Environment binding already belongs to another Group"
        }
        groups[group.id] = group
        return group
    }

    /** Test fixture entrypoint. */
    fun import(record: Group): Group {
        check(record.id !in groups) { "Duplicate group id" }
        groups[record.id] = record
        return record
    }

    @Synchronized
    override fun listAll(): List<Group> = groups.values.sortedWith(GROUP_ORDER)

    @Synchronized
    override fun find(groupId: String): Group? = groups[groupId]

    @Synchronized
    override fun rename(groupId: String, name: String): Group? = update(groupId) { group ->
        group.copy(name = name.trim())
    }

    @Synchronized
    override fun addApp(
        groupId: String,
        packageName: String,
        addedAtEpochMillis: Long,
    ): Group? = update(groupId) { group ->
        require(group.health == GroupHealth.HEALTHY) { "Group is not available" }
        require(!group.contains(packageName)) { "$packageName already exists in this group" }
        group.copy(apps = group.apps + GroupApp(packageName, addedAtEpochMillis))
    }

    @Synchronized
    override fun removeApp(groupId: String, packageName: String): Group? = update(groupId) { group ->
        group.copy(apps = group.apps.filterNot { it.packageName == packageName })
    }

    @Synchronized
    override fun updateAppState(
        groupId: String,
        packageName: String,
        state: GroupAppState,
    ): Group? = update(groupId) { group ->
        require(group.contains(packageName)) { "GroupApp does not exist" }
        group.copy(
            apps = group.apps.map { app ->
                if (app.packageName == packageName) app.copy(state = state) else app
            },
        )
    }

    @Synchronized
    override fun updateHealth(groupId: String, health: GroupHealth): Group? = update(groupId) { group ->
        group.copy(health = health)
    }

    @Synchronized
    override fun delete(groupId: String): Boolean = groups.remove(groupId) != null

    private fun update(groupId: String, transform: (Group) -> Group): Group? {
        val current = groups[groupId] ?: return null
        return transform(current).also { groups[groupId] = it }
    }

    private companion object {
        val GROUP_ORDER = compareBy(Group::createdAtEpochMillis, Group::id)
    }
}
