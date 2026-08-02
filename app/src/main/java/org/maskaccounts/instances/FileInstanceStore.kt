package org.maskaccounts.instances

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import java.util.UUID

/** Persists instance identity and reserves an isolated data root without touching source app data. */
class FileInstanceStore(context: Context) : InstanceStore {
    private val root = File(context.applicationContext.filesDir, "instances")

    @Synchronized
    override fun create(
        packageName: String,
        displayName: String,
        createdAtEpochMillis: Long,
    ): VirtualInstance {
        val instance = VirtualInstance(
            id = UUID.randomUUID().toString(),
            packageName = packageName,
            displayName = displayName.trim(),
            createdAtEpochMillis = createdAtEpochMillis,
        )
        val packageRoot = File(root, packageName)
        check(packageRoot.exists() || packageRoot.mkdirs()) { "Unable to create instance package root" }
        val staging = File(packageRoot, ".staging-${instance.id}")
        check(staging.mkdirs()) { "Unable to create instance staging root" }
        return try {
            check(File(staging, "data").mkdir()) { "Unable to create isolated data root" }
            writeMetadata(File(staging, METADATA), instance)
            check(staging.renameTo(File(packageRoot, instance.id))) {
                "Unable to atomically activate instance"
            }
            instance
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    @Synchronized
    override fun list(packageName: String): List<VirtualInstance> =
        File(root, packageName).listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && !it.name.startsWith(".staging-") }
            .mapNotNull { readMetadata(File(it, METADATA)) }
            .sortedWith(compareBy(VirtualInstance::createdAtEpochMillis, VirtualInstance::id))
            .toList()

    @Synchronized
    override fun find(instanceId: String): VirtualInstance? = root.listFiles()
        .orEmpty()
        .asSequence()
        .map { File(it, "$instanceId/$METADATA") }
        .mapNotNull(::readMetadata)
        .firstOrNull()

    private fun writeMetadata(file: File, instance: VirtualInstance) {
        val properties = Properties().apply {
            setProperty("id", instance.id)
            setProperty("packageName", instance.packageName)
            setProperty("displayName", instance.displayName)
            setProperty("createdAtEpochMillis", instance.createdAtEpochMillis.toString())
            setProperty("state", instance.state.name)
        }
        FileOutputStream(file).use { output ->
            properties.store(output, "MaskAccounts virtual instance")
            output.fd.sync()
        }
    }

    private fun readMetadata(file: File): VirtualInstance? = runCatching {
        if (!file.isFile) return null
        val properties = Properties().apply { FileInputStream(file).use(::load) }
        VirtualInstance(
            id = requireNotNull(properties.getProperty("id")),
            packageName = requireNotNull(properties.getProperty("packageName")),
            displayName = requireNotNull(properties.getProperty("displayName")),
            createdAtEpochMillis = requireNotNull(
                properties.getProperty("createdAtEpochMillis")?.toLongOrNull(),
            ),
            state = InstanceState.valueOf(requireNotNull(properties.getProperty("state"))),
        )
    }.getOrNull()

    private companion object {
        const val METADATA = "instance.properties"
    }
}
