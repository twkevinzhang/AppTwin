package org.maskaccounts.groups

import java.util.UUID

enum class GroupRuntimeState {
    NOT_PREPARED,
    PREPARING,
    READY,
    FAILED,
}
data class GroupApp(
    val packageName: String,
    val addedAtEpochMillis: Long,
) {
    init {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid Android package name" }
        require(addedAtEpochMillis >= 0) { "Added time must not be negative" }
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

data class AppGroup(
    val id: String,
    val name: String,
    val createdAtEpochMillis: Long,
    val runtimeState: GroupRuntimeState = GroupRuntimeState.NOT_PREPARED,
    val apps: List<GroupApp> = emptyList(),
) {
    init {
        require(ID.matches(id)) { "Invalid group id" }
        require(name.isNotBlank()) { "Group name must not be blank" }
        require(createdAtEpochMillis >= 0) { "Creation time must not be negative" }
        require(apps.map(GroupApp::packageName).distinct().size == apps.size) {
            "A package can only be added once per group"
        }
    }

    fun contains(packageName: String): Boolean = apps.any { it.packageName == packageName }

    private companion object {
        val ID = Regex("[a-f0-9-]{36}")
    }
}

interface GroupStore {
    fun create(name: String, createdAtEpochMillis: Long): AppGroup
    fun listAll(): List<AppGroup>
    fun find(groupId: String): AppGroup?
    fun rename(groupId: String, name: String): AppGroup?
    fun addApp(groupId: String, packageName: String, addedAtEpochMillis: Long): AppGroup?
    fun removeApp(groupId: String, packageName: String): AppGroup?
    fun updateRuntimeState(groupId: String, state: GroupRuntimeState): AppGroup?
    fun delete(groupId: String): Boolean
}

class InMemoryGroupStore(
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : GroupStore {
    private val groups = linkedMapOf<String, AppGroup>()

    @Synchronized
    override fun create(name: String, createdAtEpochMillis: Long): AppGroup {
        val group = AppGroup(
            id = idFactory(),
            name = name.trim(),
            createdAtEpochMillis = createdAtEpochMillis,
        )
        check(group.id !in groups) { "Duplicate group id" }
        groups[group.id] = group
        return group
    }

    @Synchronized
    override fun listAll(): List<AppGroup> = groups.values.sortedWith(GROUP_ORDER)

    @Synchronized
    override fun find(groupId: String): AppGroup? = groups[groupId]

    @Synchronized
    override fun rename(groupId: String, name: String): AppGroup? = update(groupId) { group ->
        group.copy(name = name.trim())
    }

    @Synchronized
    override fun addApp(
        groupId: String,
        packageName: String,
        addedAtEpochMillis: Long,
    ): AppGroup? = update(groupId) { group ->
        require(!group.contains(packageName)) { "$packageName already exists in this group" }
        group.copy(apps = group.apps + GroupApp(packageName, addedAtEpochMillis))
    }

    @Synchronized
    override fun removeApp(groupId: String, packageName: String): AppGroup? = update(groupId) { group ->
        group.copy(apps = group.apps.filterNot { it.packageName == packageName })
    }

    @Synchronized
    override fun updateRuntimeState(groupId: String, state: GroupRuntimeState): AppGroup? =
        update(groupId) { group -> group.copy(runtimeState = state) }

    @Synchronized
    override fun delete(groupId: String): Boolean = groups.remove(groupId) != null

    private fun update(groupId: String, transform: (AppGroup) -> AppGroup): AppGroup? {
        val current = groups[groupId] ?: return null
        return transform(current).also { groups[groupId] = it }
    }

    private companion object {
        val GROUP_ORDER = compareBy(AppGroup::createdAtEpochMillis, AppGroup::id)
    }
}
