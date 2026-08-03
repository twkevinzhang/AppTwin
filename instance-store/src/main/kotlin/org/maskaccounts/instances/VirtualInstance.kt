package org.maskaccounts.instances

import java.util.UUID

enum class InstanceState {
    READY,
    SOURCE_MISSING,
}

data class VirtualInstance(
    val id: String,
    val packageName: String,
    val displayName: String,
    val createdAtEpochMillis: Long,
    val state: InstanceState = InstanceState.READY,
) {
    init {
        require(ID.matches(id)) { "Invalid instance id" }
        require(PACKAGE_NAME.matches(packageName)) { "Invalid Android package name" }
        require(displayName.isNotBlank()) { "Instance display name must not be blank" }
        require(createdAtEpochMillis >= 0) { "Creation time must not be negative" }
    }

    private companion object {
        val ID = Regex("[a-f0-9-]{36}")
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

interface InstanceStore {
    fun create(packageName: String, displayName: String, createdAtEpochMillis: Long): VirtualInstance
    fun listAll(): List<VirtualInstance>
    fun list(packageName: String): List<VirtualInstance>
    fun find(instanceId: String): VirtualInstance?
    fun rename(instanceId: String, displayName: String): VirtualInstance?
    fun delete(instanceId: String): Boolean
}

class InMemoryInstanceStore(
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) : InstanceStore {
    private val instances = linkedMapOf<String, VirtualInstance>()

    @Synchronized
    override fun create(
        packageName: String,
        displayName: String,
        createdAtEpochMillis: Long,
    ): VirtualInstance {
        val instance = VirtualInstance(
            id = idFactory(),
            packageName = packageName,
            displayName = displayName.trim(),
            createdAtEpochMillis = createdAtEpochMillis,
        )
        check(instance.id !in instances) { "Duplicate instance id" }
        instances[instance.id] = instance
        return instance
    }

    @Synchronized
    override fun listAll(): List<VirtualInstance> = instances.values
        .sortedWith(INSTANCE_ORDER)

    @Synchronized
    override fun list(packageName: String): List<VirtualInstance> = instances.values
        .filter { it.packageName == packageName }
        .sortedWith(INSTANCE_ORDER)

    @Synchronized
    override fun find(instanceId: String): VirtualInstance? = instances[instanceId]

    @Synchronized
    override fun rename(instanceId: String, displayName: String): VirtualInstance? {
        val trimmedDisplayName = displayName.trim()
        require(trimmedDisplayName.isNotBlank()) { "Instance display name must not be blank" }
        val current = instances[instanceId] ?: return null
        return current.copy(displayName = trimmedDisplayName).also { instances[instanceId] = it }
    }

    @Synchronized
    override fun delete(instanceId: String): Boolean = instances.remove(instanceId) != null

    private companion object {
        val INSTANCE_ORDER = compareBy(VirtualInstance::createdAtEpochMillis, VirtualInstance::id)
    }
}
