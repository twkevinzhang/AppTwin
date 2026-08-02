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
    fun list(packageName: String): List<VirtualInstance>
    fun find(instanceId: String): VirtualInstance?
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
    override fun list(packageName: String): List<VirtualInstance> = instances.values
        .filter { it.packageName == packageName }
        .sortedWith(compareBy(VirtualInstance::createdAtEpochMillis, VirtualInstance::id))

    @Synchronized
    override fun find(instanceId: String): VirtualInstance? = instances[instanceId]
}
